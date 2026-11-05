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

open class StudioButton(context:Context,private val primary:Boolean=false):AppCompatButton(context) {
    private var ready=false
    private var animator:ValueAnimator?=null
    private var captionAnimator:ValueAnimator?=null
    private var fraction=0f
    private var lastTarget=Float.NaN
    private var sharedMorph=false
    /** Parameter overlays own this entry until their closing frame; state rebinds cannot reveal it. */
    internal var panelSourceHidden=false
        set(value){field=value;animate().cancel();alpha=if(value || sharedMorph)0f else if(isEnabled)1f else .34f}
    private var surfaceFill=0xff222324.toInt();private var surfaceStroke=0xff373839.toInt()
    private val shape=GradientDrawable()
    /** Optional chrome geometry; the original paint/press/caption timings remain unchanged. */
    var cornerRadiusDp=12f
        set(value){require(value>=0f && value.isFinite());field=value;shape.cornerRadius=value*resources.displayMetrics.density;invalidate()}
    var quietSelection=false
        set(value){field=value;if(ready)paintState(fraction)}
    var borderVisible=true
        set(value){field=value;if(ready)paintState(fraction)}
    init {
        val d=resources.displayMetrics.density
        isAllCaps=false;textSize=12f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);letterSpacing=.035f
        minHeight=(48*d).toInt();minimumHeight=minHeight;minWidth=0;minimumWidth=0;gravity=Gravity.CENTER
        setPadding((12*d).toInt(),(9*d).toInt(),(12*d).toInt(),(9*d).toInt());stateListAnimator=null
        shape.cornerRadius=cornerRadiusDp*d
        background=RippleDrawable(ColorStateList.valueOf(0x28ffffff),shape,null)
        ready=true;paintState(if(primary)1f else 0f)
    }
    override fun setText(value:CharSequence?,type:android.widget.TextView.BufferType?){
        if(ready && text?.toString()==value?.toString())return
        val changed=ready && text?.toString()!=value?.toString()
        super.setText(value,type)
        if(changed){captionAnimator?.cancel();textScaleX=1f} // Stable type: state refresh is not a new expand animation.
    }
    override fun drawableStateChanged(){
        super.drawableStateChanged();if(!ready)return
        val target=if(isSelected || primary)1f else 0f
        if(sharedMorph){captionAnimator?.cancel();paintState(target);animate().cancel();alpha=0f;scaleX=1f;scaleY=1f;textScaleX=1f;return}
        if(lastTarget!=target){
            lastTarget=target;animator?.cancel()
            if(ValueAnimator.areAnimatorsEnabled())animator=ValueAnimator.ofFloat(fraction,target).apply{duration=220;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{paintState(it.animatedValue as Float)};start()}else paintState(target)
        }
        // Ripple/color feedback only. Rebinding state never squeezes text or pulses the whole control.
        animate().cancel();scaleX=1f;scaleY=1f;alpha=if(panelSourceHidden)0f else if(isEnabled)1f else .34f
    }
    private fun paintState(f:Float){
        fraction=f
        val end=if(primary)0xffc4b8a6.toInt() else 0xffd9d2c4.toInt()
        surfaceFill=ArgbEvaluator().evaluate(f,0xff222324.toInt(),if(quietSelection)0xff34312b.toInt()else end) as Int
        surfaceStroke=ArgbEvaluator().evaluate(f,0xff373839.toInt(),end) as Int
        shape.setColor(surfaceFill)
        shape.setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),if(borderVisible)surfaceStroke else Color.TRANSPARENT)
        setTextColor(ArgbEvaluator().evaluate(f,0xffc4c0b8.toInt(),if(quietSelection)0xffd9d2c4.toInt()else 0xff171717.toInt()) as Int)
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
        val source=MorphSource(bitmap,surfaceFill,surfaceStroke,cornerRadiusDp*d,d.toInt().coerceAtLeast(1).toFloat())
        sharedMorph=true;alpha=0f;return source
    }
    internal fun endSharedMorph(){
        if(!sharedMorph)return
        sharedMorph=false;animate().cancel();animator?.cancel();captionAnimator?.cancel()
        scaleX=1f;scaleY=1f;textScaleX=1f;alpha=if(panelSourceHidden)0f else if(isEnabled)1f else .34f
        paintState(if(isSelected || primary)1f else 0f);jumpDrawablesToCurrentState()
    }
    override fun onDetachedFromWindow(){animator?.cancel();captionAnimator?.cancel();animate().cancel();super.onDetachedFromWindow()}
}
