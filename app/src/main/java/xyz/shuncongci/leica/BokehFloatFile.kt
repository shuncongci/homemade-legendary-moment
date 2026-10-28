package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

internal data class BokehBox(val left:Int,val top:Int,val w:Int,val h:Int){
    init{require(left>=0 && top>=0 && w>0 && h>0)}
    val right get()=left+w
    val bottom get()=top+h
    fun expand(pad:Int,width:Int,height:Int,align:Int=1):BokehBox{
        require(pad>=0 && align>0 && right<=width && bottom<=height)
        val l=max(0,left-pad)/align*align;val t=max(0,top-pad)/align*align
        val r=min(width,((min(width.toLong(),right.toLong()+pad)+align-1)/align*align).toInt())
        val b=min(height,((min(height.toLong(),bottom.toLong()+pad)+align-1)/align*align).toInt())
        return BokehBox(l,t,r-l,b-t)
    }
    companion object {
        fun four(w:Int,h:Int):List<BokehBox>{
            require(w>0 && h>0);val xs=if(w==1)intArrayOf(0,1)else intArrayOf(0,w/2,w);val ys=if(h==1)intArrayOf(0,1)else intArrayOf(0,h/2,h)
            return (0 until ys.lastIndex).flatMap{y->(0 until xs.lastIndex).map{x->BokehBox(xs[x],ys[y],xs[x+1]-xs[x],ys[y+1]-ys[y])}}
        }
    }
}
internal data class BokehRegion(val fullW:Int,val fullH:Int,val box:BokehBox){
    init{require(fullW>0 && fullH>0 && box.right<=fullW && box.bottom<=fullH)}
}

/** Planar IEEE754 Float32 region I/O with the EXISTING LCP1 checkpoint header.
 * 64KiB heap I/O only; no image-sized direct buffer or mapping. Incomplete stores
 * are caller-owned scratch and are never put into the durable result manifest.
 */
internal class BokehFloatFile private constructor(val file:File,val w:Int,val h:Int,val channels:Int,private val out:RandomAccessFile):Closeable {
    private val bytes=ByteBuffer.allocate(65536).order(ByteOrder.BIG_ENDIAN)
    val whole get()=BokehBox(0,0,w,h)
    private fun bounds(c:Int,y:Int,x:Int,count:Int,a:FloatArray,offset:Int){
        require(c in 0 until channels && y in 0 until h && x>=0 && count>0 && x.toLong()+count<=w && offset>=0 && offset.toLong()+count<=a.size)
    }
    fun row(c:Int,y:Int,x:Int,count:Int,a:FloatArray,cancel:Cancel,offset:Int=0){
        bounds(c,y,x,count,a,offset);var at=0;var pos=16+4L*(c.toLong()*w*h+y.toLong()*w+x)
        while(at<count){cancel.check();val n=min(bytes.capacity()/4,count-at);bytes.clear();bytes.limit(4*n)
            while(bytes.hasRemaining()){val got=out.channel.read(bytes,pos);if(got<0)throw EOFException("分块浮点缓存被截断");check(got>0);pos+=got}
            bytes.flip();bytes.asFloatBuffer().get(a,offset+at,n);at+=n
        }
    }
    fun putRow(c:Int,y:Int,x:Int,count:Int,a:FloatArray,cancel:Cancel,offset:Int=0){
        bounds(c,y,x,count,a,offset);var at=0;var pos=16+4L*(c.toLong()*w*h+y.toLong()*w+x)
        while(at<count){cancel.check();val n=min(bytes.capacity()/4,count-at)
            for(i in offset+at until offset+at+n)require(a[i].isFinite()){"分块浮点数值无效"}
            bytes.clear();bytes.limit(4*n);bytes.asFloatBuffer().put(a,offset+at,n)
            while(bytes.hasRemaining()){val got=out.channel.write(bytes,pos);check(got>0);pos+=got};at+=n
        }
    }
    fun read(box:BokehBox,cancel:Cancel):Frame{
        require(box.right<=w && box.bottom<=h)
        val a=FloatArray(box.w*box.h*channels)
        for(c in 0 until channels)for(y in 0 until box.h)row(c,box.top+y,box.left,box.w,a,cancel,c*box.w*box.h+y*box.w)
        return Frame(box.w,box.h,channels,a)
    }
    fun put(frame:Frame,box:BokehBox,cancel:Cancel){
        require(frame.w==box.w && frame.h==box.h && frame.channels==channels && box.right<=w && box.bottom<=h)
        for(c in 0 until channels)for(y in 0 until box.h)putRow(c,box.top+y,box.left,box.w,frame.data,cancel,c*frame.size+y*box.w)
    }
    /** Write only a core from a larger halo frame, without allocating a core copy. */
    fun putCore(frame:Frame,input:BokehBox,core:BokehBox,output:BokehBox,cancel:Cancel){
        require(frame.w==input.w && frame.h==input.h && frame.channels==channels && output.w==core.w && output.h==core.h)
        require(core.left>=input.left && core.top>=input.top && core.right<=input.right && core.bottom<=input.bottom)
        for(c in 0 until channels)for(y in 0 until core.h)putRow(c,output.top+y,output.left,core.w,frame.data,cancel,c*frame.size+(core.top-input.top+y)*frame.w+core.left-input.left)
    }
    /** Contiguous receiver rows, still 64KiB heap I/O and the exact LCP1 Float32 layout.
     * Explicit multi-row API leaves single-row bounds and read-only open() unchanged. */
    fun rows(c:Int,y:Int,rows:Int,a:FloatArray,cancel:Cancel,offset:Int=0){
        require(c in 0 until channels && y>=0 && rows>0 && y.toLong()+rows<=h && offset>=0 && offset.toLong()+w.toLong()*rows<=a.size)
        val count=w*rows;var at=0;var pos=16+4L*(c.toLong()*w*h+y.toLong()*w)
        while(at<count){cancel.check();val n=min(bytes.capacity()/4,count-at);bytes.clear();bytes.limit(4*n)
            while(bytes.hasRemaining()){val got=out.channel.read(bytes,pos);if(got<0)throw EOFException("浮点行缓存被截断");check(got>0);pos+=got}
            bytes.flip();bytes.asFloatBuffer().get(a,offset+at,n);at+=n
        }
    }
    fun putRows(c:Int,y:Int,rows:Int,a:FloatArray,cancel:Cancel,offset:Int=0){
        require(c in 0 until channels && y>=0 && rows>0 && y.toLong()+rows<=h && offset>=0 && offset.toLong()+w.toLong()*rows<=a.size)
        val count=w*rows;var at=0;var pos=16+4L*(c.toLong()*w*h+y.toLong()*w)
        while(at<count){cancel.check();val n=min(bytes.capacity()/4,count-at)
            for(i in offset+at until offset+at+n)require(a[i].isFinite()){"浮点行数值无效"}
            bytes.clear();bytes.limit(4*n);bytes.asFloatBuffer().put(a,offset+at,n)
            while(bytes.hasRemaining()){val got=out.channel.write(bytes,pos);check(got>0);pos+=got};at+=n
        }
    }
    fun range(cancel:Cancel):Pair<Float,Float>{
        var lo=Float.POSITIVE_INFINITY;var hi=Float.NEGATIVE_INFINITY;val row=FloatArray(w)
        for(c in 0 until channels)for(y in 0 until h){row(c,y,0,w,row,cancel);for(v in row){require(v.isFinite());lo=min(lo,v);hi=max(hi,v)}}
        return lo to hi
    }
    fun sync(){out.fd.sync()}
    override fun close(){out.close()}
    companion object {
        fun create(file:File,w:Int,h:Int,channels:Int):BokehFloatFile{
            require(w>0 && h>0 && channels in 1..4 && w.toLong()*h*channels<=Int.MAX_VALUE)
            require(!file.exists() || file.length()==0L){"不可覆盖已有分块缓存"}
            HdrFloatFile.requireSpace(file,16+4L*w*h*channels+16*HdrMemory.MIB)
            val f=RandomAccessFile(file,"rw")
            try{f.setLength(16+4L*w*h*channels);f.writeInt(0x4c435031);f.writeInt(w);f.writeInt(h);f.writeInt(channels);return BokehFloatFile(file,w,h,channels,f)}catch(t:Throwable){f.close();throw t}
        }
        fun open(file:File):BokehFloatFile{
            val f=RandomAccessFile(file,"r")
            try{require(f.readInt()==0x4c435031);val w=f.readInt();val h=f.readInt();val c=f.readInt();require(w>0 && h>0 && c in 1..4 && file.length()==16+4L*w*h*c);return BokehFloatFile(file,w,h,c,f)}catch(t:Throwable){f.close();throw t}
        }
    }
}
