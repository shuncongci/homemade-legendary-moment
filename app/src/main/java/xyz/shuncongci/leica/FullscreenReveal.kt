package xyz.shuncongci.leica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/** Live full-size content, clipped by four edges from its real trigger. No photo snapshot or scale transform. */
class FullscreenReveal(context:Context):FrameLayout(context) {
    private var anchor:StudioButton?=null
    private var source:StudioButton.MorphSource?=null
    private var origin=ExportMotion.Rect(0f,0f,1f,1f)
    private var progress=0f
    private var animator:ValueAnimator?=null
    private val path=Path();private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val here=IntArray(2);private val there=IntArray(2)
    init{clipChildren=false;clipToPadding=false;setWillNotDraw(false)}
    fun capture(button:StudioButton){check(source==null);anchor=button;source=button.beginSharedMorph();progress=0f}
    private fun sync(){
        val button=anchor?:return
        if(button.isAttachedToWindow){getLocationOnScreen(here);button.getLocationOnScreen(there)
            val x=(there[0]-here[0]).toFloat();val y=(there[1]-here[1]).toFloat()
            origin=ExportMotion.Rect(x,y,x+button.width,y+button.height)
        }
    }
    fun animateTo(open:Boolean,done:()->Unit={}){
        val old=animator;animator=null;old?.cancel();sync();val target=if(open)1f else 0f
        if(!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()){progress=target;invalidate();done();return}
        animator=ValueAnimator.ofFloat(progress,target).apply{
            duration=WorkspaceMotion.duration(progress,target);interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{progress=it.animatedValue as Float;invalidate()}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){if(animator===animation){animator=null;progress=target;done()}}})
            start()
        }
    }
    fun finish(){val old=animator;animator=null;old?.cancel();anchor?.endSharedMorph();anchor=null;source?.bitmap?.recycle();source=null}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);sync()}
    override fun onDraw(c:Canvas){
        if(width<=0 || height<=0)return
        val r=ExportMotion.mix(origin,ExportMotion.Rect(0f,0f,width.toFloat(),height.toFloat()),progress)
        val radius=(source?.radius?:18f)*(1-progress)
        paint.color=androidx.core.graphics.ColorUtils.blendARGB(source?.fill?:Color.BLACK,0xff101112.toInt(),progress);paint.alpha=255
        c.drawRoundRect(RectF(r.left,r.top,r.right,r.bottom),radius,radius,paint)
    }
    override fun dispatchDraw(c:Canvas){
        if(width<=0 || height<=0)return
        val r=ExportMotion.mix(origin,ExportMotion.Rect(0f,0f,width.toFloat(),height.toFloat()),progress)
        val radius=(source?.radius?:18f)*(1-progress)
        path.rewind();path.addRoundRect(RectF(r.left,r.top,r.right,r.bottom),radius,radius,Path.Direction.CW)
        val n=c.save();c.clipPath(path)
        // Alpha layer is bounded to this window and only used while transitioning; the stage stays live.
        val a=if(progress<1f)c.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(WorkspaceMotion.content(progress)*255).toInt())else -1
        super.dispatchDraw(c);if(a>=0)c.restoreToCount(a);c.restoreToCount(n)
        source?.let{s->paint.alpha=(ExportMotion.ghost(progress)*255).toInt();if(paint.alpha>0)c.drawBitmap(s.bitmap,null,RectF(origin.left,origin.top,origin.right,origin.bottom),paint)}
    }
    override fun onInterceptTouchEvent(e:MotionEvent)=animator!=null || super.onInterceptTouchEvent(e)
    override fun onTouchEvent(e:MotionEvent)=animator!=null || super.onTouchEvent(e)
    override fun onDetachedFromWindow(){finish();super.onDetachedFromWindow()}
}
