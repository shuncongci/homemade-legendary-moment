package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/** A real sliding segment switch: tapping OR swiping changes mode, binding never dispatches work. */
class HdrModeSwitch(context:Context,private val changed:(HdrDevelopMode)->Unit):LinearLayout(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction=0f;private var target=0f;private var animation:ValueAnimator?=null
    private var mode=HdrDevelopMode.SDR
    private var downX=0f;private var swiping=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val options=listOf(HdrDevelopMode.SDR,HdrDevelopMode.HDR)
    private val captions=options.map{value->object:TextView(context){
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.RadioButton";info.isCheckable=true;info.isChecked=mode==value}
    }.apply{
        text=value.name;textSize=12f;gravity=android.view.Gravity.CENTER;minHeight=dp(48);minimumWidth=dp(60)
        setPadding(dp(8),dp(6),dp(8),dp(6));isFocusable=true;isClickable=true
        contentDescription="下次${value.name}显影；只选择，不自动处理；不改变现有成片"
        setOnClickListener{if(isEnabled && mode!=value)changed(value)}
    }}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    init{setWillNotDraw(false);orientation=HORIZONTAL;captions.forEach{addView(it,LayoutParams(0,-2,1f))};contentDescription="下次显影模式，可点选或左右滑动切换 SDR 与 HDR"}
    fun bind(value:HdrDevelopMode,enabled:Boolean){
        require(value!=HdrDevelopMode.AUTO)
        mode=value;isEnabled=enabled;captions.forEachIndexed{i,v->v.isEnabled=enabled;v.isSelected=options[i]==value;v.setTextColor(if(v.isSelected)0xff171819.toInt()else 0xffa9aaa7.toInt());ViewCompat.setStateDescription(v,if(v.isSelected)"已选择"else "未选择")}
        val next=if(value==HdrDevelopMode.HDR)1f else 0f
        if(target==next)return
        target=next;animation?.cancel()
        if(!isLaidOut || !ValueAnimator.areAnimatorsEnabled()){fraction=target;invalidate();return}
        animation=ValueAnimator.ofFloat(fraction,target).apply{duration=240;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{fraction=it.animatedValue as Float;invalidate()};start()}
    }
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density;paint.color=0xff26282a.toInt();c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),14*d,14*d,paint)
        val half=(width-4*d)/2;val x=2*d+half*fraction;paint.color=0xffdedbd3.toInt();c.drawRoundRect(x,2*d,x+half,height-2*d,12*d,12*d,paint)
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(!isEnabled)return false
        if(e.actionMasked==MotionEvent.ACTION_DOWN){downX=e.x;swiping=false}
        if(e.actionMasked==MotionEvent.ACTION_MOVE && kotlin.math.abs(e.x-downX)>slop){swiping=true;parent?.requestDisallowInterceptTouchEvent(true);return true}
        return super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=e.x;return true}
            MotionEvent.ACTION_MOVE->{if(kotlin.math.abs(e.x-downX)>slop)swiping=true;return true}
            MotionEvent.ACTION_UP->{val value=WorkspaceMotion.modeForGesture(downX,e.x,width.toFloat(),swiping);if(value!=mode)changed(value);swiping=false;parent?.requestDisallowInterceptTouchEvent(false);performClick();return true}
            MotionEvent.ACTION_CANCEL->{swiping=false;parent?.requestDisallowInterceptTouchEvent(false);return true}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onDetachedFromWindow(){animation?.cancel();fraction=target;super.onDetachedFromWindow()}
}
