package xyz.shuncongci.leica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Live overlay. The photo ALWAYS owns the full viewport; closing reserves zero panel pixels.
 * Content is measured once at its final size, then revealed by four-edge clipping from the real trigger. */
class EditorLayout(context:Context,private val preview:View,private val controls:View):ViewGroup(context) {
    private var fraction=1f
    private var animator:ValueAnimator?=null
    var anchor:View?=null
        set(value){if(field===value)return;releaseTrigger();field=value;syncTrigger()}
    private var hiddenTrigger:View?=null
    private var triggerAccessibility=IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var triggerClickable=false
    private var triggerFocusable=false
    private var triggerAlpha=1f
    private var origin:ExportMotion.Rect?=null
    private var captured=false
    private var closeCompletion:(()->Unit)?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xff1c1d1f.toInt()}
    private val path=Path()
    var expanded=true
        set(value){
            if(field==value)return
            if(!captured)captureOrigin(anchor);field=value
            animateTo(if(value)1f else 0f)
        }
    /** A different panel (e.g. composition) starts at its own action, not at the screen centre. */
    fun revealFrom(trigger:View){captureOrigin(trigger);revealCaptured()}
    fun captureTrigger(trigger:View){captureOrigin(trigger);captured=true}
    fun revealCaptured(){captured=false;fraction=0f;animateTo(1f)}
    /** Keep the existing panel content alive until it has returned to its entry. */
    fun close(after:()->Unit){
        if(closeCompletion!=null)return
        closeCompletion=after
        if(expanded)expanded=false else if(animator==null)finishClose()
    }
    private fun finishClose(){if(!expanded){val done=closeCompletion;closeCompletion=null;done?.invoke()}}
    private fun captureOrigin(trigger:View?){
        if(trigger?.isAttachedToWindow!=true || !trigger.isShown || trigger.width<=0 || trigger.height<=0)return
        val decor=rootView as? ViewGroup?:return
        val self=ExportUi.inDecor(this,decor);origin=ExportUi.inDecor(trigger,decor).offset(-self.left,-self.top)
    }
    private fun syncTrigger(){
        if(!MaintenanceUiPolicy.hideParameterSource(expanded,animator!=null || fraction>0f)){releaseTrigger();return}
        val trigger=anchor?:return
        if(hiddenTrigger===trigger)return
        releaseTrigger();hiddenTrigger=trigger
        triggerAccessibility=trigger.importantForAccessibility;triggerClickable=trigger.isClickable
        triggerFocusable=trigger.isFocusable;triggerAlpha=trigger.alpha
        trigger.isPressed=false;trigger.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        trigger.isClickable=false;trigger.isFocusable=false
        if(trigger is StudioButton)trigger.panelSourceHidden=true else trigger.alpha=0f
    }
    private fun releaseTrigger(){
        val trigger=hiddenTrigger?:return;hiddenTrigger=null
        if(trigger is StudioButton)trigger.panelSourceHidden=false else trigger.alpha=triggerAlpha
        trigger.importantForAccessibility=triggerAccessibility;trigger.isClickable=triggerClickable;trigger.isFocusable=triggerFocusable
    }
    private fun animateTo(target:Float){
        val old=animator;animator=null;old?.cancel()
        controls.visibility=VISIBLE
        controls.importantForAccessibility=if(expanded)IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        requestLayout()
        if(!isLaidOut || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()){settle();return}
        syncTrigger()
        animator=ValueAnimator.ofFloat(fraction,target).apply{
            duration=WorkspaceMotion.duration(fraction,target);interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{fraction=it.animatedValue as Float;invalidate()}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(a:Animator){if(animator===a){animator=null;fraction=target;controls.visibility=if(expanded)VISIBLE else GONE;requestLayout();invalidate();syncTrigger();finishClose()}}})
            start()
        }
    }
    init{clipChildren=false;clipToPadding=false;addView(preview);addView(controls)}
    fun settle(){val old=animator;animator=null;old?.cancel();fraction=if(expanded)1f else 0f;controls.visibility=if(expanded)VISIBLE else GONE;controls.alpha=1f;requestLayout();invalidate();syncTrigger();finishClose()}
    private fun plan(w:Int,h:Int)=WorkspaceMotion.panel(w,h,resources.displayMetrics.density,resources.configuration.fontScale)
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec);val p=plan(w,h)
        preview.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
        if(controls.visibility!=GONE)controls.measure(MeasureSpec.makeMeasureSpec(p.controls.w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(p.controls.h,MeasureSpec.EXACTLY))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        val p=plan(r-l,b-t)
        preview.layout(0,0,r-l,b-t)
        val c=p.controls;if(controls.visibility!=GONE)controls.layout(c.x,c.y,c.x+c.w,c.y+c.h)
    }
    override fun dispatchDraw(c:Canvas){
        drawChild(c,preview,drawingTime)
        if(controls.visibility==GONE || fraction<=0 || width<=0 || height<=0)return
        val p=plan(width,height).controls
        if(p.w<=0 || p.h<=0)return
        val target=ExportMotion.Rect(p.x.toFloat(),p.y.toFloat(),(p.x+p.w).toFloat(),(p.y+p.h).toFloat())
        val start=origin?:ExportMotion.Rect((width-48*resources.displayMetrics.density).coerceAtLeast(0f),0f,width.toFloat(),48*resources.displayMetrics.density)
        val r=ExportMotion.mix(start,target,fraction);val radius=18*resources.displayMetrics.density
        val bounds=RectF(r.left,r.top,r.right,r.bottom)
        c.drawRoundRect(bounds,radius,radius,paint)
        path.rewind();path.addRoundRect(bounds,radius,radius,Path.Direction.CW)
        val saved=c.save();c.clipPath(path);controls.alpha=WorkspaceMotion.content(fraction);drawChild(c,controls,drawingTime);c.restoreToCount(saved)
    }
    override fun onInterceptTouchEvent(event:android.view.MotionEvent)=animator!=null || super.onInterceptTouchEvent(event)
    override fun onTouchEvent(event:android.view.MotionEvent)=animator!=null || super.onTouchEvent(event)
    override fun onAttachedToWindow(){super.onAttachedToWindow();syncTrigger()}
    override fun onDetachedFromWindow(){settle();releaseTrigger();super.onDetachedFromWindow()}
}
