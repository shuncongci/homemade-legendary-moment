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
    /** O(width+height) geometry, shared by RGB channels; tap multiplication/add order is unchanged. */
    private class ResizeAxis(source:Int,size:Int,cubic:Boolean,alignCorners:Boolean){
        val taps=if(cubic)4 else 2
        val index=IntArray(size*taps);val weight=FloatArray(size*taps)
        init {for(i in 0 until size){
            val s=if(alignCorners)if(size==1)0f else i*(source-1f)/(size-1)else (i+.5f)*source/size-.5f
            val base=floor(s).toInt();val fraction=s-base
            for(tap in 0 until taps){val d=if(cubic)tap-1 else tap;val at=i*taps+tap
                index[at]=(base+d).coerceIn(0,source-1)
                weight[at]=if(cubic)cubic(d-fraction)else if(d==0)1-fraction else fraction
            }
        }}
    }
    /** OpenCV half-pixel linear/cubic; alignCorners is used for torch depth resizing. */
    fun resize(f: Frame,w: Int,h: Int,cubic: Boolean=false,alignCorners: Boolean=false,cancel: Cancel?=null): Frame {
        if(w==f.w && h==f.h)return f
        val out=Frame(w,h,f.channels,FloatArray(w*h*f.channels))
        val ax=ResizeAxis(f.w,w,cubic,alignCorners);val ay=ResizeAxis(f.h,h,cubic,alignCorners);val taps=ax.taps
        for(c in 0 until f.channels) parallelFor(h) { y ->
            if(y and 31==0)cancel?.check()
            val offset=c*f.size;val yy=y*taps
            for(x in 0 until w) {
                val xx=x*taps;var sum=0f
                for(dy in 0 until taps)for(dx in 0 until taps)
                    sum+=f.data[offset+ay.index[yy+dy]*f.w+ax.index[xx+dx]]*ay.weight[yy+dy]*ax.weight[xx+dx]
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
    internal fun srgb(x: Float)=if(x<=.0031308f)12.92f*max(x,0f) else 1.055f*max(x,0f).pow(1/2.4f)-.055f
    /** Exact byte-domain shortcut only; continuous Float32 values still use the original power function. */
    internal fun linearQuantized(x:Float):Float {
        if(!x.isFinite())return linear(x)
        val code=(x*255).roundToInt().coerceIn(0,255)
        return if(x.toRawBits()==(code/255f).toRawBits())HdrMath.linearByte(code)else linear(x)
    }
    private fun quantize(x: Float)=(Math.rint((x.coerceIn(0f,1f)*255).toDouble())/255).toFloat()
    private val quantizedByteBase=FloatArray(256){quantize(srgb(HdrMath.linearByte(it)))}
    /** Exact byte round trip, cached only for ratio==1; continuous values keep pow(). */
    internal fun baseChannel(p:Planes,i:Int,cap:Float,c:Int):Float {
        val r=HdrMath.linearByte(p.p0[i].toInt()and 255);val g=HdrMath.linearByte(p.p1[i].toInt()and 255);val b=HdrMath.linearByte(p.p2[i].toInt()and 255)
        val ratio=min(1f,cap/max(max(r,max(g,b)),1e-8f))
        return if(ratio==1f)quantizedByteBase[p.plane(c)[i].toInt()and 255] else quantize(srgb((if(c==0)r else if(c==1)g else b)*ratio))
    }
    /** Same quantized SDR split base, computed per pixel for bounded region I/O. */
    internal fun basePixel(p:Planes,i:Int,cap:Float,out:FloatArray){
        val r=HdrMath.linearByte(p.p0[i].toInt()and 255);val g=HdrMath.linearByte(p.p1[i].toInt()and 255);val b=HdrMath.linearByte(p.p2[i].toInt()and 255)
        val ratio=min(1f,cap/max(max(r,max(g,b)),1e-8f))
        if(ratio==1f){out[0]=quantizedByteBase[p.p0[i].toInt()and 255];out[1]=quantizedByteBase[p.p1[i].toInt()and 255];out[2]=quantizedByteBase[p.p2[i].toInt()and 255]}
        else {out[0]=quantize(srgb(r*ratio));out[1]=quantize(srgb(g*ratio));out[2]=quantize(srgb(b*ratio))}
    }
    internal fun split(p: Planes,cancel:Cancel): Pair<Frame,Frame> {
        val caps=HighlightExtraction.caps(p,cancel)
        val size=p.size;val base=Frame(p.width,p.height,3,FloatArray(size*3));val high=Frame(p.width,p.height,3,FloatArray(size*3))
        parallelFor(p.height) { y -> for(i in y*p.width until (y+1)*p.width) {
            val r=HdrMath.linearByte(p.p0[i].toInt()and 255);val g=HdrMath.linearByte(p.p1[i].toInt()and 255);val b=HdrMath.linearByte(p.p2[i].toInt()and 255);val ratio=min(1f,caps[i]/max(max(r,max(g,b)),1e-8f))
            for(c in 0..2) { val value=when(c){0->r;1->g;else->b};val at=c*size+i
                base.data[at]=quantize(srgb(value*ratio));high.data[at]=max(0f,value-linearQuantized(base.data[at]))
            }
        } }
        return base to high
    }
    /** Render one whole 2048-fit photo; optical multiscale passes are not image tiles. */
    private fun multiscale(rgb: Frame,coc: Frame,cancel: Cancel,globalScales: FloatArray?,onPass:((Frame)->Unit)?,checkpoint:MultiscaleCheckpoint?,infer: (Frame,Frame)->Frame): Frame {
        if(infer is FullCocRenderer){
            require(globalScales==null){"整图分层算法不接受神经模型尺度"}
            cancel.check()
            val saved=checkpoint?.load()
            val restored=saved?.takeIf{it.finished && it.index==1 && it.scale==1f && it.previous==1f}?.composite
            val output=restored ?: infer(rgb,coc)
            require(output.w==rgb.w && output.h==rgb.h && output.channels==3 && output.data.all{it.isFinite() && it in 0f..1f}){"分层虚化输出异常"}
            if(restored==null)checkpoint?.save(MultiscaleCheckpoint.State(1f,1f,1,true,output),cancel)
            // Same persistence/borrowed-preview/cancellation contract as the neural path.
            cancel.check();onPass?.invoke(output);cancel.check()
            return output
        }
        val target=max(1f,coc.data.maxOf{abs(it)}/8)
        var scale=1f;var previous=1f;var composite: Frame?=null;var index=0
        require(globalScales==null || (globalScales.isNotEmpty() && globalScales.all{it.isFinite() && it>=1f}))
        val saved=checkpoint?.load()
        var finished=false
        if(saved!=null){
            require(saved.composite.w==rgb.w && saved.composite.h==rgb.h && saved.composite.channels==3 && saved.scale.isFinite() && saved.scale>=1f && saved.previous.isFinite() && saved.previous>=1f && saved.index>=1)
            scale=saved.scale;previous=saved.previous;index=saved.index;composite=saved.composite;finished=saved.finished
        }
        while(!finished) {
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
            previous=actual;index++
            finished=if(globalScales!=null)index>=globalScales.size else scale>=target
            if(!finished && globalScales==null)scale=min(scale*2,target)
            checkpoint?.save(MultiscaleCheckpoint.State(scale,previous,index,finished,composite!!),cancel)
            // Persistence must precede observer mutation / animation and cancellation.
            cancel.check();onPass?.invoke(composite!!);cancel.check()
        }
        val output=composite!!
        parallelFor(rgb.h) { y -> for(i in y*rgb.w until (y+1)*rgb.w) {
            val weight=smooth(abs(coc.data[i]))
            for(c in 0..2){val at=c*rgb.size+i;output.data[at]=quantize(rgb.data[at]*(1-weight)+output.data[at]*weight)}
        } }
        return output
    }
    /** HDR companion render: same production ARNet, depth/CoC/alpha/PSFs; no SDR shoulder or invented clipped-light recovery. */
    fun renderHdr(colored:Planes,sharp:Frame,depth:Frame,focus:Float,aperture:Float,cancel:Cancel,subjectMask:Frame?=null,infer:(Frame,Frame)->Frame):Frame {
        return renderHdrCombined(colored,sharp,depth,focus,aperture,cancel,subjectMask,infer=infer)
    }
    /** One network pass, independent SDR/HDR radiance. Share geometry, never substitute HDR light for SDR. */
    fun renderHdrCombined(colored:Planes,sharp:Frame,depth:Frame,focus:Float,aperture:Float,cancel:Cancel,
        subjectMask:Frame?=null,onSdr:(Planes)->Unit = {},onPass:((Frame)->Unit)?=null,
        progress:(String)->Unit = {},checkpoint:MultiscaleCheckpoint?=null,infer:(Frame,Frame)->Frame):Frame {
        HdrMath.checked(sharp);require(sharp.w==colored.width && sharp.h==colored.height)
        val raw=FullFrameOptics.coc(depth,focus,aperture,sharp.w,sharp.h,cancel)
        val alpha=subjectMask?.let{SharpSubjectEdges.renderAlpha(colored,it,cancel)}
        val coc=if(alpha==null)raw else SubjectProtectionMath.protectedCoc(raw,alpha,cancel)
        if(coc.data.maxOf{abs(it)}<1e-6f){cancel.check();onSdr(colored);return sharp}
        // The network input is exactly the SDR base, independent of all HDR gains.
        // Transport positive HDR excess and negative HDR deficit separately; neither
        // may modify the SDR residual, network input, or SDR compositor.
        data class PreparedLight(val rendered:Frame,val hdrHigh:Frame,val hdrLoss:Frame?,val sdr:Planes)
        fun prepareLight():PreparedLight {
            val hasHighlight=(0 until colored.size).any{(colored.p0[it].toInt() and 255)>80 || (colored.p1[it].toInt() and 255)>80 || (colored.p2[it].toInt() and 255)>80}
            val (base,sdrHigh)=if(hasHighlight)split(colored,cancel) else
                rgb(colored) to Frame(sharp.w,sharp.h,3,FloatArray(sharp.data.size))
            val hdrHigh=Frame(sharp.w,sharp.h,3,FloatArray(sharp.data.size))
            var hdrLoss:Frame?=null
            for(i in base.data.indices){if(i%sharp.w==0)cancel.check()
                val delta=sharp.data[i]-linearQuantized(base.data[i])
                hdrHigh.data[i]=max(0f,delta)
                if(delta<0f){
                    if(hdrLoss==null)hdrLoss=Frame(sharp.w,sharp.h,3,FloatArray(sharp.data.size))
                    hdrLoss.data[i]=-delta
                }
            }
            val plateCoc=if(alpha==null)coc else Frame(coc.w,coc.h,1,coc.data.copyOf()).also{BackgroundPlate.fillInPlace(alpha,cancel,base,it)}
            val pass=if(alpha==null)onPass else onPass?.let{subjectPreview(colored,alpha,it)}
            val rendered=multiscale(base,plateCoc,cancel,null,pass,checkpoint,infer)
            fun sdrResult():Planes {
                if(sdrHigh.data.all{it==0f}){
                    val out=planes(rendered)
                    return if(alpha==null)out else SubjectProtectionMath.composite(colored,out,alpha,cancel)
                }
                progress("SDR高光合成 · 保持原亮度")
                val radiance=BokehRadiance.boost(sdrHigh,coc,cancel)
                ClippedHighlights.recoverInPlace(radiance,colored,sdrHigh,coc,cancel)
                var foreground:Frame?=null
                val optical=if(alpha==null)SoftBokehEdges.softenInPlace(HighlightScatter.optical(radiance,coc,aperture,cancel),coc,cancel)
                    else HighlightScatter.opticalLayers(radiance,coc,aperture,cancel).let{
                        foreground=SoftBokehEdges.softenInPlace(it.foreground,coc,cancel)
                        SoftBokehEdges.softenInPlace(it.color,coc,cancel)
                    }
                val out=Planes(sharp.w,sharp.h)
                for(c in 0..2)for(i in 0 until sharp.size){if(i%sharp.w==0)cancel.check();val at=c*sharp.size+i
                    val value=srgb(SoftBokehEdges.shoulder(linearQuantized(rendered.data[at]),optical.data[at]))
                    out.plane(c)[i]=Math.rint((value.coerceIn(0f,1f)*255).toDouble()).toInt().toByte()
                }
                return if(alpha==null)out else BokehRadiance.protect(colored,out,alpha,checkNotNull(foreground),cancel,radiance,coc)
            }
            return PreparedLight(rendered,hdrHigh,hdrLoss,sdrResult())
        }
        val prepared=prepareLight() // SDR optical scratch is out of scope before HDR scatter.
        cancel.check();onSdr(prepared.sdr)
        val rendered=prepared.rendered;val high=prepared.hdrHigh
        progress("HDR高光合成 · 独立光能，不重跑模型")
        var lossForeground:Frame?=null
        val lossOptical=prepared.hdrLoss?.let{loss->
            if(alpha==null)SoftBokehEdges.softenInPlace(HighlightScatter.optical(loss,coc,aperture,cancel),coc,cancel)
            else HighlightScatter.opticalLayers(loss,coc,aperture,cancel).let{
                lossForeground=SoftBokehEdges.softenInPlace(it.foreground,coc,cancel)
                SoftBokehEdges.softenInPlace(it.color,coc,cancel)
            }
        }
        var foreground:Frame?=null
        val optical=if(alpha==null)SoftBokehEdges.softenInPlace(HighlightScatter.optical(high,coc,aperture,cancel),coc,cancel)
            else HighlightScatter.opticalLayers(high,coc,aperture,cancel).let{
                foreground=SoftBokehEdges.softenInPlace(it.foreground,coc,cancel)
                SoftBokehEdges.softenInPlace(it.color,coc,cancel)
            }
        for(i in 0 until sharp.size){if(i%sharp.w==0)cancel.check();val a=alpha?.data?.get(i)?.coerceIn(0f,1f)?:0f
            for(c in 0..2){val at=c*sharp.size+i
                // Non-negative physical radiance after signed residual transport; no global exposure factor.
                val blurred=max(0f,linearQuantized(rendered.data[at])+optical.data[at]-(lossOptical?.data?.get(at)?:0f))
                val local=if(coc.data[i]>0)high.data[at]-(prepared.hdrLoss?.data?.get(at)?:0f)else 0f
                val incoming=max(0f,(foreground?.data?.get(at)?:0f)-(lossForeground?.data?.get(at)?:0f)-local)
                rendered.data[at]=sharp.data[at]*a+blurred*(1-a)+incoming*a
            }
        };return HdrMath.checked(rendered)
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
    fun render(colored: Planes,depth: Frame,focus: Float,aperture: Float,cancel: Cancel,progress: (String)->Unit = {},highlightRenderer:((Frame,Frame,Float,Cancel)->Frame)? = null,onPass:((Frame)->Unit)?=null,subjectMask:Frame?=null,highlightBoost:Float=BokehRadiance.DEFAULT_GAIN,checkpoint:MultiscaleCheckpoint?=null,infer: (Frame,Frame)->Frame): Planes {
        val rawCoc=FullFrameOptics.coc(depth,focus,aperture,colored.width,colored.height,cancel)
        // One render-resolution alpha (edge ramp tightened when enlarged) for CoC, plate and composite.
        val alpha=subjectMask?.let{SharpSubjectEdges.renderAlpha(colored,it,cancel)}
        val coc=if(alpha==null)rawCoc else SubjectProtectionMath.protectedCoc(rawCoc,alpha,cancel)
        var foreground:Frame?=null;var sourceHigh:Frame?=null
        val rendered=renderCoc(colored,coc,aperture,cancel,progress=progress,highlightRenderer=highlightRenderer,onPass=onPass,highlightBoost=highlightBoost,onForeground=if(alpha==null)null else {light->foreground=light},onHighlightSource=if(alpha==null)null else {source->sourceHigh=source},subjectAlpha=alpha,checkpoint=checkpoint,infer=infer)
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
    fun renderCoc(colored: Planes,coc: Frame,aperture: Float,cancel: Cancel,progress: (String)->Unit = {},globalScales: FloatArray? = null,highlightRenderer:((Frame,Frame,Float,Cancel)->Frame)? = null,highlightSplitter:(Planes,Cancel)->Pair<Frame,Frame> = ::split,onPass:((Frame)->Unit)?=null,highlightBoost:Float=BokehRadiance.DEFAULT_GAIN,onForeground:((Frame)->Unit)?=null,onHighlightSource:((Frame)->Unit)?=null,highlightShoulder:Boolean=highlightRenderer==null,subjectAlpha:Frame?=null,clippedRecovery:Boolean=highlightRenderer==null,checkpoint:MultiscaleCheckpoint?=null,infer: (Frame,Frame)->Frame): Planes {
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
            return multiscale(rgb,plateCoc,cancel,globalScales,pass,checkpoint,infer)
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
            val b=linearQuantized(rendered.data[i]);rendered.data[i]=srgb(if(highlightShoulder)SoftBokehEdges.shoulder(b,optical.data[i]) else b+optical.data[i])
        } }
        progress("整图RGB成片转换 ${colored.width}×${colored.height}")
        cancel.check();return planes(rendered)
    }
}
