package xyz.shuncongci.leica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Measure each real row at its final size, then reveal/translate without scaling its text.
 * Both directions share one fraction, so a reverse switch starts at the currently visible frame. */
internal class CompositionActionDock(context:Context,private val normal:View,private val composition:View):ViewGroup(context) {
    private val state=CompositionActionMotion.State()
    private var animator:ValueAnimator?=null
    init {addView(normal);addView(composition);clipChildren=true;clipToPadding=true}
    fun bind(composing:Boolean){
        when(state.bind(composing,isLaidOut && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled())){
            CompositionActionMotion.Change.UNCHANGED->return
            CompositionActionMotion.Change.SETTLE->settle()
            CompositionActionMotion.Change.ANIMATE->{
                val old=animator;animator=null;old?.cancel()
                clearFocus();normal.clearFocus();composition.clearFocus()
                normal.visibility=VISIBLE;composition.visibility=VISIBLE
                normal.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                composition.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val target=state.target
                animator=ValueAnimator.ofFloat(state.fraction,target).apply{
                    duration=CompositionActionMotion.duration(state.fraction,target)
                    interpolator=PathInterpolator(.2f,0f,0f,1f)
                    addUpdateListener{state.advance(it.animatedValue as Float);requestLayout();invalidate()}
                    addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(a:Animator){if(animator===a){animator=null;settle()}}})
                    start()
                }
                requestLayout();invalidate()
            }
        }
    }
    fun settle(){
        val old=animator;animator=null;old?.cancel();state.settle()
        normal.visibility=if(state.composing)GONE else VISIBLE
        composition.visibility=if(state.composing)VISIBLE else GONE
        normal.alpha=1f;composition.alpha=1f
        normal.importantForAccessibility=if(state.composing)IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        composition.importantForAccessibility=if(state.composing)IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        requestLayout();invalidate()
    }
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val w=MeasureSpec.getSize(widthSpec)
        val childWidth=MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY)
        val childHeight=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        // Even the hidden row has a current target height after font/window/content changes.
        normal.measure(childWidth,childHeight);composition.measure(childWidth,childHeight)
        val h=CompositionActionMotion.height(normal.measuredHeight,composition.measuredHeight,state.fraction)
        setMeasuredDimension(resolveSize(w,widthSpec),resolveSize(h,heightSpec))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        normal.layout(0,height-normal.measuredHeight,width,height)
        composition.layout(0,height-composition.measuredHeight,width,height)
    }
    override fun dispatchDraw(c:Canvas){
        if(animator==null){super.dispatchDraw(c);return}
        val f=CompositionActionMotion.frame(state.fraction);val d=resources.displayMetrics.density
        fun draw(view:View,alpha:Float,offset:Float){
            if(alpha<=0f)return
            view.alpha=alpha
            val save=c.save();c.clipRect(0,0,width,height);c.translate(0f,offset*d)
            drawChild(c,view,drawingTime);c.restoreToCount(save)
        }
        draw(normal,f.normalAlpha,f.normalOffset);draw(composition,f.compositionAlpha,f.compositionOffset)
    }
    override fun onInterceptTouchEvent(e:MotionEvent)=animator!=null || super.onInterceptTouchEvent(e)
    override fun onTouchEvent(e:MotionEvent)=animator!=null || super.onTouchEvent(e)
    override fun dispatchKeyEvent(e:KeyEvent)=if(animator!=null && e.keyCode!=KeyEvent.KEYCODE_BACK)true else super.dispatchKeyEvent(e)
    override fun onDetachedFromWindow(){settle();super.onDetachedFromWindow()}
}
