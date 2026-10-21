package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.CompositionGeometry.Crop

/** The selected budget applies AFTER cropping. Never enlarge an already rendered result. */
object CompositionOutput {
    data class Plan(val crop:Crop,val width:Int,val height:Int,val workWidth:Int,val workHeight:Int)
    fun eligible(w:Int,h:Int,crop:Crop,output:BokehOutput):Boolean {
        val edge=output.longEdge?:return true
        val b=CompositionGeometry.pixels(crop,w,h)
        return max(w,h)<edge || max(b.w,b.h)>=edge
    }
    fun plan(w:Int,h:Int,crop:Crop,output:BokehOutput):Plan {
        require(w>0 && h>0)
        require(eligible(w,h,crop,output)){"该构图不足所选像素，请选择其他方案"}
        val c=CompositionGeometry.snapped(crop,w,h);val b=CompositionGeometry.pixels(c,w,h)
        val edge=min(output.longEdge?:max(b.w,b.h),max(b.w,b.h))
        val scale=edge.toDouble()/max(b.w,b.h)
        val tw=max(1,(b.w*scale).roundToInt());val th=max(1,(b.h*scale).roundToInt())
        // Preserve the full scene during color/bokeh: surrounding light and the original focus plane remain available.
        val workScale=max(tw.toDouble()/b.w,th.toDouble()/b.h)
        var ww=ceil(w*workScale).toInt().coerceIn(1,w);var wh=ceil(h*workScale).toInt().coerceIn(1,h)
        while(CompositionGeometry.pixels(c,ww,wh).w<tw && ww<w)ww++
        while(CompositionGeometry.pixels(c,ww,wh).h<th && wh<h)wh++
        return Plan(c,tw,th,ww,wh)
    }
}

/** Immutable appearance settings of the displayed result; later slider edits cannot change its export. */
data class CompositionAppearance(val color:Boolean,val blur:Boolean,val focus:LegendaryMath.Point?,val aperture:Float) {
    init {require(!blur || focus!=null);FullFrameOptics.checkAperture(aperture)}
}
