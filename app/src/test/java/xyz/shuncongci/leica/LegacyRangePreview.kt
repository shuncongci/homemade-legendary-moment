package xyz.shuncongci.leica

import kotlin.math.abs

/** UI-only estimation in output CoC pixels; never fed to the renderer or PNG writer. */
object LegacyRangePreview {
    const val NEAR_ARGB: Int = 0x663FDC9C
    const val TRANSITION_ARGB: Int = 0x55FFD166
    data class Overlay(val width:Int,val height:Int,val argb:IntArray,val focus:Float)
    fun color(coc:Float):Int {
        require(coc.isFinite())
        return when { coc==0f -> NEAR_ARGB; abs(coc)<1f -> TRANSITION_ARGB; else -> 0 }
    }
    fun create(depth:LegendaryMath.Frame,point:LegendaryMath.Point,aperture:Float,
               outputWidth:Int,outputHeight:Int,cancel:Cancel,rangeSteps:Int=0):Overlay? {
        cancel.check();FocusRangeMath.checkSteps(rangeSteps)
        val selected=LegendaryMath.focus(depth,point) ?: return null
        // Green now means the specified zero-CoC interval, independent of F. Yellow is transition.
        // Sample actual OUTPUT pixel centres, not a separately rescaled depth threshold.
        val (w,h)=LeicaSizes.previewSize(outputWidth,outputHeight)
        val field=FocusRangeMath.field(depth,selected,aperture,rangeSteps,cancel)
        val pixels=IntArray(w*h)
        for(y in 0 until h){cancel.check();val oy=FocusRangeMath.displayPixel(y,h,outputHeight)
            for(x in 0 until w){val ox=FocusRangeMath.displayPixel(x,w,outputWidth)
                pixels[y*w+x]=color(FocusRangeMath.sample(field,ox,oy,outputWidth,outputHeight))
            }
        }
        cancel.check();return Overlay(w,h,pixels,selected)
    }
}

