package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Historical V1 oracle, TEST ONLY; old golden fixtures stay unchanged. */
object LegacyHighlightScatter {
    private fun reflect(x0: Int,n: Int): Int { if(n==1)return 0;var x=x0;while(x<0 || x>=n) x=if(x<0)-x else 2*n-2-x;return x }
    internal data class Kernel(val half: Int,val data: FloatArray) { val w get()=half*2+1 }
    internal fun kernel(radius: Int,aperture: Float): Kernel {
        if(radius==0)return Kernel(0,floatArrayOf(1f))
        val half=ceil(radius+.5).toInt();val w=half*2+1;val a=FloatArray(w*w)
        val round=when(aperture){.95f->1.0;1.8f->.85;2f->.8;2.8f->.65;4f->.45;else->.25}
        for(y in 0 until w) for(x in 0 until w) {
            var covered=0
            for(yy in 0 until 8) for(xx in 0 until 8) {
                val dx=x+(xx+.5)/8-.5-half;val dy=y+(yy+.5)/8-.5-half
                val theta=atan2(dy,dx);val period=2*PI/9;val sector=((theta+PI/9)%period+period)%period-PI/9
                val polygon=cos(PI/9)/cos(sector);val boundary=radius*(round+(1-round)*polygon)
                if(hypot(dx,dy)<=boundary)covered++
            }
            a[y*w+x]=covered/64f
        }
        val sum=a.sum();for(i in a.indices)a[i]/=sum;return Kernel(half,a)
    }
    /** Layered, full-resolution source highlights; same PSF and signed depth order as PC.
     * Equivalent to direct per-tap gather (see LegendaryMathTest reference), but each convex PSF row is
     * one constant-weight run read from row prefix sums plus a few antialiased edge taps, and only
     * kernel rows/columns that can reach pixels of the current level are visited. Rows run in parallel.
     */
    internal fun uniqueLevels(bins: IntArray): IntArray {
        // CoC levels are few (aperture gain <=54), independent of the photo pixel count.
        // Sort only distinct primitive levels; avoid boxing millions of individual pixels.
        var levels=IntArray(16);var count=0
        for(v in bins){
            var found=false;for(i in 0 until count)if(levels[i]==v){found=true;break}
            if(!found){if(count==levels.size)levels=levels.copyOf(levels.size*2);levels[count++]=v}
        }
        return levels.copyOf(count).apply{sort()}
    }
    /** One PSF row: [a,b] is the full-weight interior (a<0: none); tx/tw are the remaining nonzero taps. */
    private class PsfRow(val dy: Int,val a: Int,val b: Int,val run: Float,val tx: IntArray,val tw: FloatArray,val lo: Int,val hi: Int)
    private fun psfRows(k: Kernel): Array<PsfRow> {
        val full=k.data.maxOrNull()!!;val rows=ArrayList<PsfRow>()
        for(ky in 0 until k.w) {
            val at=ky*k.w;var a=-1;var b=-2
            for(kx in 0 until k.w) if(k.data[at+kx]==full){if(a<0)a=kx;b=kx}
            if(a>=0) for(kx in a..b) if(k.data[at+kx]!=full){a=-1;b=-2;break}
            val tx=ArrayList<Int>();val tw=ArrayList<Float>()
            for(kx in 0 until k.w){val v=k.data[at+kx];if(v!=0f && (a<0 || kx<a || kx>b)){tx.add(kx);tw.add(v)}}
            if(a<0 && tx.isEmpty())continue
            val lo=min(if(a<0)Int.MAX_VALUE else a,tx.minOrNull() ?: Int.MAX_VALUE)
            val hi=max(b,tx.maxOrNull() ?: -1)
            rows.add(PsfRow(ky-k.half,a,b,full,tx.toIntArray(),tw.toFloatArray(),lo,hi))
        }
        return rows.toTypedArray()
    }
    internal fun scatter(high: Frame,coc: Frame,aperture: Float,cancel: Cancel): Frame {
        val W=high.w;val H=high.h;val n=high.size
        val bins=IntArray(coc.size){Math.rint(coc.data[it].toDouble()).toInt()};val levels=uniqueLevels(bins)
        val color=Frame(W,H,3,FloatArray(high.data.size));val coverage=FloatArray(n)
        if(levels.isEmpty())return color
        // Which rows contain each level: one pass, independent of kernel size.
        val minL=levels.first();val span=levels.last()-minL+1;val present=BooleanArray(span*H)
        parallelFor(H) { y -> for(i in y*W until (y+1)*W) present[(bins[i]-minL)*H+y]=true }
        val halfOf={r: Int -> if(r==0)0 else ceil(r+.5).toInt()}
        val maxHalf=levels.maxOf{halfOf(abs(it))};val stride=W+2*maxHalf+1
        // Reflect-padded row prefix sums for the current level; mask counts are exact integers.
        val maskP=IntArray(stride*H);val rP=FloatArray(stride*H);val gP=FloatArray(stride*H);val bP=FloatArray(stride*H)
        val pmin=IntArray(H);val pmax=IntArray(H)
        val plans=HashMap<Int,Array<PsfRow>>()
        val acc=ThreadLocal.withInitial{FloatArray(4*W)}
        for(level in levels) {
            cancel.check()
            val half=halfOf(abs(level));val rows=plans.getOrPut(abs(level)){psfRows(kernel(abs(level),aperture))}
            val padded=W+2*half;val colOf=IntArray(padded){reflect(it-half,W)};val li=(level-minL)*H
            parallelFor(H) { sy ->
                if(!present[li+sy]){pmin[sy]=-1;return@parallelFor}
                val base=sy*stride;val src=sy*W;var lo=-1;var hi=-1
                var m=0;var r=0f;var g=0f;var b=0f;maskP[base]=0;rP[base]=0f;gP[base]=0f;bP[base]=0f
                for(p in 0 until padded) {
                    val i=src+colOf[p]
                    if(bins[i]==level){m++;r+=high.data[i];g+=high.data[n+i];b+=high.data[2*n+i];if(lo<0)lo=p;hi=p}
                    maskP[base+p+1]=m;rP[base+p+1]=r;gP[base+p+1]=g;bP[base+p+1]=b
                }
                pmin[sy]=lo;pmax[sy]=hi
            }
            parallelFor(H) { y ->
                if(y and 7==0)cancel.check()
                val a=acc.get();var touched=false
                for(row in rows) {
                    val sy=reflect(y+row.dy,H);if(!present[li+sy] || pmin[sy]<0)continue
                    val x0=max(0,pmin[sy]-row.hi);val x1=min(W-1,pmax[sy]-row.lo);if(x0>x1)continue
                    if(!touched){java.util.Arrays.fill(a,0f);touched=true}
                    val base=sy*stride;val src=sy*W
                    if(row.a>=0) for(x in x0..x1) {
                        val p0=base+x+row.a;val p1=base+x+row.b+1;val count=maskP[p1]-maskP[p0]
                        if(count==0)continue
                        a[x]+=row.run*count;a[W+x]+=row.run*(rP[p1]-rP[p0]);a[2*W+x]+=row.run*(gP[p1]-gP[p0]);a[3*W+x]+=row.run*(bP[p1]-bP[p0])
                    }
                    for(t in row.tx.indices) {
                        val kx=row.tx[t];val weight=row.tw[t]
                        for(x in x0..x1) {
                            val i=src+colOf[x+kx];if(bins[i]!=level)continue
                            a[x]+=weight;a[W+x]+=high.data[i]*weight;a[2*W+x]+=high.data[n+i]*weight;a[3*W+x]+=high.data[2*n+i]*weight
                        }
                    }
                }
                if(!touched)return@parallelFor
                for(x in 0 until W) {
                    val alpha=a[x];if(alpha==0f)continue
                    val i=y*W+x;val keep=1-alpha.coerceIn(0f,1f)
                    color.data[i]=a[W+x]+color.data[i]*keep;color.data[n+i]=a[2*W+x]+color.data[n+i]*keep;color.data[2*n+i]=a[3*W+x]+color.data[2*n+i]*keep
                    coverage[i]=alpha.coerceIn(0f,1f)+coverage[i]*keep
                }
            }
        }
        parallelFor(H) { y -> for(i in y*W until (y+1)*W) for(c in 0..2) color.data[c*n+i]=if(coverage[i]>1e-6f)color.data[c*n+i]/coverage[i] else 0f }
        return color
    }
}
