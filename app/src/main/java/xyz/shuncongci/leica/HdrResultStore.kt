package xyz.shuncongci.leica

import java.io.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Immutable lossless working pair owned by exactly one rendered Result. Never rereads source or models. */
object HdrResultStore {
    fun pairFile(result:File)=File(result.path+".hdr-pair")
    fun jpegFile(result:File)=File(result.path+".hdr.jpg")
    fun remove(result:File){pairFile(result).delete();jpegFile(result).delete()}
    fun write(file:File,sdr:Planes,hdr:Frame,cancel:Cancel){
        require(sdr.width==hdr.w && sdr.height==hdr.h && hdr.channels==3)
        HdrMath.checked(hdr)
        HdrFloatFile.requireSpace(file,16L+15L*sdr.size+16*HdrMemory.MIB)
        try{DataOutputStream(BufferedOutputStream(file.outputStream(),65536)).use{out->
            out.writeInt(0x48445231);out.writeInt(hdr.w);out.writeInt(hdr.h)
            for(c in 0..2){cancel.check();out.write(sdr.plane(c))}
            FloatBlocks.write(out,hdr.data,cancel)
            cancel.check()
        }}catch(t:Throwable){file.delete();throw t}
    }
    internal fun write(file:File,sdr:Planes,hdr:HdrPixels,cancel:Cancel){
        require(sdr.width==hdr.w && sdr.height==hdr.h)
        HdrFloatFile.requireSpace(file,16L+15L*sdr.size+16*HdrMemory.MIB)
        try{DataOutputStream(BufferedOutputStream(file.outputStream(),65536)).use{out->
            cancel.check();out.writeInt(0x48445231);out.writeInt(hdr.w);out.writeInt(hdr.h)
            for(c in 0..2){cancel.check();out.write(sdr.plane(c))}
            val block=minOf(16,hdr.h);val values=FloatArray(hdr.w*block)
            for(c in 0..2)for(y in 0 until hdr.h step block){
                val rows=minOf(block,hdr.h-y);val count=rows*hdr.w;hdr.read(c,y,rows,values,cancel)
                for(i in 0 until count)HdrPixels.valid(values[i])
                FloatBlocks.write(out,if(count==values.size)values else values.copyOf(count),cancel)
            };cancel.check()
        }}catch(t:Throwable){file.delete();throw t}
    }
    /** Production crop/save readback: same HDR1 bytes, but never allocate the stored full float raster. */
    internal fun readRows(file:File,w:Int,h:Int,cancel:Cancel):Pair<Planes,FileHdrPixels>{
        HdrPixels.dimensions(w,h);check(file.length()==12L+15L*w*h){"HDR成片缓存缺失或长度错误，请重新生成"}
        val sdr=DataInputStream(BufferedInputStream(file.inputStream(),65536)).use{input->
            cancel.check();check(input.readInt()==0x48445231 && input.readInt()==w && input.readInt()==h){"HDR成片缓存不匹配"}
            Planes(w,h).also{p->for(c in 0..2){cancel.check();input.readFully(p.plane(c))}}
        }
        cancel.check();return sdr to FileHdrPixels(file,w,h,12L+3L*w*h)
    }
    fun read(file:File,w:Int,h:Int,cancel:Cancel):Pair<Planes,Frame>{
        require(w>0 && h>0 && w.toLong()*h*3<=Int.MAX_VALUE)
        check(file.length()==12L+15L*w*h){"HDR成片缓存缺失或长度错误，请重新生成"}
        return DataInputStream(BufferedInputStream(file.inputStream(),65536)).use{input->
            check(input.readInt()==0x48445231 && input.readInt()==w && input.readInt()==h){"HDR成片缓存不匹配"}
            val sdr=Planes(w,h);for(c in 0..2){cancel.check();input.readFully(sdr.plane(c))}
            val values=FloatArray(w*h*3);FloatBlocks.read(input,values,cancel)
            cancel.check();sdr to HdrMath.checked(Frame(w,h,3,values))
        }
    }
}
