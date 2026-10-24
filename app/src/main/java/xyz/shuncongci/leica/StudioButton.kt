package xyz.shuncongci.leica

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.Canvas
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
    private var sharedMorph=false
    private var surfaceFill=0xff222324.toInt();private var surfaceStroke=0xff373839.toInt()
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
        if(changed && !sharedMorph){captionAnimator?.cancel();if(ValueAnimator.areAnimatorsEnabled())captionAnimator=ValueAnimator.ofFloat(.94f,1f).apply{duration=190;addUpdateListener{textScaleX=it.animatedValue as Float};start()}else textScaleX=1f}
    }
    override fun drawableStateChanged(){
        super.drawableStateChanged();if(!ready)return
        val target=if(isSelected || primary)1f else 0f
        animator?.cancel()
        if(sharedMorph){captionAnimator?.cancel();paintState(target);animate().cancel();alpha=0f;scaleX=1f;scaleY=1f;textScaleX=1f;return}
        if(ValueAnimator.areAnimatorsEnabled())animator=ValueAnimator.ofFloat(fraction,target).apply{duration=220;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{paintState(it.animatedValue as Float)};start()} else paintState(target)
        animate().cancel();animate().scaleX(if(isPressed).965f else 1f).scaleY(if(isPressed).965f else 1f).alpha(if(isEnabled)1f else .34f).setDuration(if(ValueAnimator.areAnimatorsEnabled())160 else 0).start()
    }
    private fun paintState(f:Float){
        fraction=f
        val end=if(primary)0xffc4b8a6.toInt() else 0xffd9d2c4.toInt()
        surfaceFill=ArgbEvaluator().evaluate(f,0xff222324.toInt(),end) as Int
        surfaceStroke=ArgbEvaluator().evaluate(f,0xff373839.toInt(),end) as Int
        shape.setColor(surfaceFill)
        shape.setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),surfaceStroke)
        setTextColor(ArgbEvaluator().evaluate(f,0xffc4c0b8.toInt(),0xff171717.toInt()) as Int)
    }
    internal data class MorphSource(val bitmap:Bitmap,val fill:Int,val stroke:Int,val radius:Float,val strokeWidth:Float)
    internal val morphLightSurface get()=primary || isSelected
    /** Canonical released appearance: cancel the .965 press transform BEFORE measuring/capturing. */
    internal fun beginSharedMorph():MorphSource {
        check(!sharedMorph && width>0 && height>0)
        isPressed=false;animate().cancel();animator?.cancel();captionAnimator?.cancel()
        scaleX=1f;scaleY=1f;textScaleX=1f;alpha=if(isEnabled)1f else .34f
        paintState(if(isSelected || primary)1f else 0f);jumpDrawablesToCurrentState()
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val d=resources.displayMetrics.density
        val source=MorphSource(bitmap,surfaceFill,surfaceStroke,8*d,d.toInt().coerceAtLeast(1).toFloat())
        sharedMorph=true;alpha=0f;return source
    }
    internal fun endSharedMorph(){
        if(!sharedMorph)return
        sharedMorph=false;animate().cancel();animator?.cancel();captionAnimator?.cancel()
        scaleX=1f;scaleY=1f;textScaleX=1f;alpha=if(isEnabled)1f else .34f
        paintState(if(isSelected || primary)1f else 0f);jumpDrawablesToCurrentState()
    }
    override fun onDetachedFromWindow(){animator?.cancel();captionAnimator?.cancel();animate().cancel();super.onDetachedFromWindow()}
}
