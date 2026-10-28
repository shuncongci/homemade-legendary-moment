package xyz.shuncongci.leica

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

/** Upright, bounded-strip pixels shared by all encoders. No full-size IntArray or output resize beyond the requested plan. */
object CompositionExport {
    fun write(out:OutputStream,sourceW:Int,sourceH:Int,crop:CompositionGeometry.Crop,cancel:Cancel,
              read:(NativeImageMath.Box)->IntArray) {
        val b=CompositionGeometry.pixels(crop,sourceW,sourceH)
        writeSized(out,sourceW,sourceH,crop,b.w,b.h,cancel,read)
    }
    /** Classify actual output alpha, then write RGB when opaque, RGBA otherwise. Reader must be immutable/repeatable. */
    /** Borrows [out]: flushes a complete image but NEVER closes the caller's stream/descriptor. */
    fun writeSized(out:OutputStream,sourceW:Int,sourceH:Int,crop:CompositionGeometry.Crop,width:Int,height:Int,cancel:Cancel,
                   read:(NativeImageMath.Box)->IntArray) {
        var opaque=true
        rows(sourceW,sourceH,crop,width,height,cancel,read){_,p,off->
            if(opaque)for(x in 0 until width)if(p[off+x] ushr 24 !=255){opaque=false;break}
        }
        Png(out,width,height,opaque).use{png->
            rows(sourceW,sourceH,crop,width,height,cancel,read){_,p,off->png.row(p,off)}
            cancel.check();png.finish()
        }
    }
    /** emit borrows a row/strip only during the call. Exact-size path preserves even hidden RGB under alpha=0.
     * Area downsampling is unchanged: filter alpha premultiplied, round to even, never upscale. */
    fun rows(sourceW:Int,sourceH:Int,crop:CompositionGeometry.Crop,width:Int,height:Int,cancel:Cancel,
             read:(NativeImageMath.Box)->IntArray,emit:(Int,IntArray,Int)->Unit) {
        val b=CompositionGeometry.pixels(crop,sourceW,sourceH)
        require(width in 1..b.w && height in 1..b.h){"不能放大裁剪后的像素"}
        require(width<=Int.MAX_VALUE/4 && b.w<=Int.MAX_VALUE/4){"导出行过宽"}
        cancel.check()
        val strip=max(1,min(64,262144/b.w))
        if(width==b.w && height==b.h){
            var y=b.top
            while(y<b.bottom){
                cancel.check();val box=NativeImageMath.Box(b.left,y,b.right,min(y+strip,b.bottom));val p=read(box)
                require(p.size==box.w*box.h){"原像素读取尺寸不一致"}
                for(row in 0 until box.h){cancel.check();emit(y-b.top+row,p,row*box.w)}
                y=box.bottom
            }
            cancel.check();return
        }
        var stripY=-1;var stripBottom=-1;var pixels=IntArray(0)
        var cachedY=-1;val horizontal=DoubleArray(width*4);val sum=DoubleArray(width*4);val row=IntArray(width)
        val sx=b.w.toDouble()/width;val sy=b.h.toDouble()/height
        fun sourceRow(y:Int){
            if(cachedY==y)return
            cancel.check()
            if(y !in stripY until stripBottom){
                stripY=y;stripBottom=min(b.h,y+strip)
                pixels=read(NativeImageMath.Box(b.left,b.top+stripY,b.right,b.top+stripBottom))
                require(pixels.size==b.w*(stripBottom-stripY)){"原像素读取尺寸不一致"}
            }
            horizontal.fill(0.0)
            for(x in 0 until width){val left=x*sx;val right=(x+1)*sx
                for(i in kotlin.math.floor(left).toInt() until kotlin.math.ceil(right).toInt().coerceAtMost(b.w)){
                    val weight=(min(right,i+1.0)-max(left,i.toDouble()))/sx
                    val argb=pixels[(y-stripY)*b.w+i];val a=(argb ushr 24).toDouble();val at=x*4
                    horizontal[at]+=a*weight
                    horizontal[at+1]+=((argb ushr 16) and 255)*a*weight
                    horizontal[at+2]+=((argb ushr 8) and 255)*a*weight
                    horizontal[at+3]+=(argb and 255)*a*weight
                }
            };cachedY=y
        }
        for(y in 0 until height){cancel.check();sum.fill(0.0);val top=y*sy;val bottom=(y+1)*sy
            for(j in kotlin.math.floor(top).toInt() until kotlin.math.ceil(bottom).toInt().coerceAtMost(b.h)){
                sourceRow(j);val weight=(min(bottom,j+1.0)-max(top,j.toDouble()))/sy
                for(i in sum.indices)sum[i]+=horizontal[i]*weight
            }
            for(x in 0 until width){val at=x*4;val a=sum[at];var color=kotlin.math.round(a).toInt().coerceIn(0,255) shl 24
                if(a>1e-9)for(k in 1..3)color=color or(kotlin.math.round(sum[at+k]/a).toInt().coerceIn(0,255) shl ((3-k)*8))
                row[x]=color
            };emit(y,row,0)
        };cancel.check()
    }
    /** On cancellation/error close only releases resources; IEND is emitted only by finish(). */
    private class Png(output:OutputStream,val w:Int,val h:Int,val opaque:Boolean):AutoCloseable {
        private val out=DataOutputStream(output.buffered());private val zip=Deflater(6);private val compressed=ByteArray(65536)
        private val channels=if(opaque)3 else 4
        private val raw=ByteArray(w*channels);private val previous=ByteArray(raw.size)
        private var candidate=ByteArray(raw.size+1);private var best=ByteArray(candidate.size);private var count=0
        init {
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            val bytes=java.io.ByteArrayOutputStream();DataOutputStream(bytes).use{it.writeInt(w);it.writeInt(h);it.writeByte(8);it.writeByte(if(opaque)2 else 6);it.writeByte(0);it.writeByte(0);it.writeByte(0)}
            chunk("IHDR",bytes.toByteArray());chunk("sRGB",byteArrayOf(0))
        }
        private fun chunk(type:String,b:ByteArray,n:Int=b.size){val tag=type.toByteArray(Charsets.US_ASCII);val crc=CRC32();crc.update(tag);crc.update(b,0,n);out.writeInt(n);out.write(tag);out.write(b,0,n);out.writeInt(crc.value.toInt())}
        private fun paeth(a:Int,b:Int,c:Int):Int {val p=a+b-c;val pa=abs(p-a);val pb=abs(p-b);val pc=abs(p-c);return if(pa<=pb && pa<=pc)a else if(pb<=pc)b else c}
        fun row(pixels:IntArray,offset:Int){
            check(count<h)
            for(x in 0 until w){val c=pixels[offset+x];val at=x*channels
                check(!opaque || c ushr 24==255){"来源像素在导出期间发生变化"}
                raw[at]=(c ushr 16).toByte();raw[at+1]=(c ushr 8).toByte();raw[at+2]=c.toByte();if(!opaque)raw[at+3]=(c ushr 24).toByte()
            }
            var bestScore=Long.MAX_VALUE
            for(filter in 0..4){var score=0L;candidate[0]=filter.toByte()
                for(i in raw.indices){val value=raw[i].toInt() and 255;val a=if(i>=channels)raw[i-channels].toInt() and 255 else 0
                    val b=previous[i].toInt() and 255;val c=if(i>=channels)previous[i-channels].toInt() and 255 else 0
                    val predicted=when(filter){1->a;2->b;3->(a+b)/2;4->paeth(a,b,c);else->0}
                    val v=(value-predicted).toByte();candidate[i+1]=v;score+=abs(v.toInt())
                }
                if(score<bestScore){bestScore=score;val swap=best;best=candidate;candidate=swap}
            }
            zip.setInput(best);while(!zip.needsInput()){val n=zip.deflate(compressed);if(n==0)break;chunk("IDAT",compressed,n)}
            raw.copyInto(previous);count++
        }
        fun finish(){check(count==h);zip.finish();while(!zip.finished()){val n=zip.deflate(compressed);if(n>0)chunk("IDAT",compressed,n)};chunk("IEND",byteArrayOf());out.flush()}
        override fun close(){zip.end()} // finish() flushes success; caller owns sync/close/rollback.
    }
}
