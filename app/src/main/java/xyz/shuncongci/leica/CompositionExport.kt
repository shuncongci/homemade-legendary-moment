package xyz.shuncongci.leica

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.min
import kotlin.math.max

/** Streams actual upright source pixels (RGBA, no resize). Reader receives bounded source strips. */
object CompositionExport {
    fun write(out:OutputStream,sourceW:Int,sourceH:Int,crop:CompositionGeometry.Crop,cancel:Cancel,
              read:(NativeImageMath.Box)->IntArray) {
        val b=CompositionGeometry.pixels(crop,sourceW,sourceH)
        cancel.check()
        Png(out,b.w,b.h).use { png->
            val strip=max(1,min(64,262144/b.w))
            var y=b.top
            while(y<b.bottom){cancel.check();val bottom=min(y+strip,b.bottom);val box=NativeImageMath.Box(b.left,y,b.right,bottom)
                val pixels=read(box);require(pixels.size==box.w*box.h){"原像素读取尺寸不一致"}
                for(row in 0 until box.h){cancel.check();png.row(pixels,row*box.w)};y=bottom
            }
            cancel.check();png.finish()
        }
    }
    /** Area downsampling after the crop, in bounded source strips. Alpha is filtered premultiplied. */
    fun writeSized(out:OutputStream,sourceW:Int,sourceH:Int,crop:CompositionGeometry.Crop,width:Int,height:Int,cancel:Cancel,
                   read:(NativeImageMath.Box)->IntArray) {
        val b=CompositionGeometry.pixels(crop,sourceW,sourceH)
        require(width in 1..b.w && height in 1..b.h){"不能放大裁剪后的像素"}
        if(width==b.w && height==b.h){write(out,sourceW,sourceH,crop,cancel,read);return}
        cancel.check()
        Png(out,width,height).use{png->
            val strip=max(1,min(64,262144/b.w));var stripY=-1;var stripBottom=-1;var pixels=IntArray(0)
            var cachedY=-1;val horizontal=DoubleArray(width*4);val sum=DoubleArray(width*4);val row=IntArray(width)
            val sx=b.w.toDouble()/width;val sy=b.h.toDouble()/height
            fun sourceRow(y:Int){
                if(cachedY==y)return
                cancel.check()
                if(y !in stripY until stripBottom){stripY=y;stripBottom=min(b.h,y+strip)
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
                };png.row(row,0)
            };cancel.check();png.finish()
        }
    }
    /** On cancellation/error close only releases resources; it never publishes a partial PNG. */
    private class Png(output:OutputStream,val w:Int,val h:Int):AutoCloseable {
        private val out=DataOutputStream(output.buffered());private val zip=Deflater(3);private val compressed=ByteArray(65536)
        private val line=ByteArray(w*4+1);private val shifts=intArrayOf(16,8,0,24);private var rows=0
        init {
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            val bytes=java.io.ByteArrayOutputStream();DataOutputStream(bytes).use{it.writeInt(w);it.writeInt(h);it.writeByte(8);it.writeByte(6);it.writeByte(0);it.writeByte(0);it.writeByte(0)}
            chunk("IHDR",bytes.toByteArray());chunk("sRGB",byteArrayOf(0))
        }
        private fun chunk(type:String,b:ByteArray,n:Int=b.size){val tag=type.toByteArray(Charsets.US_ASCII);val crc=CRC32();crc.update(tag);crc.update(b,0,n);out.writeInt(n);out.write(tag);out.write(b,0,n);out.writeInt(crc.value.toInt())}
        fun row(pixels:IntArray,offset:Int){check(rows<h);line[0]=1;var previous=0
            for(x in 0 until w){val c=pixels[offset+x];for(k in 0..3){val shift=shifts[k];line[1+x*4+k]=(((c ushr shift) and 255)-((previous ushr shift) and 255)).toByte()};previous=c}
            zip.setInput(line);while(!zip.needsInput()){val n=zip.deflate(compressed);if(n==0)break;chunk("IDAT",compressed,n)};rows++
        }
        fun finish(){check(rows==h);zip.finish();while(!zip.finished()){val n=zip.deflate(compressed);if(n>0)chunk("IDAT",compressed,n)};chunk("IEND",byteArrayOf());out.flush()}
        override fun close(){zip.end();out.close()}
    }
}
