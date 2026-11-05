package xyz.shuncongci.leica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import kotlin.math.roundToInt

/** Shared live-view fullscreen in the SAME decor. No Dialog, bar switch, photo snapshot or alpha layer.
 * A placeholder preserves the editor's geometry. Only this overlay relayouts during animation. */
class FullscreenOverlay(private val host:ComponentActivity,private val content:View,titleText:String,
                        private val lower:View?=null,private val restored:()->Unit={} ) {
    private val decor=host.window.decorView as ViewGroup
    private val source=content.parent as ViewGroup
    private val sourceIndex=source.indexOfChild(content)
    private val sourceParams=content.layoutParams
    private val placeholder=View(host).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val state=ExportMotion.State()
    private var animator:ValueAnimator?=null
    private var disposed=false
    private var mounted=false
    private var origin=ExportMotion.Rect(0f,0f,1f,1f)
    private var target=origin
    private var current=origin
    private val accessibility=mutableListOf<Pair<View,Int>>()
    private val previousFocus=decor.findFocus()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val viewport=FrameLayout(host).apply{
        setBackgroundColor(0xff0d0e0f.toInt());clipToOutline=true
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,18*resources.displayMetrics.density*(1-state.progress))}}
    }
    val title=TextView(host).apply{text=titleText;textSize=14f;setTextColor(0xffeee8dd.toInt());gravity=Gravity.CENTER;maxLines=2}
    val toolbar=LinearLayout(host).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(8),0)}
    var onClosing:()->Unit={}
    var onFocus:(Boolean)->Unit={}
    var requestClose:()->Unit={close()}
    private val back=object:OnBackPressedCallback(true){override fun handleOnBackPressed(){requestClose()}}
    private val layer=object:ViewGroup(host){
        init{setWillNotDraw(false);isClickable=true;isFocusableInTouchMode=true;clipChildren=false;clipToPadding=false}
        override fun onMeasure(wSpec:Int,hSpec:Int){
            val w=MeasureSpec.getSize(wSpec);val h=MeasureSpec.getSize(hSpec);setMeasuredDimension(w,h)
            if(w<=0 || h<=0)return
            val offset=if(width>0 && height>0)ExportUi.inDecor(this,decor)else ExportMotion.Rect(0f,0f,1f,1f)
            val insets=ViewCompat.getRootWindowInsets(decor)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val safe=ExportMotion.Rect(maxOf(0f,(insets?.left?:0)-offset.left),maxOf(0f,(insets?.top?:0)-offset.top),
                minOf(w.toFloat(),decor.width-(insets?.right?:0)-offset.left),minOf(h.toFloat(),decor.height-(insets?.bottom?:0)-offset.top))
            val exact=MeasureSpec.makeMeasureSpec(safe.width.roundToInt(),MeasureSpec.EXACTLY)
            toolbar.measure(exact,MeasureSpec.makeMeasureSpec((safe.height*.25f).toInt(),MeasureSpec.AT_MOST))
            lower?.measure(exact,MeasureSpec.makeMeasureSpec((safe.height*.35f).toInt(),MeasureSpec.AT_MOST))
            target=FullscreenMotion.viewport(safe,toolbar.measuredHeight.toFloat(),lower?.measuredHeight?.toFloat()?:0f)
            if(placeholder.isAttachedToWindow && placeholder.width>0 && placeholder.height>0)origin=ExportUi.inDecor(placeholder,decor).offset(-offset.left,-offset.top)
            current=FullscreenMotion.frame(origin,target,state.progress)
            viewport.measure(MeasureSpec.makeMeasureSpec(current.width.roundToInt().coerceAtLeast(1),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(current.height.roundToInt().coerceAtLeast(1),MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
            val x=current.left.roundToInt();val y=current.top.roundToInt()
            viewport.layout(x,y,x+viewport.measuredWidth,y+viewport.measuredHeight)
            toolbar.layout(target.left.roundToInt(),(target.top-toolbar.measuredHeight).roundToInt(),target.right.roundToInt(),target.top.roundToInt())
            lower?.layout(target.left.roundToInt(),target.bottom.roundToInt(),target.right.roundToInt(),target.bottom.roundToInt()+lower.measuredHeight)
            val a=WorkspaceMotion.content(state.progress);toolbar.alpha=a;lower?.alpha=a;viewport.invalidateOutline()
        }
        override fun onDraw(c:Canvas){paint.color=0xff101112.toInt();paint.alpha=(255*state.progress).roundToInt();c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)}
        override fun onInterceptTouchEvent(e:MotionEvent)=state.phase!=ExportMotion.Phase.OPEN || super.onInterceptTouchEvent(e)
        override fun onTouchEvent(e:MotionEvent)=true
        override fun onWindowFocusChanged(focus:Boolean){super.onWindowFocusChanged(focus);onFocus(focus && state.phase==ExportMotion.Phase.OPEN)}
        override fun onDetachedFromWindow(){super.onDetachedFromWindow();if(!disposed)dismiss()}
    }
    val isShowing get()=mounted && !disposed
    val isClosing get()=state.phase==ExportMotion.Phase.CLOSING
    private fun dp(n:Int)=(n*host.resources.displayMetrics.density).roundToInt()
    init{
        val exit=StudioButton(host).apply{text="退出";contentDescription="退出全屏预览";setOnClickListener{requestClose()}}
        toolbar.addView(exit,LinearLayout.LayoutParams(-2,-2));toolbar.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        layer.addView(viewport);layer.addView(toolbar);lower?.let{layer.addView(it)}
        ViewCompat.setAccessibilityPaneTitle(layer,"$titleText · 全屏")
        layer.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_BACK){if(event.action==KeyEvent.ACTION_UP)requestClose();true}else false}
    }
    private fun transfer(parent:ViewGroup,params:ViewGroup.LayoutParams,index:Int=-1){
        if(content is DepthStageView)content.rehost(parent,params,index)
        else{(content.parent as? ViewGroup)?.removeView(content);parent.addView(content,index,params)}
    }
    fun show(){
        check(!mounted && !disposed && content.isAttachedToWindow)
        // Cancel a partially completed gesture without changing zoom, split or camera pose.
        val cancel=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,0f,0f,0)
        content.dispatchTouchEvent(cancel);cancel.recycle()
        origin=ExportUi.inDecor(content,decor)
        try{
            transfer(viewport,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
            source.addView(placeholder,sourceIndex.coerceIn(0,source.childCount),sourceParams)
            for(i in 0 until decor.childCount){val v=decor.getChildAt(i);accessibility+=v to v.importantForAccessibility;v.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
            mounted=true;decor.addView(layer,ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));host.onBackPressedDispatcher.addCallback(host,back)
            layer.doOnPreDraw{if(isShowing && state.phase==ExportMotion.Phase.NEW){layer.requestFocus();animate(state.open()){onFocus(layer.hasWindowFocus())}}}
        }catch(t:Throwable){dismiss();throw t}
    }
    private fun animate(run:ExportMotion.Run,done:()->Unit){
        val old=animator;animator=null;old?.cancel()
        fun render(value:Float){if(state.accept(run,value)){layer.requestLayout();layer.invalidate()}}
        if(!ValueAnimator.areAnimatorsEnabled() || run.from==run.to){render(run.to);if(state.complete(run))done();return}
        animator=ValueAnimator.ofFloat(run.from,run.to).apply{
            duration=WorkspaceMotion.duration(run.from,run.to);interpolator=TimeInterpolator{run.ease(it)}
            addUpdateListener{render(it.animatedValue as Float)}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(a:Animator){if(animator===a && state.complete(run)){animator=null;layer.requestLayout();done()}}})
            start()
        }
    }
    fun close(){
        if(!isShowing)return
        val run=state.close()?:return
        onClosing();animate(run){dismiss()}
    }
    /** Rotation/lifecycle cancellation restores ownership exactly once; never starts a photo operation. */
    fun resize(){if(isClosing)dismiss()else layer.requestLayout()}
    fun dismiss(){
        if(disposed)return
        disposed=true;state.dismiss();val old=animator;animator=null;old?.cancel();back.remove()
        if(content.parent===viewport){
            val index=source.indexOfChild(placeholder).takeIf{it>=0}?:sourceIndex.coerceIn(0,source.childCount)
            if(placeholder.parent===source)source.removeView(placeholder)
            transfer(source,sourceParams,index)
        }
        if(layer.parent===decor)decor.removeView(layer)
        accessibility.forEach{(v,value)->v.importantForAccessibility=value};accessibility.clear();mounted=false
        if(previousFocus?.isAttachedToWindow==true)previousFocus.requestFocus()
        restored()
    }
}
