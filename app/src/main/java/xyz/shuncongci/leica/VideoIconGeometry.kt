package xyz.shuncongci.leica

/** Centre the INK bounds, not the empty 24-unit viewport or a TextView's drawable slot.
 * Coordinates are floats so odd pixel widths retain a true half-pixel centre. */
internal object VideoIconGeometry {
    data class Ink(val left:Float,val top:Float,val right:Float,val bottom:Float)
    data class Origin(val x:Float,val y:Float)
    fun origin(width:Int,height:Int,side:Int,ink:Ink):Origin {
        require(width>=0 && height>=0 && side>0)
        require(listOf(ink.left,ink.top,ink.right,ink.bottom).all { it.isFinite() })
        require(ink.right>ink.left && ink.bottom>ink.top)
        val scale=side/24f
        return Origin(width/2f-(ink.left+ink.right)/2f*scale,
            height/2f-(ink.top+ink.bottom)/2f*scale)
    }
}
