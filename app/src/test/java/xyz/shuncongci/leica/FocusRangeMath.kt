package xyz.shuncongci.leica

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** User-controlled relative-depth HALF width, not metres, lens calibration or semantic protection.
 * 0 preserves the pre-slider renderer exactly. 10000 covers the entire normalized depth interval.
 * Aperture multiplies only the residual outside this interval; it never changes its endpoints.
 */
object FocusRangeMath {
    const val MAX_STEPS=10000
    fun checkSteps(steps:Int) { require(steps in 0..MAX_STEPS) { "焦内范围须为 0.00–100.00%" } }
    fun halfWidth(steps:Int):Float {checkSteps(steps);return steps/MAX_STEPS.toFloat()}
    fun percent(steps:Int):String {checkSteps(steps);return "${steps/100}.${(steps%100).toString().padStart(2,'0')}"}
    fun parsePercent(text:String):Int? = try {
        val raw=text.trim().removeSuffix("%").trim()
        if(!Regex("(?:\\d{1,3}(?:\\.\\d{0,2})?|\\.\\d{1,2})").matches(raw))null else {
            val n=BigDecimal(raw).movePointRight(2).intValueExact()
            if(n in 0..MAX_STEPS)n else null
        }
    } catch(_:NumberFormatException){null} catch(_:ArithmeticException){null}
    fun interval(focus:Float,steps:Int):Pair<Float,Float> {
        require(focus.isFinite() && focus in 0f..1f);val r=halfWidth(steps)
        return max(0f,focus-r) to min(1f,focus+r)
    }
    internal class Field(val native:LegendaryMath.Frame,val steps:Int,val gain:Float) {
        private val radius=halfWidth(steps)
        fun encode(sample:Float):Float = if(steps==0)sample else sign(sample)*max(0f,abs(sample)-radius)*gain
    }
    internal fun field(depth:LegendaryMath.Frame,focus:Float,aperture:Float,steps:Int,cancel:Cancel):Field {
        cancel.check();checkSteps(steps);require(depth.channels==1 && focus.isFinite() && focus in 0f..1f)
        val g=LegendaryMath.gain(aperture);val a=FloatArray(depth.size)
        for(y in 0 until depth.h){cancel.check();for(i in y*depth.w until (y+1)*depth.w){
            val d=depth.data[i];require(d.isFinite() && d in 0f..1f)
            // Keep the old multiply-before-resize order at zero width for exact compatibility.
            a[i]=if(steps==0)(d-focus)*g else d-focus
        }}
        return Field(LegendaryMath.Frame(depth.w,depth.h,1,a),steps,g)
    }
    fun coc(depth:LegendaryMath.Frame,focus:Float,aperture:Float,w:Int,h:Int,steps:Int,cancel:Cancel):LegendaryMath.Frame {
        require(w>0 && h>0)
        val f=field(depth,focus,aperture,steps,cancel)
        val out=LegendaryMath.resize(f.native,w,h,cancel=cancel)
        if(steps!=0)for(y in 0 until h){cancel.check();for(i in y*w until (y+1)*w)out.data[i]=f.encode(out.data[i])}
        cancel.check();return out
    }
    /** Exact output pixel coordinates for the UI sampling layer; no full-resolution allocation. */
    internal fun displayPixel(index:Int,displaySize:Int,outputSize:Int):Int =
        (((index+.5)*outputSize/displaySize).toInt()).coerceIn(0,outputSize-1)
    internal fun sample(f:Field,x:Int,y:Int,w:Int,h:Int):Float {
        val n=f.native
        if(w==n.w && h==n.h)return f.encode(n.data[y*n.w+x])
        val sy=(y+.5f)*n.h/h-.5f;val iy=floor(sy).toInt();val fy=sy-iy
        val sx=(x+.5f)*n.w/w-.5f;val ix=floor(sx).toInt();val fx=sx-ix;var sum=0f
        // Same half-pixel clamp and operation order as LegendaryMath.resize.
        for(dy in 0..1)for(dx in 0..1)sum+=n.data[(iy+dy).coerceIn(0,n.h-1)*n.w+(ix+dx).coerceIn(0,n.w-1)]*(if(dy==0)1-fy else fy)*(if(dx==0)1-fx else fx)
        return f.encode(sum)
    }
}

