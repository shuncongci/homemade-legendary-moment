package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Same global HighlightExtraction decisions, but no whole-frame peak/horizontal
 * arrays or N-int BFS queue. Only the scalar result, candidate bits, row ring and
 * a bounded paged FIFO are in memory. The FIFO is generated private cache, not input data.
 */
internal object BoundedHighlightExtraction {
    private fun smooth(v:Float):Float{val t=v.coerceIn(0f,1f);return t*t*(3-2*t)}
    private val linear=FloatArray(256){v->val x=v/255f;if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)}
    private class Queue(file:File):Closeable{
        val f=RandomAccessFile(file,"rw");val page=16384;val write=IntArray(page);val read=IntArray(page)
        val bytes=ByteBuffer.allocate(page*4).order(ByteOrder.BIG_ENDIAN)
        var head=0;var tail=0;var tag=-1
        fun reset(){head=0;tail=0;tag=-1}
        fun add(v:Int){write[tail%page]=v;tail++
            if(tail%page==0){bytes.clear();bytes.asIntBuffer().put(write);var pos=4L*(tail-page)
                while(bytes.hasRemaining()){val n=f.channel.write(bytes,pos);check(n>0);pos+=n}
            }
        }
        fun get(i:Int):Int{
            require(i in 0 until tail);val p=i/page
            if(p==tail/page)return write[i%page]
            if(tag!=p){bytes.clear();var pos=4L*p*page
                while(bytes.hasRemaining()){val n=f.channel.read(bytes,pos);if(n<0)throw EOFException();check(n>0);pos+=n}
                bytes.flip();bytes.asIntBuffer().get(read);tag=p
            };return read[i%page]
        }
        override fun close(){f.close()}
    }
    fun caps(p:Planes,cancel:Cancel,scratch:File):FloatArray{
        val w=p.width;val h=p.height;val n=p.size
        fun peak(i:Int)=linear[maxOf(p.p0[i].toInt()and 255,p.p1[i].toInt()and 255,p.p2[i].toInt()and 255)]
        val r=max(4,(min(w,h)*.015f).roundToInt());val capacity=min(h,2*r+1)
        val ring=Array(capacity){FloatArray(w)};val sums=DoubleArray(w);val out=FloatArray(n);val candidate=BooleanArray(n)
        fun horizontal(y:Int,dst:FloatArray){var sum=0.0;var left=0;var right=-1;val row=y*w
            for(x in 0 until w){val lo=max(0,x-r);val hi=min(w-1,x+r)
                while(right<hi){right++;sum+=peak(row+right)};while(left<lo){sum-=peak(row+left);left++};dst[x]=(sum/(right-left+1)).toFloat()
            }
        }
        // Add newly visible rows before removing the old row, exactly as the reference.
        // One extra transient row prevents overwriting an outgoing ring slot first.
        val extra=FloatArray(w);var top=0;var bottom=-1;var any=false
        for(y in 0 until h){cancel.check();val lo=max(0,y-r);val hi=min(h-1,y+r)
            while(bottom<hi){val sy=bottom+1;val slot=sy%capacity
                if(sy-top>=capacity){horizontal(sy,extra);for(x in 0 until w)sums[x]+=extra[x]
                    val old=ring[top%capacity];for(x in 0 until w)sums[x]-=old[x];top++;extra.copyInto(ring[slot])
                }else{horizontal(sy,ring[slot]);for(x in 0 until w)sums[x]+=ring[slot][x]};bottom=sy
            }
            while(top<lo){val old=ring[top%capacity];for(x in 0 until w)sums[x]-=old[x];top++}
            for(x in 0 until w){val i=y*w+x;val v=peak(i);val mean=(sums[x]/(bottom-top+1)).toFloat()
                val strength=smooth((v-.08f)/.12f)*smooth((v-mean-.025f)/.10f)
                if(strength>0f)any=true
                candidate[i]=strength>0f || v>.25f;out[i]=if(strength>0f).25f-.19f*strength else max(.25f,v)
            }
        }
        if(!any)return out
        val maxSpan=max(8,(min(w,h)*.08f).roundToInt())
        Queue(scratch).use{q->for(seed in 0 until n){
            if(seed%w==0)cancel.check();if(!candidate[seed])continue
            q.reset();q.add(seed);candidate[seed]=false
            var x0=seed%w;var x1=x0;var y0=seed/w;var y1=y0;var large=false;var topPeak=0f;var chroma=0f
            while(q.head<q.tail){if(q.head and 4095==0)cancel.check();val i=q.get(q.head++);val x=i%w;val y=i/w
                x0=min(x0,x);x1=max(x1,x);y0=min(y0,y);y1=max(y1,y)
                if(!large && (x1-x0+1>maxSpan || y1-y0+1>maxSpan)){
                    large=true;for(j in 0 until q.head){val k=q.get(j);out[k]=max(.25f,peak(k))}
                }
                if(large)out[i]=max(.25f,peak(i))else if(peak(i)>topPeak){
                    topPeak=peak(i);val hi=maxOf(p.p0[i].toInt()and 255,p.p1[i].toInt()and 255,p.p2[i].toInt()and 255)
                    val low=minOf(p.p0[i].toInt()and 255,p.p1[i].toInt()and 255,p.p2[i].toInt()and 255);chroma=(hi-low).toFloat()/max(1,hi)
                }
                for(yy in max(0,y-1)..min(h-1,y+1))for(xx in max(0,x-1)..min(w-1,x+1)){val j=yy*w+xx;if(candidate[j]){candidate[j]=false;q.add(j)}}
            }
            if(large)continue
            val sx=x1-x0+1;val sy=y1-y0+1;val pad=max(2,min(6,min(sx,sy)));var ringSum=0.0;var count=0
            for(y in max(0,y0-pad)..min(h-1,y1+pad)){cancel.check();for(x in max(0,x0-pad)..min(w-1,x1+pad)){
                if(x in x0..x1 && y in y0..y1)continue;ringSum+=peak(y*w+x);count++
            }}
            val surround=if(count>0)(ringSum/count).toFloat()else topPeak
            val neutral=smooth((topPeak-.60f)/.25f);val colored=smooth((chroma-.35f)/.35f)*smooth((topPeak-.13f)/.10f)
            val isolation=smooth((1f-surround/max(topPeak,1e-8f)-.55f)/.30f);val shape=smooth((min(sx,sy).toFloat()/max(sx,sy)-.34f)/.20f)
            val confidence=max(neutral,colored)*isolation*shape
            for(j in 0 until q.tail){val i=q.get(j);out[i]=if(confidence==0f)max(.25f,peak(i))else max(out[i],peak(i)-(peak(i)-out[i])*confidence)}
        }}
        cancel.check();return out
    }
}
