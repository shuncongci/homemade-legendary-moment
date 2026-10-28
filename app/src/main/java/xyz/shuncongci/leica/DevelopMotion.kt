package xyz.shuncongci.leica

import kotlin.math.*

/** Display-only timeline. No predicted progress, export settings or image mutation. */
class DevelopMotion {
    enum class Stage { IDLE, COLOR_WAIT, COLOR_FINISH, COLOR_HOLD, BOKEH_WAIT, BOKEH_FINISH, DONE }
    companion object { const val COLOR_MS=3300f;const val BOKEH_MS=420f }
    var stage=Stage.IDLE;private set
    var elapsed=0f;private set
    var seconds=0f;private set
    private var blurRequested=false
    private var finalReady=false
    private var colorReady=false
    private var bokehReady=false
    private var finishLift=0f
    private var finishDetail=0f
    val showsBokeh get()=stage==Stage.BOKEH_WAIT || stage==Stage.BOKEH_FINISH
    val presentationLabel get()=when(stage){Stage.COLOR_FINISH->"调色收尾";Stage.BOKEH_FINISH->"虚化收尾";else->null}
    fun statusText(actual:String)=presentationLabel?.let{"画面：$it\n$actual"}?:actual
    val visible get()=stage!=Stage.IDLE && stage!=Stage.DONE
    val finishing get()=stage==Stage.COLOR_FINISH || stage==Stage.BOKEH_FINISH
    val needsFrames get()=stage==Stage.COLOR_WAIT || finishing || (stage==Stage.BOKEH_WAIT && elapsed<550f)
    val progress get()=when(stage){Stage.COLOR_FINISH->(elapsed/COLOR_MS).coerceIn(0f,1f);Stage.BOKEH_FINISH->(elapsed/BOKEH_MS).coerceIn(0f,1f);else->0f}
    val bokehLift get()=when(stage){
        Stage.BOKEH_WAIT->DevelopMath.ease(elapsed/550f)*.78f
        Stage.BOKEH_FINISH->finishLift*(1-DevelopMath.ease(progress))
        else->0f
    }
    /** Fade real region data/indicators in with the lift; a fast final must not flash a dark grid. */
    val bokehDetail get()=when(stage){Stage.BOKEH_WAIT->DevelopMath.ease(elapsed/550f);Stage.BOKEH_FINISH->finishDetail;else->0f}
    private fun enter(next:Stage){if(next==Stage.BOKEH_FINISH){finishLift=bokehLift;finishDetail=bokehDetail};stage=next;elapsed=0f}
    fun startColor(blur:Boolean){reset();blurRequested=blur;enter(Stage.COLOR_WAIT)}
    fun colorCompleted(){check(stage==Stage.COLOR_WAIT);colorReady=true;enter(Stage.COLOR_FINISH)}
    fun startBokeh(){
        check(!bokehReady && stage!=Stage.DONE)
        bokehReady=true;blurRequested=true
        if(stage==Stage.IDLE || stage==Stage.COLOR_HOLD)enter(Stage.BOKEH_WAIT)
    }
    /** Retain actual progress immediately, but never truncate an in-flight color presentation. */
    fun bokehPassCompleted(){
        check(bokehReady && !finalReady)
        if(stage==Stage.COLOR_HOLD)enter(Stage.BOKEH_WAIT)
    }
    fun resultCompleted(rendered:Boolean){
        check(!finalReady && stage!=Stage.COLOR_WAIT)
        finalReady=true;blurRequested=rendered
        when(stage){
            Stage.IDLE->enter(Stage.DONE)
            Stage.COLOR_HOLD,Stage.BOKEH_WAIT->enter(if(rendered && bokehReady)Stage.BOKEH_FINISH else Stage.DONE)
            else->Unit
        }
    }
    fun advance(ms:Float){
        require(ms.isFinite() && ms>=0);var remaining=ms
        while(remaining>0 && needsFrames){
            val duration=when(stage){Stage.COLOR_FINISH->COLOR_MS;Stage.BOKEH_FINISH->BOKEH_MS;else->Float.POSITIVE_INFINITY}
            val step=min(remaining,(duration-elapsed).coerceAtLeast(0f))
            seconds=((seconds.toDouble()+step/1000.0)%3600.0).toFloat()
            elapsed=(elapsed.toDouble()+step).coerceAtMost(1e9).toFloat();remaining-=step
            if(stage==Stage.COLOR_FINISH && elapsed>=COLOR_MS){
                enter(if(blurRequested && bokehReady){if(finalReady)Stage.BOKEH_FINISH else Stage.BOKEH_WAIT}else if(finalReady)Stage.DONE else Stage.COLOR_HOLD)
            }else if(stage==Stage.BOKEH_FINISH && elapsed>=BOKEH_MS)enter(Stage.DONE)
        }
    }
    /** Skip decoration, never infer a result from elapsed time. */
    fun reduceMotion(){
        if(finalReady)enter(Stage.DONE)
        else if(stage==Stage.COLOR_FINISH && colorReady)enter(if(bokehReady)Stage.BOKEH_WAIT else Stage.COLOR_HOLD)
    }
    fun reset(){stage=Stage.IDLE;elapsed=0f;seconds=0f;blurRequested=false;finalReady=false;colorReady=false;bokehReady=false;finishLift=0f;finishDetail=0f}
}

/** Monotonic display clock: dropped frames catch up, stopped/background time is never replayed. */
class DevelopFrameClock {
    private var last:Long?=null
    fun tick(now:Long):Float {
        require(now>=0);val before=last
        if(before!=null && now<before)return 0f
        last=now;return if(before==null)0f else (now-before).toFloat()
    }
    fun reset(){last=null}
}

/** Main-thread one-shot completion. Reset/close cancels instead of leaving an awaiting coroutine suspended. */
class DevelopCompletion {
    private var callback:((Boolean)->Unit)?=null
    val pending get()=callback!=null
    fun await(done:(Boolean)->Unit){check(callback==null);callback=done}
    fun resolve(completed:Boolean){val cb=callback;callback=null;cb?.invoke(completed)}
}

object DevelopMath {
    const val IMPACT=.67f
    fun ease(t:Float):Float {require(t.isFinite());val x=t.coerceIn(0f,1f);return x*x*(3-2*x)}
    fun phase(p:Float,a:Float,b:Float):Float {require(a.isFinite() && b.isFinite() && b>a);return ease((p-a)/(b-a))}
    fun field(x:Float,y:Float,time:Float):Float {
        val xx=x+.07f*sin(y*9f+time*.73f);val yy=y+.09f*cos(x*7f-time*.61f)
        return (sin(xx*13f+yy*4f+time*1.4f)+sin(yy*17f-xx*5f-time*1.1f)+.55f*sin(xx*31f+yy*23f+time*.8f))/2.55f
    }
    /** Whole-image pigment breath; every area both loses and regains color. */
    fun pigment(x:Float,y:Float,time:Float)=.06f+.94f*ease((field(x,y,time)+.42f)/.84f)
    fun colorScale(p:Float)=1-.25f*phase(p,0f,.18f)*(1-phase(p,.90f,1f))
    fun colorOffset(p:Float)=-.14f*phase(p,0f,.18f)*(1-phase(p,.90f,1f))
    fun stripped(p:Float)=phase(p,.16f,.43f)
    fun revealed(x:Float,y:Float,p:Float):Float {
        if(p<=IMPACT)return 0f;if(p>=.94f)return 1f
        val radius=phase(p,IMPACT,.94f)*1.32f
        val d=hypot(x-.68f,y-.48f)+.025f*field(x,y,p*2)
        return ease((radius-d+.045f)/.09f)
    }
    /** Photo-local soft arrival: x/y, radius relative to short edge, opacity. No off-screen emitter. */
    fun colorVeilSample(seed:Float,u:Float,p:Float,aspect:Float,out:FloatArray){
        require(seed.isFinite() && seed in 0f..1f && u.isFinite() && u in 0f..1f && p.isFinite())
        require(aspect.isFinite() && aspect>0f && out.size>=4)
        val travel=phase(p,.42f,IMPACT)+.18f*phase(p,IMPACT,.78f)
        val t=ease((travel-u*.18f).coerceIn(0f,1f))
        val arc=sin(PI*t).toFloat();val tail=sin(PI*(.12f+.76f*u)).toFloat()
        out[0]=1.025f+(.68f-1.025f)*t
        out[1]=.48f+min(1f,aspect)*(-.09f*arc+(seed-.5f)*.06f*(1-t)*(.6f+.4f*arc))
        out[2]=(.045f+.022f*sin(PI*u).toFloat())*(1-.12f*t)
        out[3]=.07f*phase(p,.36f,.48f)*(1-phase(p,IMPACT,.81f))*tail
    }
    fun stripPoint(seed:Float,u:Float,p:Float,sx:Float,sy:Float,out:FloatArray){
        val strip=phase(p,.16f,.50f);val reach=u*strip;val e=sin(PI*u).toFloat()
        out[0]=sx-(.8f+sx)*reach+.05f*sin(u*13+seed*9+strip*5)*e
        out[1]=sy-.20f*reach+.065f*cos(u*11+seed*12-strip*4)*e
    }
    fun wave(p:Float,band:Int):Float {require(band in 0..3);return ((p-.03f-band*.045f)/.62f).coerceIn(0f,1f)}
    data class FocusTiles(val cols:Int,val rows:Int,val values:FloatArray){
        init{require(cols>0 && rows>0 && cols.toLong()*rows==values.size.toLong());require(values.all{it.isFinite() && it in 0f..1f})}
    }
    /** Display-only lift = in-focus coverage UNION the actual refined subject protection.
     * Does not mutate depth, alpha, focus or the export renderer. Disabled keeps the old path. */
    fun focusTiles(depth:LegendaryMath.Frame,point:LegendaryMath.Point,aperture:Float,w:Int,h:Int,cancel:Cancel,subjectMask:LegendaryMath.Frame?=null,displayGrid:Pair<Int,Int>?=null):FocusTiles? {
        cancel.check();require(w>0 && h>0 && depth.channels==1);FullFrameOptics.checkAperture(aperture)
        subjectMask?.let{require(it.channels==1);for(i in it.data.indices){if(i%it.w==0)cancel.check();require(it.data[i].isFinite() && it.data[i] in 0f..1f)}}
        val focus=LegendaryMath.focus(depth,point)?:return null
        val field=FullFrameOptics.nativeCoc(depth,focus,aperture,w,h,cancel);val accept=FullFrameOptics.acceptableRadius(w,h)
        displayGrid?.let{require(it.first in 1..32 && it.second in 1..32)}
        val (cx,cy)=displayGrid?:DepthVisualMath.grid(w,h,22);val nx=if(displayGrid==null)cx.coerceAtMost(w)else cx;val ny=if(displayGrid==null)cy.coerceAtMost(h)else cy
        val values=FloatArray(nx*ny)
        for(y in 0 until ny)for(x in 0 until nx){
            cancel.check();var n=0f
            for(j in 0..2)for(i in 0..2){
                val px=((x+(i+.5f)/3)/nx*(w-1)).roundToInt();val py=((y+(j+.5f)/3)/ny*(h-1)).roundToInt()
                val coc=abs(FullFrameOptics.sample(field,px,py,w,h))
                val optical=1-ease((coc-accept)/(accept*.65f))
                val protected=subjectMask?.let{FullFrameOptics.sample(it,px,py,w,h).coerceIn(0f,1f)}?:0f
                n+=max(optical,protected)
            }
            values[y*nx+x]=n/9f
        }
        cancel.check();return FocusTiles(nx,ny,values)
    }
    /** Clockwise screen-space side walls face the camera; back walls must not cover photo tiles. */
    fun visibleSide(base:FloatArray,top:FloatArray,edge:Int):Boolean {
        require(base.size==8 && top.size==8 && edge in 0..3)
        require(base.all{it.isFinite()} && top.all{it.isFinite()})
        val i=edge*2;val j=((edge+1)%4)*2
        val ax=base[j]-base[i];val ay=base[j+1]-base[i+1]
        val bx=top[j]-base[i];val by=top[j+1]-base[i+1]
        val cx=top[i]-base[i];val cy=top[i+1]-base[i+1]
        return ax*by-ay*bx+bx*cy-by*cx>1e-4f
    }
    /** Focus surfaces lift; others stay nearly flat. Never used by image export. */
    fun project(x:Float,y:Float,focus:Float,lift:Float,aspect:Float,out:FloatArray){
        require(x.isFinite() && y.isFinite() && focus.isFinite() && lift.isFinite() && lift in 0f..1f && aspect.isFinite() && aspect>0 && out.size>=3)
        val ax=min(1f,aspect);val ay=min(1f,1/aspect)
        val z=(.012f+.32f*focus.coerceIn(0f,1f))*lift
        DepthVisualMath.project((x-.5f)*ax,(y-.5f)*ay,z,.28f*lift,-.30f*lift,out)
    }
}
