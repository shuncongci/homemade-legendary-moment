package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.*

class ApertureDial(context:Context):View(context) {
    private var aperture=2.8f;private var fraction=.5f;private var animator:ValueAnimator?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    fun setAperture(value:Float){
        FullFrameOptics.checkAperture(value);aperture=value;contentDescription="光圈$value"
        val target=(ln(value/.95f)/ln(5.6f/.95f)).coerceIn(0f,1f)
        animator?.cancel()
        if(!ValueAnimator.areAnimatorsEnabled()){fraction=target;invalidate();return}
        animator=ValueAnimator.ofFloat(fraction,target).apply{duration=420;interpolator=PathInterpolator(.16f,1f,.3f,1f);addUpdateListener{fraction=it.animatedValue as Float;invalidate()};start()}
    }
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density;val radius=min(height*.4f,43*d);val cx=width-radius-12*d;val cy=height/2f
        val showIris=width>=320*d && resources.configuration.fontScale<=1.3f
        paint.style=Paint.Style.STROKE;paint.strokeWidth=d;paint.color=0xff444342.toInt();if(showIris)c.drawCircle(cx,cy,radius+4*d,paint)
        val inner=radius*(.67f-.40f*fraction);val rotation=fraction*.45
        if(showIris)for(i in 0..8){
            val a=i*2*PI/9+rotation;val b=a+2*PI/9
            path.rewind();path.moveTo(cx+(cos(a)*inner).toFloat(),cy+(sin(a)*inner).toFloat())
            path.lineTo(cx+(cos(b)*inner).toFloat(),cy+(sin(b)*inner).toFloat())
            path.lineTo(cx+(cos(b+.65)*radius).toFloat(),cy+(sin(b+.65)*radius).toFloat())
            path.lineTo(cx+(cos(a+.65)*radius).toFloat(),cy+(sin(a+.65)*radius).toFloat());path.close()
            paint.style=Paint.Style.FILL;paint.color=0xff292a2b.toInt();c.drawPath(path,paint);paint.style=Paint.Style.STROKE;paint.color=0xff69645c.toInt();c.drawPath(path,paint)
        }
        paint.style=Paint.Style.FILL;paint.typeface=Typeface.create("serif",Typeface.NORMAL);paint.textSize=36*resources.displayMetrics.scaledDensity;paint.color=0xffeee8dd.toInt();c.drawText("$aperture",4*d,cy+5*d,paint)
        paint.typeface=Typeface.MONOSPACE;paint.textSize=10*resources.displayMetrics.scaledDensity;paint.color=0xff9d9890.toInt();c.drawText("光圈",6*d,cy+28*d,paint)
    }
    override fun onDetachedFromWindow(){animator?.cancel();super.onDetachedFromWindow()}
}
