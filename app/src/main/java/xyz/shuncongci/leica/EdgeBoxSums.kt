package xyz.shuncongci.leica

import kotlin.math.*

/** Exact separable sliding sums. Each pixel sees the SAME Double add/subtract order as
 * the 67 row/column loops, including the Float32 horizontal round in f32. Only receivers
 * outside the mixed-alpha bounding columns are omitted. No sample/precision changes.
 * Inlined primitive callbacks remove per-sample Function1<Int,Double/Float> boxing on ART.
 * Column blocks retain cache locality; independent rows/columns share the existing pool. */
internal class EdgeBoxSums(val w:Int,val h:Int,val top:Int,val end:Int,val r:Int,val left:Int,val right:Int) {
    val span=right-left+1
    val y0=max(0,top-r)
    val y1=min(h,end+r)
    val rows=y1-y0
    val count=(end-top)*span
    init { require(w>0 && h>0 && top>=0 && end>top && end<=h && r>=0 && left>=0 && right in left until w) }
    inline fun f64(horizontal:DoubleArray,cancel:Cancel,crossinline value:(Int)->Double):DoubleArray {
        require(horizontal.size>=span*rows)
        parallelFor(rows){yy->
            cancel.check();var sum=0.0;var lo=0;var hi=-1
            // Start at x=0, not at left: preserve even non-integer accumulation order.
            for(x in 0..right){val a=max(0,x-r);val b=min(w-1,x+r)
                while(hi<b){hi++;sum+=value((yy+y0)*w+hi)}
                while(lo<a){sum-=value((yy+y0)*w+lo);lo++}
                if(x>=left)horizontal[yy*span+x-left]=sum
            }
        }
        val out=DoubleArray(count)
        parallelFor((span+63)/64){block->
            cancel.check();val x0=block*64;val x1=min(span,x0+64);val sums=DoubleArray(x1-x0);var lo=0;var hi=-1
            for(yy in 0 until rows){val a=max(0,yy-r);val b=min(rows-1,yy+r)
                while(hi<b){hi++;for(x in x0 until x1)sums[x-x0]+=horizontal[hi*span+x]}
                while(lo<a){for(x in x0 until x1)sums[x-x0]-=horizontal[lo*span+x];lo++}
                if(yy+y0 in top until end)for(x in x0 until x1)out[(yy+y0-top)*span+x]=sums[x-x0]
            }
        };return out
    }
    inline fun f32(horizontal:FloatArray,cancel:Cancel,crossinline value:(Int)->Float):FloatArray {
        require(horizontal.size>=span*rows)
        parallelFor(rows){yy->
            cancel.check();var sum=0.0;var lo=0;var hi=-1
            for(x in 0..right){val a=max(0,x-r);val b=min(w-1,x+r)
                while(hi<b){hi++;sum+=value((yy+y0)*w+hi)}
                while(lo<a){sum-=value((yy+y0)*w+lo);lo++}
                if(x>=left)horizontal[yy*span+x-left]=sum.toFloat()
            }
        }
        val out=FloatArray(count)
        parallelFor((span+63)/64){block->
            cancel.check();val x0=block*64;val x1=min(span,x0+64);val sums=DoubleArray(x1-x0);var lo=0;var hi=-1
            for(yy in 0 until rows){val a=max(0,yy-r);val b=min(rows-1,yy+r)
                while(hi<b){hi++;for(x in x0 until x1)sums[x-x0]+=horizontal[hi*span+x]}
                while(lo<a){for(x in x0 until x1)sums[x-x0]-=horizontal[lo*span+x];lo++}
                if(yy+y0 in top until end)for(x in x0 until x1)out[(yy+y0-top)*span+x]=sums[x-x0].toFloat()
            }
        };return out
    }
    companion object {
        fun mixed(alpha:FloatArray,w:Int,top:Int,end:Int,cancel:Cancel):IntRange? {
            var lo=w;var hi=-1
            for(y in top until end){cancel.check();for(x in 0 until w){val a=alpha[y*w+x];if(a>0f && a<1f){lo=min(lo,x);hi=max(hi,x)}}}
            return if(hi<lo)null else lo..hi
        }
    }
}
