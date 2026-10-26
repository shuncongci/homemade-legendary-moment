package xyz.shuncongci.leica

import kotlin.math.min
import kotlin.math.roundToInt

/** Display-only geometry/copy. No output pixels, render keys or persisted edit parameters. */
object EditorPresentation {
    fun panel(w:Int,h:Int,density:Float,font:Float,amount:Float):EditorLayoutPolicy.Layout {
        require(amount.isFinite())
        val p=amount.coerceIn(0f,1f)
        val full=EditorLayoutPolicy.arrange(w,h,density,font,true)
        fun mix(a:Int,b:Int)=(a+(b-a)*p).roundToInt()
        val preview=EditorLayoutPolicy.Rect(0,0,mix(w,full.preview.w),mix(h,full.preview.h))
        // Keep the scroll content measured at its final size; slide and clip rather than squash its text.
        val c=full.controls
        val controls=if(full.sideBySide)c.copy(x=mix(w,c.x))else c.copy(y=mix(h,c.y))
        return EditorLayoutPolicy.Layout(preview,controls,full.sideBySide)
    }
    data class Rect(val left:Float,val top:Float,val right:Float,val bottom:Float)
    fun finalRect(w:Int,h:Int,bw:Int,bh:Int,crop:CompositionGeometry.Crop,amount:Float):Rect {
        require(w>=0 && h>=0 && bw>0 && bh>0 && amount.isFinite())
        val p=amount.coerceIn(0f,1f)
        val start=min(w.toFloat()/bw,h.toFloat()/bh)*.94f
        val cw=(bw*crop.w).roundToInt().coerceAtLeast(1);val ch=(bh*crop.h).roundToInt().coerceAtLeast(1)
        val scale=min(w.toFloat()/cw,h.toFloat()/ch)
        val tw=cw*scale/crop.w.toFloat();val th=ch*scale/crop.h.toFloat()
        val tx=(w-cw*scale)/2-crop.l.toFloat()*tw;val ty=(h-ch*scale)/2-crop.t.toFloat()*th
        fun mix(a:Float,b:Float)=a+(b-a)*p
        val left=mix((w-bw*start)/2,tx);val top=mix((h-bh*start)/2,ty)
        return Rect(left,top,left+mix(bw*start,tw),top+mix(bh*start,th))
    }
    /** Crop the decorative full frame continuously into CompareView's final visible crop. */
    fun finalClip(rect:Rect,crop:CompositionGeometry.Crop,amount:Float):Rect {
        require(amount.isFinite())
        val p=amount.coerceIn(0f,1f);val w=rect.right-rect.left;val h=rect.bottom-rect.top
        return Rect(rect.left+w*crop.l.toFloat()*p,rect.top+h*crop.t.toFloat()*p,
            rect.right-w*(1-crop.r).toFloat()*p,rect.bottom-h*(1-crop.b).toFloat()*p)
    }
    fun holdFinal(stage:DevelopMotion.Stage,hasFinal:Boolean)=stage==DevelopMotion.Stage.DONE && hasFinal
    private val step=Regex("^\\d+/\\d+\\s+")
    fun stage(raw:String):String {
        val text=raw.trim().replace(step,"")
        if(text.isEmpty())return "等待操作"
        // Errors and explicit fallbacks must never be shortened into a success message.
        if(listOf("失败","取消","不支持","不可用","已转 SDR").any{text.contains(it)})return text
        return when {
            text.startsWith("粗深度") -> "生成深度预览"
            text.startsWith("完整深度") || text.startsWith("解析") -> "计算照片深度"
            text.startsWith("焦内范围") -> "更新景深提示"
            text.startsWith("主体保护") -> text.substringBefore(" · 分析图")
            text.startsWith("读取") -> "读取照片"
            text.startsWith("上色") || text.startsWith("融合") -> "应用调色风格"
            text.startsWith("HDR · 按行") -> "生成 HDR 成片"
            text.startsWith("HDR · 源") -> "还原 HDR 亮度"
            text.contains("高光合成") -> "处理高光"
            text.contains("虚化") -> "处理背景虚化"
            text.startsWith("成片") -> "整理成片"
            text.startsWith("分析当前画面") -> "寻找构图方案"
            text.startsWith("生成完成") -> "显影完成 · 展示成片"
            else -> text.substringBefore(" · ")
        }
    }
}
