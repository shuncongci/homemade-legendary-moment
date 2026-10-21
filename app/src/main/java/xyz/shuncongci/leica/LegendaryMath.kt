package xyz.shuncongci.leica

import kotlin.math.*

/** Relative depth and ARNet rendering math. No automatic focus; optional explicit subject mask. */
object LegendaryMath {
    data class Point(val x: Float, val y: Float) { init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) } }
    data class Frame(val w: Int, val h: Int, val channels: Int, val data: FloatArray) {
        init { require(w > 0 && h > 0 && data.size == w*h*channels) }
        val size get() = w*h
    }
    val apertures = floatArrayOf(.95f,1.8f,2f,2.8f,4f,5.6f)
    fun gain(aperture: Float): Float = when(aperture) {
        .95f -> 96f/1.8f; 1.8f -> 21.6f; 2f -> 19.44f; 2.8f -> 15.12f; 4f -> 10.8f; 5.6f -> 10.8f*4/5.6f
        else -> throw IllegalArgumentException("不支持的光圈")
    }
    private fun byte(x: Byte) = (x.toInt() and 255)/255f
    fun rgb(p: Planes) = Frame(p.width,p.height,3,FloatArray(p.size*3) { i -> byte(p.plane(i/p.size)[i%p.size]) })
    fun planes(f: Frame): Planes {
        require(f.channels==3 && f.data.all { it.isFinite() })
        return Planes(f.w,f.h).also { p -> for(c in 0..2) for(i in 0 until p.size) p.plane(c)[i] = Math.rint((f.data[c*p.size+i].coerceIn(0f,1f)*255).toDouble()).toInt().toByte() }
    }
    private fun percent(sorted: FloatArray,q: Double): Float {
        val x=q*(sorted.size-1);val i=floor(x).toInt();val j=min(i+1,sorted.size-1)
        return (sorted[i]*(1-(x-i))+sorted[j]*(x-i)).toFloat()
    }
    fun normalize(raw: Frame): Frame {
        require(raw.channels==1 && raw.data.all { it.isFinite() })
        val s=raw.data.copyOf();s.sort();val lo=percent(s,.02);val hi=percent(s,.98)
        require(hi-lo>=1e-5f) { "原照片深度无法区分，请更换照片" }
        return Frame(raw.w,raw.h,1,FloatArray(raw.size){ ((raw.data[it]-lo)/(hi-lo)).coerceIn(0f,1f) })
    }
    fun focus(depth: Frame,point: Point): Float? {
        require(depth.channels==1 && depth.data.all { it.isFinite() && it in 0f..1f })
        val x=Math.rint((point.x*(depth.w-1)).toDouble()).toInt();val y=Math.rint((point.y*(depth.h-1)).toDouble()).toInt()
        val r=Math.rint((min(depth.w,depth.h)*.006).toDouble()).toInt().coerceIn(1,8)
        val x0=max(0,x-r);val x1=min(depth.w,x+r+1);val y0=max(0,y-r);val y1=min(depth.h,y+r+1)
        val values=FloatArray((x1-x0)*(y1-y0));var at=0
        for(yy in y0 until y1) for(xx in x0 until x1) values[at++]=depth.data[yy*depth.w+xx]
        values.sort();val med=percent(values,.5)
        return if(percent(values,.9)-percent(values,.1)<=.08f && abs(depth.data[y*depth.w+x]-med)<=.04f && depth.data.maxOrNull()!!-depth.data.minOrNull()!!>1e-5f) med else null
    }
    private fun cubic(x0: Float): Float {
        val x=abs(x0);val a=-.75f
        return if(x<=1f) (a+2)*x*x*x-(a+3)*x*x+1 else if(x<2) a*x*x*x-5*a*x*x+8*a*x-4*a else 0f
    }
    /** OpenCV half-pixel linear/cubic; alignCorners is used for torch depth resizing. */
    fun resize(f: Frame,w: Int,h: Int,cubic: Boolean=false,alignCorners: Boolean=false,cancel: Cancel?=null): Frame {
        if(w==f.w && h==f.h)return f
        val out=Frame(w,h,f.channels,FloatArray(w*h*f.channels))
        for(c in 0 until f.channels) parallelFor(h) { y ->
            if(y and 31==0)cancel?.check()
            val sy=if(alignCorners) if(h==1)0f else y*(f.h-1f)/(h-1) else (y+.5f)*f.h/h-.5f
            val iy=floor(sy).toInt();val fy=sy-iy
            for(x in 0 until w) {
                val sx=if(alignCorners) if(w==1)0f else x*(f.w-1f)/(w-1) else (x+.5f)*f.w/w-.5f
                val ix=floor(sx).toInt();val fx=sx-ix;var sum=0f
                if(cubic) for(dy in -1..2) for(dx in -1..2) sum+=f.data[c*f.size+(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*cubic(dy-fy)*cubic(dx-fx)
                else for(dy in 0..1) for(dx in 0..1) sum+=f.data[c*f.size+(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*(if(dy==0)1-fy else fy)*(if(dx==0)1-fx else fx)
                out.data[c*out.size+y*w+x]=sum
            }
        }
        return out
    }
    /** Area integration, as INTER_AREA for the multiscale input. */
    fun area(f: Frame,w: Int,h: Int,cancel: Cancel): Frame {
        if(w==f.w && h==f.h)return f
        val out=Frame(w,h,f.channels,FloatArray(w*h*f.channels));val sx=f.w.toDouble()/w;val sy=f.h.toDouble()/h
        for(c in 0 until f.channels) parallelFor(h) { y ->
            cancel.check();val ya=y*sy;val yb=(y+1)*sy
            for(x in 0 until w) {
                val xa=x*sx;val xb=(x+1)*sx;var sum=0.0
                for(yy in floor(ya).toInt() until ceil(yb).toInt()) for(xx in floor(xa).toInt() until ceil(xb).toInt()) {
                    val weight=(min(yb,yy+1.0)-max(ya,yy.toDouble()))*(min(xb,xx+1.0)-max(xa,xx.toDouble()))
                    sum+=f.data[c*f.size+yy.coerceIn(0,f.h-1)*f.w+xx.coerceIn(0,f.w-1)]*weight
                }
                out.data[c*out.size+y*w+x]=(sum/(sx*sy)).toFloat()
            }
        }
        return out
    }
    private fun smooth(t: Float): Float { val x=t.coerceIn(0f,1f);return x*x*(3-2*x) }
    private fun linear(x: Float)=if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)
    private fun srgb(x: Float)=if(x<=.0031308f)12.92f*max(x,0f) else 1.055f*max(x,0f).pow(1/2.4f)-.055f
    private fun quantize(x: Float)=(Math.rint((x.coerceIn(0f,1f)*255).toDouble())/255).toFloat()
    internal fun split(p: Planes,cancel:Cancel): Pair<Frame,Frame> {
        val caps=HighlightExtraction.caps(p,cancel)
        val size=p.size;val base=Frame(p.width,p.height,3,FloatArray(size*3));val high=Frame(p.width,p.height,3,FloatArray(size*3))
        parallelFor(p.height) { y -> for(i in y*p.width until (y+1)*p.width) {
            val r=linear(byte(p.p0[i]));val g=linear(byte(p.p1[i]));val b=linear(byte(p.p2[i]));val ratio=min(1f,caps[i]/max(max(r,max(g,b)),1e-8f))
            for(c in 0..2) { val value=when(c){0->r;1->g;else->b};val at=c*size+i
                base.data[at]=quantize(srgb(value*ratio));high.data[at]=max(0f,value-linear(base.data[at]))
            }
        } }
        return base to high
    }
    /** Render one whole 2048-fit photo; optical multiscale passes are not image tiles. */
    private fun multiscale(rgb: Frame,coc: Frame,cancel: Cancel,globalScales: FloatArray?,onPass:((Frame)->Unit)?,infer: (Frame,Frame)->Frame): Frame {
        val target=max(1f,coc.data.maxOf{abs(it)}/8)
        var scale=1f;var previous=1f;var composite: Frame?=null;var index=0
        require(globalScales==null || (globalScales.isNotEmpty() && globalScales.all{it.isFinite() && it>=1f}))
        while(true) {
            cancel.check();val requested=globalScales?.get(index) ?: scale
            val w=max(2,ceil(rgb.w/requested).toInt());val h=max(2,ceil(rgb.h/requested).toInt())
            val actual=globalScales?.get(index) ?: max(rgb.w.toFloat()/w,rgb.h.toFloat()/h)
            val small=area(rgb,w,h,cancel);val df=resize(coc,w,h,cancel=cancel)
            val defocus=Frame(w,h,1,FloatArray(w*h){(df.data[it]/actual/10).coerceIn(-1f,1f)})
            cancel.check()
            val inferred=infer(small,defocus)
            // Reference/test callbacks may alias input; ORT returns a new, owned frame.
            val aliased=inferred.data===small.data || inferred.data===defocus.data || inferred.data===composite?.data
            val rendered=if(aliased)Frame(inferred.w,inferred.h,inferred.channels,inferred.data.copyOf()) else inferred
            require(rendered.w==w && rendered.h==h && rendered.channels==3 && rendered.data.all{it.isFinite()}) { "虚化模型输出异常" }
            for(i in rendered.data.indices)rendered.data[i]=rendered.data[i].coerceIn(0f,1f)
            val enlarged=resize(rendered,rgb.w,rgb.h,cancel=cancel)
            if(composite==null)composite=enlarged else { val into=composite;val prev=previous
                parallelFor(rgb.h) { y -> for(i in y*rgb.w until (y+1)*rgb.w) {
                    val weight=smooth((abs(coc.data[i])-5*prev)/(3*prev))
                    for(c in 0..2){val at=c*rgb.size+i;into.data[at]=into.data[at]*(1-weight)+enlarged.data[at]*weight}
                } }
            }
            // Borrowed real composite. Observer must copy and must never change production pixels.
            cancel.check();onPass?.invoke(composite!!);cancel.check()
            previous=actual
            if(globalScales!=null){index++;if(index>=globalScales.size)break}
            else {if(scale>=target)break;scale=min(scale*2,target)}
        }
        val output=composite!!
        parallelFor(rgb.h) { y -> for(i in y*rgb.w until (y+1)*rgb.w) {
            val weight=smooth(abs(coc.data[i]))
            for(c in 0..2){val at=c*rgb.size+i;output.data[at]=quantize(rgb.data[at]*(1-weight)+output.data[at]*weight)}
        } }
        return output
    }
    internal data class Kernel(val half: Int,val data: FloatArray) { val w get()=half*2+1 }
    internal fun kernel(radius: Int,aperture: Float): Kernel {
        if(radius==0)return Kernel(0,floatArrayOf(1f))
        val half=ceil(radius+.5).toInt();val w=half*2+1;val a=FloatArray(w*w)
        val round=when(aperture){.95f->1.0;1.8f->.85;2f->.8;2.8f->.65;4f->.45;else->.25}
        // Conservative geometric bounds: only boundary pixels need the unchanged
        // exhaustive 8x8 test. This is exact, not reduced sample quality.
        val inner=radius*(round+(1-round)*cos(PI/9));val allowance=sqrt(.5)
        for(y in 0 until w) for(x in 0 until w) {
            val distance=hypot((x-half).toDouble(),(y-half).toDouble())
            if(distance<inner-allowance){a[y*w+x]=1f;continue}
            if(distance>radius+allowance)continue
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
    internal fun scatter(high:Frame,coc:Frame,aperture:Float,cancel:Cancel):Frame =
        HighlightScatter.scatter(high,coc,aperture,cancel)
    fun render(colored: Planes,depth: Frame,focus: Float,aperture: Float,cancel: Cancel,progress: (String)->Unit = {},highlightRenderer:((Frame,Frame,Float,Cancel)->Frame)? = null,onPass:((Frame)->Unit)?=null,subjectMask:Frame?=null,highlightBoost:Float=BokehRadiance.DEFAULT_GAIN,infer: (Frame,Frame)->Frame): Planes {
        val rawCoc=FullFrameOptics.coc(depth,focus,aperture,colored.width,colored.height,cancel)
        // One render-resolution alpha (edge ramp tightened when enlarged) for CoC, plate and composite.
        val alpha=subjectMask?.let{SharpSubjectEdges.renderAlpha(colored,it,cancel)}
        val coc=if(alpha==null)rawCoc else SubjectProtectionMath.protectedCoc(rawCoc,alpha,cancel)
        var foreground:Frame?=null;var sourceHigh:Frame?=null
        val rendered=renderCoc(colored,coc,aperture,cancel,progress=progress,highlightRenderer=highlightRenderer,onPass=onPass,highlightBoost=highlightBoost,onForeground=if(alpha==null)null else {light->foreground=light},onHighlightSource=if(alpha==null)null else {source->sourceHigh=source},subjectAlpha=alpha,infer=infer)
        return if(alpha==null)rendered else foreground?.let{BokehRadiance.protect(colored,rendered,alpha,it,cancel,sourceHigh,coc)}?:SubjectProtectionMath.composite(colored,rendered,alpha,cancel)
    }
    /** Preview of a background-plate pass: temporarily show the sharp subject over it, then
     * restore the exact production floats (observer contract: borrowed, must copy). */
    private fun subjectPreview(sharp:Planes,alpha:Frame,observer:(Frame)->Unit):(Frame)->Unit = {frame->
        val n=frame.size;var count=0
        for(i in 0 until n)if(alpha.data[i]>0f)count++
        val saved=FloatArray(count*3);var k=0
        for(i in 0 until n){if(!(alpha.data[i]>0f))continue;val t=min(1f,alpha.data[i])
            for(c in 0..2){val at=c*n+i;saved[k*3+c]=frame.data[at];frame.data[at]=frame.data[at]*(1-t)+byte(sharp.plane(c)[i])*t};k++
        }
        try{observer(frame)}finally{k=0
            for(i in 0 until n){if(!(alpha.data[i]>0f))continue;for(c in 0..2)frame.data[c*n+i]=saved[k*3+c];k++}
        }
    }
    fun renderCoc(colored: Planes,coc: Frame,aperture: Float,cancel: Cancel,progress: (String)->Unit = {},globalScales: FloatArray? = null,highlightRenderer:((Frame,Frame,Float,Cancel)->Frame)? = null,highlightSplitter:(Planes,Cancel)->Pair<Frame,Frame> = ::split,onPass:((Frame)->Unit)?=null,highlightBoost:Float=BokehRadiance.DEFAULT_GAIN,onForeground:((Frame)->Unit)?=null,onHighlightSource:((Frame)->Unit)?=null,highlightShoulder:Boolean=highlightRenderer==null,subjectAlpha:Frame?=null,clippedRecovery:Boolean=highlightRenderer==null,infer: (Frame,Frame)->Frame): Planes {
        require(coc.w==colored.width && coc.h==colored.height && coc.channels==1)
        if(subjectAlpha!=null)require(subjectAlpha.channels==1 && subjectAlpha.w==colored.width && subjectAlpha.h==colored.height)
        cancel.check()
        if(coc.data.maxOf{abs(it)}<1e-6f)return colored
        val pass=if(subjectAlpha==null)onPass else onPass?.let{subjectPreview(colored,subjectAlpha,it)}
        // ARNet input: with an explicit subject, its colour and CoC are replaced by a clean
        // background plate so subject colour is never smeared into the surrounding blur.
        // [rgb] is a frame owned by this call. Highlight scatter keeps the protected [coc].
        fun bokeh(rgb:Frame):Frame {
            val plateCoc=if(subjectAlpha==null)coc else Frame(coc.w,coc.h,1,coc.data.copyOf()).also{BackgroundPlate.fillInPlace(subjectAlpha,cancel,rgb,it)}
            return multiscale(rgb,plateCoc,cancel,globalScales,pass,infer)
        }
        progress("整图高光分离 ${colored.width}×${colored.height}")
        val hasHighlight=(0 until colored.size).any{(colored.p0[it].toInt() and 255)>80 || (colored.p1[it].toInt() and 255)>80 || (colored.p2[it].toInt() and 255)>80}
        if(!hasHighlight)return planes(bokeh(rgb(colored)))
        val (base,high)=highlightSplitter(colored,cancel)
        if(high.data.all{it==0f})return planes(bokeh(base))
        val rendered=bokeh(base)
        progress("整图高光散景合成 ${colored.width}×${colored.height}")
        val radiance=BokehRadiance.boost(high,coc,cancel,highlightBoost)
        // Production only: clipped defocused point lights get their lost radiance back.
        if(clippedRecovery)ClippedHighlights.recoverInPlace(radiance,colored,high,coc,cancel)
        // Custom renderers keep the exact linear reference composite. Production light gets
        // softened disc rims and a highlight shoulder instead of hard-edged clipped plateaus.
        val optical=if(highlightRenderer!=null)highlightRenderer(radiance,coc,aperture,cancel)
            else if(onForeground==null)SoftBokehEdges.softenInPlace(HighlightScatter.optical(radiance,coc,aperture,cancel),coc,cancel)
            else HighlightScatter.opticalLayers(radiance,coc,aperture,cancel).let{onHighlightSource?.invoke(radiance);onForeground(SoftBokehEdges.softenInPlace(it.foreground,coc,cancel));SoftBokehEdges.softenInPlace(it.color,coc,cancel)}
        parallelFor(3*colored.height) { row -> for(i in row*colored.width until (row+1)*colored.width) {
            val b=linear(rendered.data[i]);rendered.data[i]=srgb(if(highlightShoulder)SoftBokehEdges.shoulder(b,optical.data[i]) else b+optical.data[i])
        } }
        progress("整图RGB成片转换 ${colored.width}×${colored.height}")
        cancel.check();return planes(rendered)
    }
}
