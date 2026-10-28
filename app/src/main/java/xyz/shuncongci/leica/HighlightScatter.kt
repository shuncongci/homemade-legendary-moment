package xyz.shuncongci.leica

import java.io.File
import java.io.Closeable
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
    internal fun reflect(x0:Int,n:Int):Int {if(n==1)return 0;var x=x0;while(x<0 || x>=n)x=if(x<0)-x else 2*n-2-x;return x}
    internal fun layerWeight(coc:Float,level:Int)=max(0f,1f-abs(coc-level))
    internal fun visibility(receiver:Float,sourceLevel:Int):Float {
        // One CoC-bin support margin: the receiver's own two tent bins are not occluders.
        val behind=(receiver-sourceLevel-1f).coerceIn(0f,1f)
        val footprint=min(1f,abs(receiver)/max(abs(sourceLevel),1))
        return 1f-behind*(1f-footprint)
    }
    internal class PsfRow(val dy:Int,val a:Int,val b:Int,val run:Float,val tx:IntArray,val tw:FloatArray,val lo:Int,val hi:Int)
    internal fun psfRows(k:Kernel):Array<PsfRow> {
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
    /** Same PSF/coverage equations, but use full-photo field coordinates and radius bound. */
    internal fun opticalRegion(high:Frame,coc:Frame,aperture:Float,cancel:Cancel,region:BokehRegion,foreground:Frame?=null):Frame {
        require(high.w==region.box.w && high.h==region.box.h && coc.w==high.w && coc.h==high.h)
        val limit=max(54f,FullFrameOptics.maxRadius(region.fullW,region.fullH)+.01f)
        require(coc.data.all{it.isFinite() && abs(it)<=limit})
        val scaled=Frame(coc.w,coc.h,1,FloatArray(coc.size){i->if(i and 65535==0)cancel.check();coc.data[i]*RADIUS_SCALE})
        return scatterBounded(high,scaled,aperture,cancel,limit*RADIUS_SCALE,true,foreground,true,region)
    }
    /** One full-photo color channel; CoC is owned/pre-scaled. RGB API retains its original equations. */
    internal fun opticalPlaneScaled(high:Frame,scaledCoc:Frame,aperture:Float,cancel:Cancel,foreground:Frame?=null):Frame {
        require(high.channels==1 && scaledCoc.channels==1 && high.w==scaledCoc.w && high.h==scaledCoc.h)
        foreground?.let{require(it.channels==1 && it.w==high.w && it.h==high.h)}
        val limit=max(54f,FullFrameOptics.maxRadius(high.w,high.h)+.01f)
        return scatterBounded(high,scaledCoc,aperture,cancel,limit*RADIUS_SCALE,true,foreground,true)
    }
    /** Same RGB scatter ONCE. Only receiver-band accumulation lives on heap; full RGB
     * color/own/coverage/foreground are lossless row files. Newly-created output stores are caller-owned. */
    internal fun opticalFiles(high:Frame,scaledCoc:Frame,aperture:Float,cancel:Cancel,color:BokehFloatFile,foreground:BokehFloatFile?,onLevel:(Int,Int)->Unit={_,_->}) {
        require(high.channels==3 && scaledCoc.channels==1 && high.w==scaledCoc.w && high.h==scaledCoc.h)
        require(color.w==high.w && color.h==high.h && color.channels==3)
        foreground?.let{require(it.w==high.w && it.h==high.h && it.channels==3)}
        val limit=max(54f,FullFrameOptics.maxRadius(high.w,high.h)+.01f)
        val fg=foreground?.let{Frame(high.w,min(64,high.h),3,FloatArray(high.w*min(64,high.h)*3))}
        RowState(color,foreground,cancel).use{state->scatterBounded(high,scaledCoc,aperture,cancel,limit*RADIUS_SCALE,true,fg,true,null,state,onLevel)}
        color.sync();foreground?.sync();cancel.check()
    }
    private class RowState(val color:BokehFloatFile,val foreground:BokehFloatFile?,val cancel:Cancel):Closeable {
        private val own=BokehFloatFile.create(File.createTempFile("light-own-",".f32",color.file.parentFile),color.w,color.h,3)
        private val coverage=try{BokehFloatFile.create(File.createTempFile("light-coverage-",".f32",color.file.parentFile),color.w,color.h,1)}catch(t:Throwable){own.close();own.file.delete();throw t}
        fun transfer(store:BokehFloatFile,y:Int,rows:Int,data:FloatArray,stride:Int,write:Boolean){
            for(c in 0 until store.channels){
                if(write)store.putRows(c,y,rows,data,cancel,c*stride)
                else store.rows(c,y,rows,data,cancel,c*stride)
            }
        }
        fun load(y:Int,rows:Int,c:FloatArray,o:FloatArray,a:FloatArray,f:FloatArray?,n:Int){
            transfer(color,y,rows,c,n,false);transfer(own,y,rows,o,n,false);transfer(coverage,y,rows,a,n,false)
            if(f!=null)transfer(checkNotNull(foreground),y,rows,f,n,false)
        }
        fun save(y:Int,rows:Int,c:FloatArray,o:FloatArray,a:FloatArray,f:FloatArray?,n:Int){
            transfer(color,y,rows,c,n,true);transfer(own,y,rows,o,n,true);transfer(coverage,y,rows,a,n,true)
            if(f!=null)transfer(checkNotNull(foreground),y,rows,f,n,true)
        }
        override fun close(){try{own.close()}finally{coverage.close();own.file.delete();coverage.file.delete()}}
    }
    private fun scatterBounded(high:Frame,coc:Frame,aperture:Float,cancel:Cancel,maxRadius:Float,field:Boolean=false,foreground:Frame?=null,pruneDark:Boolean=true,region:BokehRegion?=null,fileState:RowState?=null,onLevel:(Int,Int)->Unit={_,_->}):Frame {
        cancel.check();LegendaryMath.gain(aperture)
        require(high.channels in listOf(1,3) && coc.channels==1 && high.w==coc.w && high.h==coc.h)
        require(high.data.all{it.isFinite() && it>=0f} && coc.data.all{it.isFinite() && abs(it)<=maxRadius})
        val w=high.w;val h=high.h;val n=high.size;val channels=high.channels
        val workH=if(fileState==null)h else min(64,h);val workN=w*workH
        val color=Frame(w,workH,channels,FloatArray(workN*channels))
        if(high.data.all{it==0f})return color
        val minL=floor(coc.data.minOrNull()!!).toInt();val maxL=ceil(coc.data.maxOrNull()!!).toInt()
        val present=BooleanArray((maxL-minL+1)*h)
        parallelFor(h){y->
            cancel.check()
            for(i in y*w until (y+1)*w){val c=coc.data[i];present[(floor(c).toInt()-minL)*h+y]=true;present[(ceil(c).toInt()-minL)*h+y]=true}
        }
        val own=FloatArray(color.data.size);val coverage=FloatArray(workN)
        fun halfOf(r:Int)=if(r==0)0 else r+1
        val maxHalf=halfOf(max(abs(minL),abs(maxL)));val stride=w+2*maxHalf+1
        // Exact conservative support of nonzero radiance, including reflected edges.
        // Keep all occluding depth layers inside it; never threshold dim lights or lower resolution.
        val outLo=IntArray(h);val outHi=IntArray(h){w-1}
        if(pruneDark){
            val litLo=IntArray(h){w};val litHi=IntArray(h){-1}
            for(y in 0 until h){cancel.check();for(x in 0 until w){val i=y*w+x
                if(high.data[i]>0f || (channels==3 && (high.data[n+i]>0f || high.data[2*n+i]>0f))){litLo[y]=min(litLo[y],x);litHi[y]=x}
            }}
            for(y in 0 until h){cancel.check();var lo=w;var hi=-1
                for(sy in max(0,y-maxHalf)..min(h-1,y+maxHalf)){lo=min(lo,litLo[sy]);hi=max(hi,litHi[sy])}
                outLo[y]=if(hi<0)w else max(0,lo-maxHalf);outHi[y]=if(hi<0)-1 else min(w-1,hi+maxHalf)
            }
        }
        // Double row prefixes avoid cancelling tiny subpixel weights after long bright rows.
        // A receiver band needs only its exact PSF halo. Source-row tags let adjacent bands
        // retain prefixes instead of recomputing overlapping halos; float/double order is unchanged.
        val band=if(fileState==null && region==null && channels==3)256 else 64;val capacity=min(h,band+2*maxHalf)
        val tags=IntArray(capacity){-1}
        val maskP=DoubleArray(stride*capacity);val rP=DoubleArray(stride*capacity);val gP=DoubleArray(if(channels==3)stride*capacity else 0);val bP=DoubleArray(gP.size)
        val pmin=IntArray(h);val pmax=IntArray(h)
        val plans=HashMap<Int,Array<PsfRow>>()
        // Smooth partition-of-unity interpolation, not disjoint blocks. Only one radius of
        // spatial kernels is retained; keep the original V4 occlusion/coverage compositor.
        val nodes=if(field)(if(region==null)OpticalPupil.nodes(w,h)else OpticalPupil.nodes(region)).filter{node->!pruneDark || (0 until h).any{y->node.wy[y]>0f && outLo[y]<=node.x1 && outHi[y]>=node.x0}} else emptyList()
        val acc=ThreadLocal.withInitial{DoubleArray(4*w)}
        for(level in maxL downTo minL){
            cancel.check()
            // No source pixels at this depth: both radiance and occlusion mass
            // are exactly zero. Avoid constructing 25 unused field PSFs.
            if(pruneDark && !(0 until h).any{present[(level-minL)*h+it]}){onLevel(maxL-level+1,maxL-minL+1);continue}
            val half=halfOf(abs(level))
            val levelPlans=if(field && level!=0)nodes.map{node->Plan(psfRows(OpticalPupil.kernel(abs(level),aperture,OpticalPupil.shape(node.x,node.y,region?.fullW?:w,region?.fullH?:h),cancel)),node)}
                else listOf(Plan(plans.getOrPut(abs(level)){psfRows(LegendaryMath.kernel(abs(level),aperture))},null))
            val padded=w+2*half;val colOf=IntArray(padded){reflect(it-half,w)};val li=(level-minL)*h
            tags.fill(-1)
            for(y0 in 0 until h step band){
            cancel.check();val y1=min(h,y0+band)
            if(fileState!=null && !(y0 until y1).any{outLo[it]<=outHi[it]})continue
            val source0=max(0,y0-half);val source1=min(h,y1+half)
            parallelFor(source1-source0){rowIndex->
                val sy=source0+rowIndex;val slot=sy%capacity
                if(tags[slot]==sy)return@parallelFor
                if(sy and 15==0)cancel.check()
                tags[slot]=sy
                if(!present[li+sy]){pmin[sy]=-1;return@parallelFor}
                val base=slot*stride;val src=sy*w;var lo=-1;var hi=-1
                var m=0.0;var r=0.0;var g=0.0;var b=0.0
                maskP[base]=0.0;rP[base]=0.0;if(channels==3){gP[base]=0.0;bP[base]=0.0}
                for(p in 0 until padded){
                    val i=src+colOf[p];val weight=layerWeight(coc.data[i],level).toDouble()
                    if(weight>0){m+=weight;r+=high.data[i]*weight;if(channels==3){g+=high.data[n+i]*weight;b+=high.data[2*n+i]*weight};if(lo<0)lo=p;hi=p}
                    maskP[base+p+1]=m;rP[base+p+1]=r;if(channels==3){gP[base+p+1]=g;bP[base+p+1]=b}
                }
                pmin[sy]=lo;pmax[sy]=hi
            }
            fileState?.load(y0,y1-y0,color.data,own,coverage,foreground?.data,workN)
            parallelFor(y1-y0){rowIndex->
                val y=y0+rowIndex
                if(y and 7==0)cancel.check()
                if(outLo[y]>outHi[y])return@parallelFor
                val a=checkNotNull(acc.get());var touched=false
                for(plan in levelPlans){
                    val node=plan.node;val wy=node?.wy?.get(y)?:1f;if(wy<=0f)continue
                    for(row in plan.rows){
                    val sy=reflect(y+row.dy,h);if(!present[li+sy] || pmin[sy]<0)continue
                    val x0=max(outLo[y],max(node?.x0?:0,max(0,pmin[sy]-row.hi)));val x1=min(outHi[y],min(node?.x1?:(w-1),min(w-1,pmax[sy]-row.lo)));if(x0>x1)continue
                    if(!touched){java.util.Arrays.fill(a,0.0);touched=true}
                    val base=(sy%capacity)*stride;val src=sy*w
                    if(row.a>=0)for(x in x0..x1){
                        val p0=base+x+row.a;val p1=base+x+row.b+1;val mass=maskP[p1]-maskP[p0]
                        if(mass<=0)continue
                        val kw=row.run*wy*(node?.wx?.get(x)?:1f)
                        a[x]+=kw*mass;a[w+x]+=kw*(rP[p1]-rP[p0]);if(channels==3){a[2*w+x]+=kw*(gP[p1]-gP[p0]);a[3*w+x]+=kw*(bP[p1]-bP[p0])}
                    }
                    for(t in row.tx.indices){
                        val kx=row.tx[t];val kw=row.tw[t]
                        for(x in x0..x1){
                            val i=src+colOf[x+kx];val weight=(kw*wy*(node?.wx?.get(x)?:1f)*layerWeight(coc.data[i],level)).toDouble();if(weight<=0)continue
                            a[x]+=weight;a[w+x]+=high.data[i]*weight;if(channels==3){a[2*w+x]+=high.data[n+i]*weight;a[3*w+x]+=high.data[2*n+i]*weight}
                        }
                    }
                }}
                if(!touched)return@parallelFor
                for(x in outLo[y]..outHi[y]){
                    val alpha=a[x].toFloat().coerceIn(0f,1f);if(alpha<=1e-8f)continue
                    val i=y*w+x;val di=if(fileState==null)i else rowIndex*w+x;val local=layerWeight(coc.data[i],level)
                    val take=min(alpha*visibility(coc.data[i],level),max(0f,1f-coverage[di]))
                    for(c in 0 until channels){
                        val at=c*workN+di;val radiance=max(0.0,a[(c+1)*w+x]).toFloat()
                        own[at]+=local*(radiance/alpha)
                        color.data[at]+=radiance*(take/alpha)
                        // Keep the same visibility/coverage. Only near-side light may veil a sharp subject.
                        if(level>0 && foreground!=null)foreground.data[at]+=radiance*(take/alpha)
                    }
                    coverage[di]=(coverage[di]+take).coerceIn(0f,1f)
                }
            }
            fileState?.save(y0,y1-y0,color.data,own,coverage,foreground?.data,workN)
            } // receiver band (all readers joined before recycling prefix slots)
            cancel.check();onLevel(maxL-level+1,maxL-minL+1)
        }
        for(y0 in 0 until h step workH){
            val count=min(workH,h-y0);fileState?.load(y0,count,color.data,own,coverage,foreground?.data,workN)
            parallelFor(count){r->cancel.check();val start=if(fileState==null)(y0+r)*w else r*w
                for(i in start until start+w)for(c in 0 until channels){val at=c*workN+i;color.data[at]+=(1f-coverage[i])*own[at]}
            }
            fileState?.let{it.transfer(it.color,y0,count,color.data,workN,true)}
        }
        cancel.check();return color
    }
}
