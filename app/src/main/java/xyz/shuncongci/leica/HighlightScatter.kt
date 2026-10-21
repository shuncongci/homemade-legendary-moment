package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Kernel
import kotlin.math.*

/** V4 depth/occlusion compositor with optional smooth field-dependent optical pupils.
 * scatter() retains the uniform reference; optical() is the production spatial PSF
 * with a 3x highlight radius (2x the 2.1.11 radius). Base ARNet CoC, focus and radiance gain are unchanged;
 * explicit subject protection is applied by LegendaryMath.
 *
 * Signed CoC grows far -> near. A source contributes continuously to its two adjacent
 * integer PSFs. Near -> far SATURATED coverage treats these as a partition of an opaque
 * scene, not independent translucent sheets. Farther wide footprints have reduced
 * visibility on a much sharper receiver (relative-depth approximation, not ray tracing).
 * Missing coverage is completed from the receiver's own depth layers; accumulated
 * foreign radiance is NEVER divided by final coverage.
 *
 * Constant-depth integer PSFs remain unchanged. Fractional constant-depth planes use
 * the exact adjacent-PSF interpolation. Unknown hidden radiance cannot be recovered.
 */
object HighlightScatter {
    private fun reflect(x0:Int,n:Int):Int {if(n==1)return 0;var x=x0;while(x<0 || x>=n)x=if(x<0)-x else 2*n-2-x;return x}
    internal fun layerWeight(coc:Float,level:Int)=max(0f,1f-abs(coc-level))
    internal fun visibility(receiver:Float,sourceLevel:Int):Float {
        // One CoC-bin support margin: the receiver's own two tent bins are not occluders.
        val behind=(receiver-sourceLevel-1f).coerceIn(0f,1f)
        val footprint=min(1f,abs(receiver)/max(abs(sourceLevel),1))
        return 1f-behind*(1f-footprint)
    }
    private class PsfRow(val dy:Int,val a:Int,val b:Int,val run:Float,val tx:IntArray,val tw:FloatArray,val lo:Int,val hi:Int)
    private fun psfRows(k:Kernel):Array<PsfRow> {
        val full=k.data.maxOrNull()!!;val rows=ArrayList<PsfRow>()
        for(ky in 0 until k.w){
            val at=ky*k.w;var a=-1;var b=-2
            for(kx in 0 until k.w)if(k.data[at+kx]==full){if(a<0)a=kx;b=kx}
            if(a>=0)for(kx in a..b)if(k.data[at+kx]!=full){a=-1;b=-2;break}
            val tx=ArrayList<Int>();val tw=ArrayList<Float>()
            for(kx in 0 until k.w){val v=k.data[at+kx];if(v!=0f && (a<0 || kx<a || kx>b)){tx.add(kx);tw.add(v)}}
            if(a<0 && tx.isEmpty())continue
            val lo=min(if(a<0)Int.MAX_VALUE else a,tx.minOrNull() ?: Int.MAX_VALUE)
            val hi=max(b,tx.maxOrNull() ?: -1)
            rows.add(PsfRow(ky-k.half,a,b,full,tx.toIntArray(),tw.toFloatArray(),lo,hi))
        }
        return rows.toTypedArray()
    }
    private class Plan(val rows:Array<PsfRow>,val node:OpticalPupil.Node?)
    fun scatter(high:Frame,coc:Frame,aperture:Float,cancel:Cancel):Frame = scatterBounded(high,coc,aperture,cancel,54f)
    data class LightLayers(val color:Frame,val foreground:Frame)
    fun opticalLayers(high:Frame,coc:Frame,aperture:Float,cancel:Cancel):LightLayers {
        val foreground=Frame(high.w,high.h,3,FloatArray(high.data.size))
        val color=opticalAtScale(high,coc,aperture,cancel,RADIUS_SCALE,foreground)
        return LightLayers(color,foreground)
    }
    const val RADIUS_SCALE=3f
    fun optical(high:Frame,coc:Frame,aperture:Float,cancel:Cancel):Frame = opticalAtScale(high,coc,aperture,cancel,RADIUS_SCALE)
    /** Internal 1x reference for before/after audits. Enlarge highlights only, never
     * mutate caller-owned base CoC. Validate the original bound before enlargement. */
    internal fun opticalAtScale(high:Frame,coc:Frame,aperture:Float,cancel:Cancel,radiusScale:Float,foreground:Frame?=null,pruneDark:Boolean=true):Frame {
        cancel.check();FullFrameOptics.checkAperture(aperture)
        require(radiusScale.isFinite() && radiusScale in 1f..RADIUS_SCALE)
        require(high.channels==3 && coc.channels==1 && high.w==coc.w && high.h==coc.h)
        val limit=max(54f,FullFrameOptics.maxRadius(high.w,high.h)+.01f)
        require(coc.data.all{it.isFinite() && abs(it)<=limit})
        val scaled=if(radiusScale==1f)coc else Frame(coc.w,coc.h,1,FloatArray(coc.size){i->
            if(i and 65535==0)cancel.check()
            coc.data[i]*radiusScale
        })
        return scatterBounded(high,scaled,aperture,cancel,limit*radiusScale,true,foreground,pruneDark)
    }
    private fun scatterBounded(high:Frame,coc:Frame,aperture:Float,cancel:Cancel,maxRadius:Float,field:Boolean=false,foreground:Frame?=null,pruneDark:Boolean=true):Frame {
        cancel.check();LegendaryMath.gain(aperture)
        require(high.channels==3 && coc.channels==1 && high.w==coc.w && high.h==coc.h)
        require(high.data.all{it.isFinite() && it>=0f} && coc.data.all{it.isFinite() && abs(it)<=maxRadius})
        val w=high.w;val h=high.h;val n=high.size
        val color=Frame(w,h,3,FloatArray(high.data.size))
        if(high.data.all{it==0f})return color
        val minL=floor(coc.data.minOrNull()!!).toInt();val maxL=ceil(coc.data.maxOrNull()!!).toInt()
        val present=BooleanArray((maxL-minL+1)*h)
        parallelFor(h){y->
            cancel.check()
            for(i in y*w until (y+1)*w){val c=coc.data[i];present[(floor(c).toInt()-minL)*h+y]=true;present[(ceil(c).toInt()-minL)*h+y]=true}
        }
        val own=FloatArray(high.data.size);val coverage=FloatArray(n)
        fun halfOf(r:Int)=if(r==0)0 else r+1
        val maxHalf=halfOf(max(abs(minL),abs(maxL)));val stride=w+2*maxHalf+1
        // Exact conservative support of nonzero radiance, including reflected edges.
        // Keep all occluding depth layers inside it; never threshold dim lights or lower resolution.
        val outLo=IntArray(h);val outHi=IntArray(h){w-1}
        if(pruneDark){
            val litLo=IntArray(h){w};val litHi=IntArray(h){-1}
            for(y in 0 until h){cancel.check();for(x in 0 until w){val i=y*w+x
                if(high.data[i]>0f || high.data[n+i]>0f || high.data[2*n+i]>0f){litLo[y]=min(litLo[y],x);litHi[y]=x}
            }}
            for(y in 0 until h){cancel.check();var lo=w;var hi=-1
                for(sy in max(0,y-maxHalf)..min(h-1,y+maxHalf)){lo=min(lo,litLo[sy]);hi=max(hi,litHi[sy])}
                outLo[y]=if(hi<0)w else max(0,lo-maxHalf);outHi[y]=if(hi<0)-1 else min(w-1,hi+maxHalf)
            }
        }
        // Double row prefixes avoid cancelling tiny subpixel weights after long bright rows.
        val maskP=DoubleArray(stride*h);val rP=DoubleArray(stride*h);val gP=DoubleArray(stride*h);val bP=DoubleArray(stride*h)
        val pmin=IntArray(h);val pmax=IntArray(h)
        val plans=HashMap<Int,Array<PsfRow>>()
        // Smooth partition-of-unity interpolation, not disjoint blocks. Only one radius of
        // spatial kernels is retained; keep the original V4 occlusion/coverage compositor.
        val nodes=if(field)OpticalPupil.nodes(w,h).filter{node->!pruneDark || (0 until h).any{y->node.wy[y]>0f && outLo[y]<=node.x1 && outHi[y]>=node.x0}} else emptyList()
        val acc=ThreadLocal.withInitial{DoubleArray(4*w)}
        for(level in maxL downTo minL){
            cancel.check()
            // No source pixels at this depth: both radiance and occlusion mass
            // are exactly zero. Avoid constructing 25 unused field PSFs.
            if(pruneDark && !(0 until h).any{present[(level-minL)*h+it]})continue
            val half=halfOf(abs(level))
            val levelPlans=if(field && level!=0)nodes.map{node->Plan(psfRows(OpticalPupil.kernel(abs(level),aperture,OpticalPupil.shape(node.x,node.y,w,h),cancel)),node)}
                else listOf(Plan(plans.getOrPut(abs(level)){psfRows(LegendaryMath.kernel(abs(level),aperture))},null))
            val padded=w+2*half;val colOf=IntArray(padded){reflect(it-half,w)};val li=(level-minL)*h
            parallelFor(h){sy->
                if(sy and 15==0)cancel.check()
                if(!present[li+sy]){pmin[sy]=-1;return@parallelFor}
                val base=sy*stride;val src=sy*w;var lo=-1;var hi=-1
                var m=0.0;var r=0.0;var g=0.0;var b=0.0
                maskP[base]=0.0;rP[base]=0.0;gP[base]=0.0;bP[base]=0.0
                for(p in 0 until padded){
                    val i=src+colOf[p];val weight=layerWeight(coc.data[i],level).toDouble()
                    if(weight>0){m+=weight;r+=high.data[i]*weight;g+=high.data[n+i]*weight;b+=high.data[2*n+i]*weight;if(lo<0)lo=p;hi=p}
                    maskP[base+p+1]=m;rP[base+p+1]=r;gP[base+p+1]=g;bP[base+p+1]=b
                }
                pmin[sy]=lo;pmax[sy]=hi
            }
            parallelFor(h){y->
                if(y and 7==0)cancel.check()
                if(outLo[y]>outHi[y])return@parallelFor
                val a=checkNotNull(acc.get());var touched=false
                for(plan in levelPlans){
                    val node=plan.node;val wy=node?.wy?.get(y)?:1f;if(wy<=0f)continue
                    for(row in plan.rows){
                    val sy=reflect(y+row.dy,h);if(!present[li+sy] || pmin[sy]<0)continue
                    val x0=max(outLo[y],max(node?.x0?:0,max(0,pmin[sy]-row.hi)));val x1=min(outHi[y],min(node?.x1?:(w-1),min(w-1,pmax[sy]-row.lo)));if(x0>x1)continue
                    if(!touched){java.util.Arrays.fill(a,0.0);touched=true}
                    val base=sy*stride;val src=sy*w
                    if(row.a>=0)for(x in x0..x1){
                        val p0=base+x+row.a;val p1=base+x+row.b+1;val mass=maskP[p1]-maskP[p0]
                        if(mass<=0)continue
                        val kw=row.run*wy*(node?.wx?.get(x)?:1f)
                        a[x]+=kw*mass;a[w+x]+=kw*(rP[p1]-rP[p0]);a[2*w+x]+=kw*(gP[p1]-gP[p0]);a[3*w+x]+=kw*(bP[p1]-bP[p0])
                    }
                    for(t in row.tx.indices){
                        val kx=row.tx[t];val kw=row.tw[t]
                        for(x in x0..x1){
                            val i=src+colOf[x+kx];val weight=(kw*wy*(node?.wx?.get(x)?:1f)*layerWeight(coc.data[i],level)).toDouble();if(weight<=0)continue
                            a[x]+=weight;a[w+x]+=high.data[i]*weight;a[2*w+x]+=high.data[n+i]*weight;a[3*w+x]+=high.data[2*n+i]*weight
                        }
                    }
                }}
                if(!touched)return@parallelFor
                for(x in outLo[y]..outHi[y]){
                    val alpha=a[x].toFloat().coerceIn(0f,1f);if(alpha<=1e-8f)continue
                    val i=y*w+x;val local=layerWeight(coc.data[i],level)
                    val take=min(alpha*visibility(coc.data[i],level),max(0f,1f-coverage[i]))
                    for(c in 0..2){
                        val at=c*n+i;val radiance=max(0.0,a[(c+1)*w+x]).toFloat()
                        own[at]+=local*(radiance/alpha)
                        color.data[at]+=radiance*(take/alpha)
                        // Keep the same visibility/coverage. Only near-side light may veil a sharp subject.
                        if(level>0 && foreground!=null)foreground.data[at]+=radiance*(take/alpha)
                    }
                    coverage[i]=(coverage[i]+take).coerceIn(0f,1f)
                }
            }
        }
        parallelFor(h){y->
            cancel.check()
            for(i in y*w until (y+1)*w)for(c in 0..2){val at=c*n+i;color.data[at]+=(1f-coverage[i])*own[at]}
        }
        cancel.check();return color
    }
}
