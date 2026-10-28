package xyz.shuncongci.leica

import kotlin.math.*

/** Explicit user budget, not an OOM fallback. Color-only sizing remains independent. */
object BokehRenderPolicy {
    const val MAX_PIXELS=12_500_000L
    const val ID="original-1250w-small-single-v1"
    fun split(output:BokehOutput)=output==BokehOutput.ORIGINAL
    fun originalSize(w:Int,h:Int):Pair<Int,Int>{
        require(w>0 && h>0)
        val pixels=w.toLong()*h
        if(pixels<=MAX_PIXELS)return w to h
        val scale=sqrt(MAX_PIXELS.toDouble()/pixels)
        var x=max(1,(w*scale).toInt());var y=max(1,(h*scale).toInt())
        if(x.toLong()*y>MAX_PIXELS){if(x>=y)x=(MAX_PIXELS/y).toInt()else y=(MAX_PIXELS/x).toInt()}
        return x to y
    }
    internal fun parts(w:Int,h:Int,split:Boolean)=if(split)BokehBox.four(w,h)else listOf(BokehBox(0,0,w,h))
}

/** Copy and phase identities keep actual layer/part numbers, never a predicted overall %. */
object BokehProgress {
    private val outer=Regex("^\\d+/\\d+\\s+(?!·)")
    private val block=Regex("^([1-4])/([1-4]) · (.+)$")
    private val globalLight=Regex("^(SDR高光|SDR柔边|HDR增光|HDR减光|HDR柔边|SDR/HDR光能|HDR光能) · .+$")
    private val layers=Regex("^(.*) · (\\d+)/(\\d+)层$")
    fun text(raw:String)=raw.trim().replace(outer,"")
    fun owns(raw:String):Boolean {val s=text(raw);return globalLight.matches(s) || block.matches(s) || s.startsWith("准备 · ") || s.startsWith("合成 · ") || layers.matches(s) || s in setOf("虚化","SDR高光","HDR增光","HDR减光","HDR合成","虚化完成","保持焦内")}
    fun key(raw:String):String {val s=text(raw);return layers.matchEntire(s)?.groupValues?.get(1)?:s.substringBefore(" · ").let{if(block.matches(s))s else it}}
    fun label(index:Int,total:Int,phase:String)=if(total>1)"$index/$total · $phase"else phase
    fun bar(raw:String):Pair<Int,Int>? {
        val s=text(raw);val m=layers.matchEntire(s)
        if(m!=null){val n=m.groupValues[2].toInt();val t=m.groupValues[3].toInt();return if(t>0 && n in 0..t)n to t else null}
        val b=block.matchEntire(s)
        return if(b!=null && b.groupValues[3]=="完成")b.groupValues[1].toInt() to b.groupValues[2].toInt()else null
    }
}

data class BokehPartTime(val index:Int,val total:Int,val millis:Long,val steps:String)
data class BokehRunReport(val parts:List<BokehPartTime>) {
    fun summary():String=if(parts.isEmpty())""else (if(parts.first().total>1)"基础虚化分块小计（已含在上方阶段中，不重复累加）"else "基础虚化整幅小计（已含在上方阶段中）")+"\n"+parts.joinToString("\n"){p->
        val place=if(p.total==4)listOf("左上","右上","左下","右下")[p.index-1]else "整幅"
        (if(p.total>1)"第${p.index}块"else "整幅")+" · ${ProcessingTime.seconds(p.millis)}（$place）\n  ${p.steps}"
    }
}

object ProcessTimingText {
    private val parts=Regex("第(\\d+)块 · ([^（\\n]+)")
    fun compact(timing:String)=parts.findAll(timing).joinToString(" · "){"${it.groupValues[1]}块 ${it.groupValues[2]}"}
    fun details(total:Long,timing:String,save:Long?=null):String = "显影 ${ProcessingTime.seconds(total)}（不含展示动画、相册导出）\n\n"+
        timing.replace("｜","\n").ifBlank{"此成片没有过程计时"}+
        (save?.let{"\n\n相册导出 ${ProcessingTime.seconds(it)}（另计）"}?:"")
}
