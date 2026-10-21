package xyz.shuncongci.leica

import kotlin.math.*

/** Preview only. No coarse frame is returned to the production depth cache. */
object ProgressiveDepth {
    fun size(w:Int,h:Int):Pair<Int,Int> {
        require(w>0 && h>0)
        val scale=minOf(1.0,518.0/min(w,h),686.0/max(w,h))
        return max(1,(w*scale).roundToInt()) to max(1,(h*scale).roundToInt())
    }
    fun <T> run(cancel:Cancel,coarse:()->T,publish:(T)->Unit,full:()->T):T {
        cancel.check()
        val preview=coarse();cancel.check();publish(preview);cancel.check()
        return full().also{cancel.check()}
    }
    fun tileDepth(map:DepthVisualMath.Tiles,index:Int,cols:Int,rows:Int):Float {
        val x=((index%cols+.5f)*map.cols/cols).toInt().coerceIn(0,map.cols-1)
        val y=((index/cols+.5f)*map.rows/rows).toInt().coerceIn(0,map.rows-1)
        return map.depth[y*map.cols+x]
    }
    fun blend(from:Float,to:Float,t:Float):Float = from+(to-from)*t.coerceIn(0f,1f)
    fun floatOffset(index:Int,time:Float):Float = .035f*sin((time*2*PI+DepthVisualMath.phase(index)*2*PI).toFloat())
}
