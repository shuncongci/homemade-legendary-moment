package xyz.shuncongci.leica

import kotlin.math.*

/** A display sweep unlocked only by a REAL multiscale output. Tile timing is presentation, not inference progress. */
class BokehTileReveal {
    var pass=0;private set
    var elapsed=0f;private set
    val active get()=pass>0 && elapsed<DURATION
    companion object { const val DURATION=720f }
    fun accept(sequence:Int):Boolean {
        require(sequence>0)
        if(sequence<=pass)return false
        pass=sequence;elapsed=0f;return true
    }
    fun advance(ms:Float){require(ms.isFinite() && ms>=0);if(pass>0)elapsed=(elapsed+ms).coerceAtMost(DURATION)}
    fun settle(){if(pass>0)elapsed=DURATION}
    fun reset(){pass=0;elapsed=0f}
    fun progress(index:Int,cols:Int,rows:Int,focus:LegendaryMath.Point):Float {
        require(cols>0 && rows>0 && index in 0 until cols*rows)
        if(pass==0)return 0f
        val distance=hypot((index%cols+.5f)/cols-focus.x,(index/cols+.5f)/rows-focus.y)/sqrt(2f)
        val delay=distance*360f
        return ((elapsed-delay)/300f).coerceIn(0f,1f)
    }
    fun opacity(index:Int,cols:Int,rows:Int,focus:LegendaryMath.Point)=DevelopMath.ease(progress(index,cols,rows,focus))
    fun pulse(index:Int,cols:Int,rows:Int,focus:LegendaryMath.Point):Float {
        val p=progress(index,cols,rows,focus)
        return if(p<=0f || p>=1f)0f else sin(PI*p).toFloat()
    }
}
