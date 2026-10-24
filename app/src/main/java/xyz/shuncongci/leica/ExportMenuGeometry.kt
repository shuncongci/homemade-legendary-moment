package xyz.shuncongci.leica

/** Screen-space placement: panel bottom stays at the source button bottom; overflow scrolls internally. */
object ExportMenuGeometry {
    data class Box(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width get()=right-left;val height get()=bottom-top
        init {require(right>left && bottom>top)}
    }
    data class Layout(val box:Box,val origin:Box)
    fun place(safe:Box,anchor:Box,desiredWidth:Int,desiredHeight:Int):Layout {
        require(desiredWidth>0 && desiredHeight>0)
        val width=desiredWidth.coerceAtMost(safe.width)
        val bottom=anchor.bottom.coerceIn(safe.top+1,safe.bottom)
        val height=desiredHeight.coerceAtMost(bottom-safe.top)
        val center=anchor.left+anchor.width/2
        val left=(center-width/2).coerceIn(safe.left,safe.right-width)
        val box=Box(left,bottom-height,left+width,bottom)
        val originWidth=anchor.width.coerceAtMost(width)
        val originLeft=(anchor.left-left).coerceIn(0,width-originWidth)
        val originHeight=anchor.height.coerceAtMost(height)
        return Layout(box,Box(originLeft,height-originHeight,originLeft+originWidth,height))
    }
    /** Compatibility entry point; the actual UI chooses separate open/close Bezier easing. */
    fun reveal(t:Float):Float=ExportMotion.openEase(t)
    fun stagger(progress:Float,index:Int):Float {
        val start=.1f+index.coerceIn(0,10)*.06f
        return ((progress-start)/(1-start)).coerceIn(0f,1f)
    }
}
