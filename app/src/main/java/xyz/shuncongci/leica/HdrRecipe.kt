package xyz.shuncongci.leica

import android.net.Uri

/** Exact snapshot of the displayed render. Later parameter edits never alter an HDR re-render. */
data class HdrRecipe(val source:Uri,val style:LeicaStyle,val blur:Boolean,val point:LegendaryMath.Point?,
    val aperture:Float,val output:BokehOutput,val color:Boolean,val width:Int,val height:Int,
    val subject:Boolean,val subjects:List<LegendaryMath.Point>)
