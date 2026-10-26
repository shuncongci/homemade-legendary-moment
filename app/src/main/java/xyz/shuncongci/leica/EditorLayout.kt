package xyz.shuncongci.leica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.abs

/** Live-view panel motion; never snapshots the HDR photo into an SDR transition bitmap. */
class EditorLayout(context:Context,private val preview:View,private val controls:View):ViewGroup(context) {
    private var fraction=1f
    private var animator:ValueAnimator?=null
    var expanded=true
        set(value){
            if(field==value)return
            field=value
            val old=animator;animator=null;old?.cancel()
            val target=if(value)1f else 0f
            controls.visibility=VISIBLE
            controls.importantForAccessibility=if(value)IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if(!isLaidOut || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()){settle();return}
            animator=ValueAnimator.ofFloat(fraction,target).apply{
                duration=(280*abs(target-fraction)).toLong().coerceAtLeast(1)
                interpolator=PathInterpolator(.2f,0f,0f,1f)
                addUpdateListener{fraction=it.animatedValue as Float;requestLayout()}
                addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(a:Animator){if(animator===a){animator=null;fraction=target;controls.visibility=if(expanded)VISIBLE else GONE;requestLayout()}}})
                start()
            }
        }
    init{clipChildren=true;addView(preview);addView(controls)}
    fun settle(){val old=animator;animator=null;old?.cancel();fraction=if(expanded)1f else 0f;controls.visibility=if(expanded)VISIBLE else GONE;requestLayout()}
    private fun plan(w:Int,h:Int)=EditorPresentation.panel(w,h,resources.displayMetrics.density,resources.configuration.fontScale,fraction)
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec);val p=plan(w,h)
        preview.measure(MeasureSpec.makeMeasureSpec(p.preview.w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(p.preview.h,MeasureSpec.EXACTLY))
        if(controls.visibility!=GONE)controls.measure(MeasureSpec.makeMeasureSpec(p.controls.w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(p.controls.h,MeasureSpec.EXACTLY))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        val p=plan(r-l,b-t)
        preview.layout(p.preview.x,p.preview.y,p.preview.x+p.preview.w,p.preview.y+p.preview.h)
        if(controls.visibility!=GONE)controls.layout(p.controls.x,p.controls.y,p.controls.x+p.controls.w,p.controls.y+p.controls.h)
    }
    override fun onDetachedFromWindow(){settle();super.onDetachedFromWindow()}
}
