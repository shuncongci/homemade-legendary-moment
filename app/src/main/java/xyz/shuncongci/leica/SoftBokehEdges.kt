package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** 2.1.17 production finish for scattered highlight light (not a lens prescription).
 *
 * The exact pupil PSF is a flat disc with a ~1px anti-aliased rim. Many small lights at
 * one depth therefore became equal, hard-edged "sticker" dots, and their linear sum was
 * hard-clipped into flat white plateaus. Two bounded fixes, both after the exact scatter:
 *
 * 1. soften(): receiver-local separable tent (two box passes per axis) whose half-width is
 *    SOFTNESS x the enlarged highlight radius. Disc size (50% crossing) and energy are kept;
 *    only the rim becomes a gradual roll-off so neighbouring discs melt together.
 *    Pixels with half-width <= MIN_HALF (in focus / nearly in focus) are bit-identical, and
 *    such pixels never contribute to a defocused neighbour (no sharp-detail bleed).
 * 2. shoulder(): light is added exactly while the linear sum stays <= KNEE; above it the
 *    remaining headroom is filled exponentially (C1, monotone) instead of a hard clip.
 *    Pixels that receive no scattered light are unchanged.
 */
object SoftBokehEdges {
    const val SOFTNESS=.08f
    const val MIN_HALF=.5f
    const val KNEE=.8f

    internal fun half(coc:Float)=abs(coc)*HighlightScatter.RADIUS_SCALE*SOFTNESS
    /** 0 = untouched; ramps to 1 between half-width .5 and 1 px. Continuous in CoC. */
    internal fun weight(coc:Float)=((half(coc)-MIN_HALF)/MIN_HALF).coerceIn(0f,1f)

    fun shoulder(base:Float,light:Float):Float {
        val sum=base+light
        if(light<=0f || sum<=KNEE || base>=1f)return sum
        val start=max(base,KNEE)
        val room=1f-start
        val rest=light-(start-base)
        return start+room*(1f-exp(-rest/room))
    }

    /** Copying variant; never mutates [light] or [coc]. */
    fun soften(light:Frame,coc:Frame,cancel:Cancel):Frame =
        softenInPlace(Frame(light.w,light.h,light.channels,light.data.copyOf()),coc,cancel)

    /** Mutates and returns [light]. Only for frames owned by the caller. */
    internal fun softenInPlace(light:Frame,coc:Frame,cancel:Cancel):Frame {
        cancel.check()
        require(light.channels in listOf(1,3) && coc.channels==1 && light.w==coc.w && light.h==coc.h)
        require(coc.data.all{it.isFinite()})
        val w=light.w;val h=light.h;val n=w*h
        if(coc.data.none{weight(it)>0f})return light
        // Per-worker line scratch, not two full-resolution float planes or unbounded ThreadLocals.
        val workers=min(4,Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        val buffers=Array(workers){Line(max(w,h))}
        repeat(2){
            parallelFor(workers){worker->for(y in worker until h step workers){
                if(y and 15==0)cancel.check();buffers[worker].run(light,coc,y*w,1,w,n)}}
            cancel.check()
            parallelFor(workers){worker->for(x in worker until w step workers){
                if(x and 15==0)cancel.check();buffers[worker].run(light,coc,x,w,h,n)}}
            cancel.check()
        }
        return light
    }

    /** One line of the separable pass. Cells cover [j-.5,j+.5]; the window integral is
     * continuous in the (fractional) half-width and clamped to the image, so a constant
     * field stays exactly constant at borders. */
    private class Line(size:Int) {
        val pw=DoubleArray(size+1);val pr=DoubleArray(size+1);val pg=DoubleArray(size+1);val pb=DoubleArray(size+1)
        val cw=DoubleArray(size);val cr=DoubleArray(size);val cg=DoubleArray(size);val cb=DoubleArray(size)
        fun integral(p:DoubleArray,c:DoubleArray,len:Int,t:Double):Double {
            val u=(t+.5).coerceIn(0.0,len.toDouble());val k=floor(u).toInt()
            return if(k>=len)p[len] else p[k]+(u-k)*c[k]
        }
        val wt=FloatArray(size);val hw=FloatArray(size)
        fun run(f:Frame,coc:Frame,start:Int,step:Int,len:Int,n:Int) {
            var any=false
            for(k in 0 until len){val c=coc.data[start+k*step];wt[k]=weight(c);hw[k]=if(wt[k]>0f)half(c)else 0f;if(wt[k]>0f)any=true}
            if(!any)return
            pw[0]=0.0;pr[0]=0.0;pg[0]=0.0;pb[0]=0.0
            for(k in 0 until len){
                val i=start+k*step;val a=wt[k].toDouble()
                cw[k]=a;cr[k]=a*f.data[i];if(f.channels==3){cg[k]=a*f.data[n+i];cb[k]=a*f.data[2*n+i]}
                pw[k+1]=pw[k]+cw[k];pr[k+1]=pr[k]+cr[k];pg[k+1]=pg[k]+cg[k];pb[k+1]=pb[k]+cb[k]
            }
            // Prefix sums are complete before any write, so in-place output is safe.
            for(k in 0 until len){
                val i=start+k*step;val own=wt[k];if(own<=0f)continue
                val lo=k-hw[k].toDouble();val hi=k+hw[k].toDouble()
                val mass=integral(pw,cw,len,hi)-integral(pw,cw,len,lo);if(mass<=1e-12)continue
                val r=(integral(pr,cr,len,hi)-integral(pr,cr,len,lo))/mass
                if(f.channels==3){val g=(integral(pg,cg,len,hi)-integral(pg,cg,len,lo))/mass
                    val b=(integral(pb,cb,len,hi)-integral(pb,cb,len,lo))/mass
                    f.data[n+i]=max(0f,f.data[n+i]+own*(g.toFloat()-f.data[n+i]))
                    f.data[2*n+i]=max(0f,f.data[2*n+i]+own*(b.toFloat()-f.data[2*n+i]))
                }
                f.data[i]=max(0f,f.data[i]+own*(r.toFloat()-f.data[i]))
            }
        }
    }
}
