package xyz.shuncongci.leica

import kotlin.math.abs
import kotlin.math.roundToInt

/** Only the action dock moves; image pixels and the editor's composition state are untouched. */
internal object CompositionActionMotion {
    enum class Change { UNCHANGED, SETTLE, ANIMATE }
    class State {
        var composing=false;private set
        var fraction=0f;private set
        private var initialized=false
        val target get()=if(composing)1f else 0f
        fun bind(value:Boolean,animate:Boolean):Change {
            if(initialized && composing==value)return Change.UNCHANGED
            composing=value
            if(!initialized || !animate){initialized=true;settle();return Change.SETTLE}
            return if(fraction==target)Change.SETTLE else Change.ANIMATE
        }
        fun advance(value:Float){fraction=if(value.isFinite())value.coerceIn(0f,1f)else target}
        fun settle(){fraction=target}
    }
    data class Frame(val normalAlpha:Float,val compositionAlpha:Float,val normalOffset:Float,val compositionOffset:Float)
    private fun smooth(v:Float):Float {val x=v.coerceIn(0f,1f);return x*x*(3-2*x)}
    fun frame(value:Float):Frame {
        val p=if(value.isFinite())value.coerceIn(0f,1f)else 0f
        val normal=1f-smooth(p/.45f);val composition=smooth((p-.55f)/.45f)
        return Frame(normal,composition,-12f*(1-normal),12f*(1-composition))
    }
    fun height(normal:Int,composition:Int,fraction:Float)=
        (normal+(composition-normal)*fraction.coerceIn(0f,1f)).roundToInt().coerceAtLeast(0)
    fun duration(from:Float,to:Float)=(300*abs(to-from)).roundToInt().coerceIn(80,300).toLong()
}
