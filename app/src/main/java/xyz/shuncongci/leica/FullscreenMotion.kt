package xyz.shuncongci.leica

import kotlin.math.roundToInt

/** Display geometry only. One Activity window; live image pixels are never copied or faded. */
object FullscreenMotion {
    fun viewport(safe:ExportMotion.Rect,top:Float,bottom:Float):ExportMotion.Rect {
        require(top.isFinite() && bottom.isFinite() && top>=0f && bottom>=0f)
        val t=top.coerceAtMost(safe.height*.25f)
        val b=bottom.coerceAtMost(safe.height*.35f)
        return ExportMotion.Rect(safe.left,safe.top+t,safe.right,safe.bottom-b)
    }
    fun frame(from:ExportMotion.Rect,to:ExportMotion.Rect,progress:Float):ExportMotion.Rect {
        require(progress.isFinite() && progress in 0f..1f)
        return ExportMotion.mix(from,to,progress)
    }
    /** The scroll occupies the entire window behind the island; padding is only its initial content gap. */
    fun islandScrollTop(topInset:Int,islandHeight:Int,gap:Int):Int {
        require(topInset>=0 && islandHeight>=0 && gap>=0)
        val total=topInset.toLong()+islandHeight+gap
        require(total<=Int.MAX_VALUE)
        return total.toInt()
    }
    /** Acceptance is an explicit click AND a successfully completed close; cancellation never commits. */
    class ChoiceCommit {
        private var pending=false
        private var delivered=false
        fun begin():Boolean {if(pending || delivered)return false;pending=true;return true}
        fun cancel(closeCompleted:Boolean):Boolean {
            if(delivered || (pending && closeCompleted))return false
            delivered=true;return true
        }
        fun commit(closeCompleted:Boolean):Boolean {
            if(delivered || !pending || !closeCompleted)return false
            delivered=true;return true
        }
    }
    data class CompositionChrome(val proposals:Boolean,val normalViews:Boolean,val normalActions:Boolean,val apply:Boolean,val exit:Boolean,val select:Boolean)
    fun composition(composing:Boolean,busy:Boolean,closing:Boolean,cancelling:Boolean,count:Int):CompositionChrome {
        require(count>=0)
        val ready=composing && !busy && !closing && !cancelling && count>0
        return CompositionChrome(composing,!composing,!composing,ready,composing && !closing && !cancelling,ready)
    }
    /** The record retains a real hit target on the SAME horizontal row as the mode switch. */
    fun recordModeWidth(available:Int,density:Float,font:Float):Int = minOf(WorkspaceMotion.modeWidth(available,density,font),(available*.60f).roundToInt())
    fun recordLabel(summary:String,roomDp:Float,font:Float):String {
        require(roomDp.isFinite() && roomDp>=0f && font.isFinite() && font>0f)
        return if(summary.isBlank())"处理记录 ›"else if(roomDp/font<130f)"处理记录 ›\n$summary"else "处理记录 · $summary"
    }
    fun panelHeight(height:Int,font:Float):Int {
        require(height>=0 && font.isFinite() && font>0)
        return (height*(.90f+.04f*(font-1f).coerceIn(0f,1f))).roundToInt()
    }
}
