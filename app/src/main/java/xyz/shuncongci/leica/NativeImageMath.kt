package xyz.shuncongci.leica

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.*

/** Native pixel geometry. EXIF mapping always refers to the USER image, not GAN dimensions. */
object NativeImageMath {
    data class Box(val left:Int,val top:Int,val right:Int,val bottom:Int) { val w get()=right-left;val h get()=bottom-top }
    fun size(w:Int,h:Int,orientation:Int)=if(orientation in 5..8) h to w else w to h
    fun encoded(x:Int,y:Int,w:Int,h:Int,o:Int):Pair<Int,Int> = when(o) {
        2 -> w-1-x to y;3 -> w-1-x to h-1-y;4 -> x to h-1-y
        5 -> y to x;6 -> y to h-1-x;7 -> w-1-y to h-1-x;8 -> w-1-y to x
        else -> x to y
    }
    fun encodedBox(b:Box,w:Int,h:Int,o:Int):Box {
        val p=listOf(encoded(b.left,b.top,w,h,o),encoded(b.right-1,b.top,w,h,o),encoded(b.left,b.bottom-1,w,h,o),encoded(b.right-1,b.bottom-1,w,h,o))
        return Box(p.minOf{it.first},p.minOf{it.second},p.maxOf{it.first}+1,p.maxOf{it.second}+1)
    }
    private fun cubic(x0:Float):Float { val x=abs(x0);val a=-.75f;return if(x<=1f)(a+2)*x*x*x-(a+3)*x*x+1 else if(x<2)a*x*x*x-5*a*x*x+8*a*x-4*a else 0f }
    /** Resize a region with GLOBAL half-pixel coordinates; no tile-local colour discontinuities. */
    fun region(f:LegendaryMath.Frame,w:Int,h:Int,b:Box,cubic:Boolean=false,cancel:Cancel?=null):LegendaryMath.Frame {
        require(b.left>=0 && b.top>=0 && b.right<=w && b.bottom<=h && b.w>0 && b.h>0)
        val out=LegendaryMath.Frame(b.w,b.h,f.channels,FloatArray(b.w*b.h*f.channels))
        for(c in 0 until f.channels)for(y in 0 until b.h) {
            if(y and 31==0)cancel?.check()
            val sy=(b.top+y+.5f)*f.h/h-.5f;val iy=floor(sy).toInt();val fy=sy-iy
            for(x in 0 until b.w) {
                val sx=(b.left+x+.5f)*f.w/w-.5f;val ix=floor(sx).toInt();val fx=sx-ix;var sum=0f
                if(cubic)for(dy in -1..2)for(dx in -1..2)sum+=f.data[c*f.size+(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*cubic(dy-fy)*cubic(dx-fx)
                else for(dy in 0..1)for(dx in 0..1)sum+=f.data[c*f.size+(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*(if(dy==0)1-fy else fy)*(if(dx==0)1-fx else fx)
                out.data[c*out.size+y*b.w+x]=sum
            }
        }
        return out
    }
    fun rgbBytes(f:LegendaryMath.Frame):Planes { require(f.channels==3);return Planes(f.w,f.h).also{p->for(c in 0..2)for(i in 0 until p.size)p.plane(c)[i]=(f.data[c*p.size+i].coerceIn(0f,1f)*255).toInt().toByte()} }
}

/** Stream a full-size RGB PNG, one scanline at a time; never allocate a full-size output bitmap. */
class NativePng(output:OutputStream,val width:Int,val height:Int):AutoCloseable {
    private val out=DataOutputStream(output.buffered())
    private val z=Deflater(3)
    private val buffer=ByteArray(64*1024)
    private var rows=0
    private var closed=false
    init {
        require(width>0 && height>0)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val bytes=java.io.ByteArrayOutputStream();DataOutputStream(bytes).use{it.writeInt(width);it.writeInt(height);it.writeByte(8);it.writeByte(2);it.writeByte(0);it.writeByte(0);it.writeByte(0)}
        chunk("IHDR",bytes.toByteArray())
    }
    private fun chunk(type:String,bytes:ByteArray,n:Int=bytes.size) {
        val tag=type.toByteArray(Charsets.US_ASCII);val crc=CRC32();crc.update(tag);crc.update(bytes,0,n)
        out.writeInt(n);out.write(tag);out.write(bytes,0,n);out.writeInt(crc.value.toInt())
    }
    private fun drain() { while(!z.needsInput() && !z.finished()){val n=z.deflate(buffer);if(n==0)break;chunk("IDAT",buffer,n)} }
    fun row(rgb:ByteArray,offset:Int=0) {
        check(!closed && rows<height);require(offset>=0 && offset+width*3<=rgb.size)
        // PNG Sub filter retains lossless native details and compresses better than unfiltered rows.
        val line=ByteArray(width*3+1);line[0]=1
        for(i in 0 until width*3)line[i+1]=((rgb[offset+i].toInt() and 255)-(if(i>=3)rgb[offset+i-3].toInt() and 255 else 0)).toByte()
        z.setInput(line);drain();rows++
    }
    override fun close() {
        if(closed)return;closed=true
        try { if(rows==height){z.finish();while(!z.finished()){val n=z.deflate(buffer);if(n>0)chunk("IDAT",buffer,n)};chunk("IEND",byteArrayOf());out.flush()} }
        finally {z.end();out.close()}
        check(rows==height){"PNG输出未完成：$rows/$height"}
    }
}
