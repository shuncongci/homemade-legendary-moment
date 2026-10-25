package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Immutable, repeatable linear RGB Float32 rows. Scratch belongs to the caller, never an image-sized array.
 * Readers are worker-confined; a borrowed read is valid after it returns until the caller reuses its buffer. */
interface HdrPixels {
    val w:Int
    val h:Int
    fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel)
    companion object {
        fun of(frame:Frame):HdrPixels {
            require(frame.channels==3)
            return object:HdrPixels {
                override val w=frame.w;override val h=frame.h
                override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){
                    bounds(this,channel,y,rows,values);cancel.check()
                    System.arraycopy(frame.data,channel*frame.size+y*w,values,0,rows*w)
                }
            }
        }
        internal fun bounds(p:HdrPixels,c:Int,y:Int,rows:Int,values:FloatArray){
            require(c in 0..2 && rows>0 && y>=0 && y.toLong()+rows<=p.h && rows.toLong()*p.w<=values.size)
        }
        internal fun valid(v:Float){require(v.isFinite() && v>=0f && v<=65504f){"HDR线性像素无效或超出安全范围，未截断/降SDR"}}
        internal fun dimensions(w:Int,h:Int){require(w>0 && h>0 && w.toLong()*h*3<=Int.MAX_VALUE){"HDR工作尺寸无效或像素索引溢出"}}
    }
}

/** Positional, bounded 64 KiB I/O, not a full-size mapping or a full-size FloatArray. */
internal class FileHdrPixels(val file:File,override val w:Int,override val h:Int,internal val offset:Long):HdrPixels,Closeable {
    private val channel:FileChannel
    private val bytes=ByteBuffer.allocate(65536).order(ByteOrder.BIG_ENDIAN)
    init {
        HdrPixels.dimensions(w,h)
        require(offset>=0 && file.length()==offset+12L*w*h){"HDR浮点缓存长度不正确"}
        channel=RandomAccessFile(file,"r").channel
    }
    override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){
        HdrPixels.bounds(this,channel,y,rows,values);check(this.channel.isOpen){"HDR浮点缓存已关闭"}
        var at=0;val count=rows*w;var position=offset+4L*(channel.toLong()*w*h+y.toLong()*w)
        while(at<count){
            cancel.check();val n=min(bytes.capacity()/4,count-at);bytes.clear();bytes.limit(n*4)
            while(bytes.hasRemaining()){
                val got=this.channel.read(bytes,position);if(got<0)throw EOFException("HDR浮点缓存被截断")
                check(got>0){"HDR浮点缓存读取未前进"};position+=got
            }
            bytes.flip();bytes.asFloatBuffer().get(values,at,n);at+=n
        };cancel.check()
    }
    override fun close(){channel.close()}
}

/** Exactly the legacy CheckpointPixels frame header and planar IEEE754 bits. Only completed files escape.
 * LatestCheckpoint still owns hash verification, fd.sync, atomic manifest commit and crash recovery. */
internal object HdrFloatFile {
    private const val MAGIC=0x4c435031
    fun open(file:File):FileHdrPixels {
        val shape=DataInputStream(file.inputStream().buffered()).use{input->
            require(input.readInt()==MAGIC){"HDR浮点缓存标识错误"};val w=input.readInt();val h=input.readInt()
            require(input.readInt()==3){"HDR浮点缓存通道错误"};w to h
        }
        return FileHdrPixels(file,shape.first,shape.second,16)
    }
    fun write(file:File,w:Int,h:Int,cancel:Cancel,produce:((Int,FloatArray)->Unit)->Unit){
        HdrPixels.dimensions(w,h);require(w<=Int.MAX_VALUE/12)
        require(!file.exists() || file.length()==0L){"不可覆盖已有HDR浮点缓存"}
        requireSpace(file,16+12L*w*h+16*HdrMemory.MIB)
        try {
            RandomAccessFile(file,"rw").use{out->
                cancel.check();out.setLength(16+12L*w*h)
                out.writeInt(MAGIC);out.writeInt(w);out.writeInt(h);out.writeInt(3)
                val written=BooleanArray(h);val bytes=ByteBuffer.allocate(w*4).order(ByteOrder.BIG_ENDIAN);val lock=Any()
                produce{y,values->synchronized(lock){
                    cancel.check();require(y in 0 until h && !written[y] && values.size==w*3){"HDR浮点缓存行重复或不完整"}
                    for(c in 0..2){
                        for(i in c*w until (c+1)*w)HdrPixels.valid(values[i])
                        bytes.clear();bytes.asFloatBuffer().put(values,c*w,w)
                        var position=16+4L*(c.toLong()*w*h+y.toLong()*w)
                        while(bytes.hasRemaining()){
                            val n=out.channel.write(bytes,position);check(n>0){"HDR浮点缓存写入未前进"};position+=n
                        }
                    };written[y]=true
                }}
                cancel.check();check(written.all{it}){"HDR浮点缓存缺少行"};out.fd.sync()
            }
        }catch(t:Throwable){file.delete();throw t}
    }
    fun write(file:File,pixels:HdrPixels,cancel:Cancel){
        write(file,pixels.w,pixels.h,cancel){emit->
            val row=FloatArray(pixels.w*3);val plane=FloatArray(pixels.w)
            for(y in 0 until pixels.h){
                for(c in 0..2){pixels.read(c,y,1,plane,cancel);plane.copyInto(row,c*pixels.w)}
                emit(y,row)
            }
        }
    }
    /** Storage exhaustion is an I/O failure, never an unsupported-HDR signal eligible for SDR fallback. */
    fun requireSpace(file:File,bytes:Long){
        if(checkNotNull(file.parentFile).usableSpace<bytes)throw IOException("HDR缓存空间不足；未缩图或转SDR")
    }
    /** Bounded byte copy for a validated legacy checkpoint; not a second in-memory float representation. */
    fun copy(from:File,to:File,cancel:Cancel){
        require(from.canonicalFile!=to.canonicalFile && (!to.exists() || to.length()==0L)){"不可覆盖已有HDR浮点缓存"}
        requireSpace(to,from.length()+16*HdrMemory.MIB)
        try{from.inputStream().use{input->FileOutputStream(to).use{out->
            val bytes=ByteArray(65536);var count=0L
            while(true){cancel.check();val n=input.read(bytes);if(n<0)break;out.write(bytes,0,n);count+=n}
            check(count==from.length()){"HDR缓存复制长度改变"};cancel.check();out.fd.sync()
        }}}catch(t:Throwable){to.delete();throw t}
    }
}

/** Same rounded crop and yy/xx Double summation order as HdrMath.geometry, but a row view, not 12N storage. */
internal object HdrPixelGeometry {
    fun crop(input:HdrPixels,crop:CompositionGeometry.Crop,w:Int,h:Int):HdrPixels {
        val box=CompositionGeometry.pixels(crop,input.w,input.h);HdrExportPolicy.checkSize(box.w,box.h,w,h)
        if(box.left==0 && box.top==0 && box.w==input.w && box.h==input.h && w==input.w && h==input.h)return input
        return object:HdrPixels {
            override val w=w;override val h=h
            private val source=FloatArray(input.w);private val sums=DoubleArray(w)
            private val sx=box.w.toDouble()/w;private val sy=box.h.toDouble()/h
            override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){
                HdrPixels.bounds(this,channel,y,rows,values)
                for(row in 0 until rows){cancel.check();sums.fill(0.0);val yyOut=y+row;val top=yyOut*sy;val bottom=(yyOut+1)*sy
                    for(yy in floor(top).toInt() until min(box.h,ceil(bottom).toInt())){
                        input.read(channel,box.top+yy,1,source,cancel)
                        for(x in 0 until w){val left=x*sx;val right=(x+1)*sx
                            for(xx in floor(left).toInt() until min(box.w,ceil(right).toInt())){
                                val v=source[box.left+xx];HdrPixels.valid(v)
                                val weight=(min(bottom,yy+1.0)-max(top,yy.toDouble()))*(min(right,xx+1.0)-max(left,xx.toDouble()))
                                sums[x]+=v*weight
                            }
                        }
                    }
                    for(x in 0 until w){val v=(sums[x]/(sx*sy)).toFloat();HdrPixels.valid(v);values[row*w+x]=v}
                }
            }
        }
    }
}
