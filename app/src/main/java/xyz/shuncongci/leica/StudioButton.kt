package xyz.shuncongci.leica

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatButton

class StudioButton(context:Context,private val primary:Boolean=false):AppCompatButton(context) {
    private var ready=false
    private var animator:ValueAnimator?=null
    private var captionAnimator:ValueAnimator?=null
    private var fraction=0f
    private val shape=GradientDrawable()
    init {
        val d=resources.displayMetrics.density
        isAllCaps=false;textSize=12f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);letterSpacing=.035f
        minHeight=(48*d).toInt();minimumHeight=minHeight;minWidth=0;minimumWidth=0;gravity=Gravity.CENTER
        setPadding((12*d).toInt(),(9*d).toInt(),(12*d).toInt(),(9*d).toInt());stateListAnimator=null
        shape.cornerRadius=8*d
        background=RippleDrawable(ColorStateList.valueOf(0x28ffffff),shape,null)
        ready=true;paintState(if(primary)1f else 0f)
    }
    override fun setText(value:CharSequence?,type:android.widget.TextView.BufferType?){
        val changed=ready && text?.toString()!=value?.toString()
        super.setText(value,type)
        if(changed){captionAnimator?.cancel();if(ValueAnimator.areAnimatorsEnabled())captionAnimator=ValueAnimator.ofFloat(.94f,1f).apply{duration=190;addUpdateListener{textScaleX=it.animatedValue as Float};start()}else textScaleX=1f}
    }
    override fun drawableStateChanged(){
        super.drawableStateChanged();if(!ready)return
        val target=if(isSelected || primary)1f else 0f
        animator?.cancel()
        if(ValueAnimator.areAnimatorsEnabled())animator=ValueAnimator.ofFloat(fraction,target).apply{duration=220;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{paintState(it.animatedValue as Float)};start()} else paintState(target)
        animate().cancel();animate().scaleX(if(isPressed).965f else 1f).scaleY(if(isPressed).965f else 1f).alpha(if(isEnabled)1f else .34f).setDuration(if(ValueAnimator.areAnimatorsEnabled())160 else 0).start()
    }
    private fun paintState(f:Float){
        fraction=f
        val end=if(primary)0xffc4b8a6.toInt() else 0xffd9d2c4.toInt()
        shape.setColor(ArgbEvaluator().evaluate(f,0xff222324.toInt(),end) as Int)
        shape.setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),ArgbEvaluator().evaluate(f,0xff373839.toInt(),end) as Int)
        setTextColor(ArgbEvaluator().evaluate(f,0xffc4c0b8.toInt(),0xff171717.toInt()) as Int)
    }
    override fun onDetachedFromWindow(){animator?.cancel();captionAnimator?.cancel();animate().cancel();super.onDetachedFromWindow()}
}
