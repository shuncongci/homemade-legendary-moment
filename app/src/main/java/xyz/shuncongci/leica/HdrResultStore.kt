package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Immutable lossless working pair owned by exactly one rendered Result. Never rereads source or models.
 * HDR1 remains byte-compatible. HDR2 adds the frozen display budget for finished joint-enhanced outputs. */
object HdrResultStore {
    private const val HDR1=0x48445231
    private const val HDR2=0x48445232
    fun pairFile(result:File)=File(result.path+".hdr-pair")
    fun jpegFile(result:File)=File(result.path+".hdr.jpg")
    fun remove(result:File){pairFile(result).delete();jpegFile(result).delete()}
    fun write(file:File,sdr:Planes,hdr:Frame,cancel:Cancel){
        require(hdr.channels==3);HdrMath.checked(hdr);write(file,sdr,HdrPixels.of(hdr),cancel)
    }
    internal fun write(file:File,sdr:Planes,hdr:HdrPixels,cancel:Cancel){
        require(sdr.width==hdr.w && sdr.height==hdr.h);HdrPixels.dimensions(hdr.w,hdr.h)
        val capacity=hdr.displayCapacity?.let{HdrOutputColor.checkedCapacity(it)}
        HdrFloatFile.requireSpace(file,16L+15L*sdr.size+16*HdrMemory.MIB)
        if(capacity!=null){writeFinished(file,sdr,hdr,capacity,cancel);return}
        // Legacy HDR1 retains its original sequential channel layout and access policy.
        try{DataOutputStream(BufferedOutputStream(file.outputStream(),65536)).use{out->
            cancel.check();out.writeInt(if(capacity==null)HDR1 else HDR2);out.writeInt(hdr.w);out.writeInt(hdr.h)
            if(capacity!=null)out.writeFloat(capacity)
            for(c in 0..2){cancel.check();out.write(sdr.plane(c))}
            val block=minOf(16,hdr.h);val values=FloatArray(hdr.w*block)
            for(c in 0..2)for(y in 0 until hdr.h step block){
                val rows=minOf(block,hdr.h-y);val count=rows*hdr.w;hdr.read(c,y,rows,values,cancel)
                for(i in 0 until count)HdrPixels.valid(values[i])
                FloatBlocks.write(out,if(count==values.size)values else values.copyOf(count),cancel)
            };cancel.check()
        }}catch(t:Throwable){file.delete();throw t}
    }
    /** Consume all RGB in one strip before advancing, so joint enhancement is evaluated once.
     * The file is still the identical HDR2 planar big-endian Float32 format. No full raster/mapping. */
    private fun writeFinished(file:File,sdr:Planes,hdr:HdrPixels,capacity:Float,cancel:Cancel){
        try{cancel.check();RandomAccessFile(file,"rw").use{out->
            out.setLength(0);out.writeInt(HDR2);out.writeInt(hdr.w);out.writeInt(hdr.h);out.writeFloat(capacity)
            for(c in 0..2){cancel.check();out.write(sdr.plane(c))}
            val block=minOf(16,hdr.h);val values=FloatArray(hdr.w*block)
            val bytes=ByteBuffer.allocate(65536).order(ByteOrder.BIG_ENDIAN);val channel=out.channel
            for(y in 0 until hdr.h step block){val rows=minOf(block,hdr.h-y);val count=rows*hdr.w
                for(c in 0..2){cancel.check();hdr.read(c,y,rows,values,cancel)
                    channel.position(16L+3L*sdr.size+4L*(c.toLong()*sdr.size+y.toLong()*hdr.w))
                    var at=0
                    while(at<count){
                        cancel.check();bytes.clear();val n=minOf(count-at,bytes.capacity()/4)
                        for(i in at until at+n)HdrPixels.valid(values[i])
                        bytes.asFloatBuffer().put(values,at,n);bytes.limit(n*4)
                        while(bytes.hasRemaining()){cancel.check();check(channel.write(bytes)>0){"HDR缓存写入未前进"}}
                        at+=n
                    }
                }
            };cancel.check();check(out.length()==16L+15L*sdr.size){"HDR成片缓存长度错误"}
        }}catch(t:Throwable){file.delete();throw t}
    }
    /** Only a completed pair escapes to encoding. Do not materialize another SDR Planes or enhance again.
     * Source/header/budget are known from this synchronous write. Reader closes before failure cleanup.
     * Ownership and atomic checkpoint commit remain with the caller; no new persistent format. */
    internal fun <T> withWrittenRows(file:File,sdr:Planes,hdr:HdrPixels,cancel:Cancel,use:(HdrPixels)->T):T {
        val capacity=hdr.displayCapacity?.let{HdrOutputColor.checkedCapacity(it)}
        write(file,sdr,hdr,cancel)
        try{
            cancel.check();val header=if(capacity==null)12L else 16L
            return FileHdrPixels(file,hdr.w,hdr.h,header+3L*sdr.size,capacity).use{rows->
                val result=use(rows);cancel.check();result
            }
        }catch(t:Throwable){file.delete();throw t}
    }
    /** Production crop/save readback, preserving HDR2 budget without allocating a full float raster. */
    internal fun readRows(file:File,w:Int,h:Int,cancel:Cancel):Pair<Planes,FileHdrPixels>{
        HdrPixels.dimensions(w,h)
        check(file.length()==12L+15L*w*h || file.length()==16L+15L*w*h){"HDR成片缓存缺失或长度错误，请重新生成"}
        var header=12L;var capacity:Float?=null
        val sdr=DataInputStream(BufferedInputStream(file.inputStream(),65536)).use{input->
            cancel.check();val magic=input.readInt()
            check((magic==HDR1 || magic==HDR2) && input.readInt()==w && input.readInt()==h){"HDR成片缓存不匹配"}
            if(magic==HDR2){header=16L;capacity=HdrOutputColor.checkedCapacity(input.readFloat())}
            check(file.length()==header+15L*w*h){"HDR成片缓存缺失或长度错误，请重新生成"}
            Planes(w,h).also{p->for(c in 0..2){cancel.check();input.readFully(p.plane(c))}}
        }
        cancel.check();return sdr to FileHdrPixels(file,w,h,header+3L*w*h,capacity)
    }
    /** Numeric compatibility API returns radiance only; production persistence uses readRows. */
    fun read(file:File,w:Int,h:Int,cancel:Cancel):Pair<Planes,Frame>{
        val (sdr,pixels)=readRows(file,w,h,cancel)
        return pixels.use{p->
            val values=FloatArray(w*h*3);val row=FloatArray(w)
            for(c in 0..2)for(y in 0 until h){p.read(c,y,1,row,cancel);row.copyInto(values,c*w*h+y*w)}
            cancel.check();sdr to HdrMath.checked(Frame(w,h,3,values))
        }
    }
}
