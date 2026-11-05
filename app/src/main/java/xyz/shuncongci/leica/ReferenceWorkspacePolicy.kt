package xyz.shuncongci.leica

/** Display-only copy/geometry for the reference layout. No file, render or service side effects. */
object ReferenceWorkspacePolicy {
    data class Chrome(val compact:Boolean,val header:Int,val tabHeight:Int,val dockHeight:Int,val tabsMinWidth:Int)
    fun chrome(heightDp:Int,font:Float):Chrome {
        require(heightDp>=0 && font>0 && font.isFinite())
        val compact=heightDp<500 || (font>=1.5f && heightDp<650)
        val cell=(48+16*(font-1).coerceIn(0f,1.5f)).toInt()
        return Chrome(compact,48,if(compact)48 else (62+12*(font-1).coerceIn(0f,1.5f)).toInt(),if(compact)64 else 78,6*cell+5*6)
    }
    data class Dock(val labelOnly:Boolean,val iconOnly:Boolean,val buttonHeight:Int,val margin:Int)
    fun dock(widthDp:Int,font:Float,compact:Boolean):Dock {
        require(widthDp>=0 && font>0 && font.isFinite())
        val margin=if(widthDp<360 || compact)8 else 14
        val cell=((widthDp-28-120-2*margin-10)/2).coerceAtLeast(0)
        // Budget for the longest caption (显影中), not just the idle two-character labels.
        val iconOnly=cell<42*font+8
        return Dock(!iconOnly && cell<18+5+42*font+20,iconOnly,(44+10*font.coerceIn(1f,2.5f)).toInt().coerceAtLeast(48),margin)
    }
    fun importantLine(raw:String)=raw.lineSequence().map{it.trim()}.firstOrNull{it.isNotEmpty() && urgent(it)}?:oneLine(raw)
    fun shortAction(action:String)=when(action){
        "选择构图"->"构图"
        "显影中"->"显影中"
        else->"显影"
    }
    fun urgent(raw:String)=listOf("失败","取消","不足","无法","中断","未发布","未读取","不支持","不可用","请勿关闭").any{raw.contains(it)}
    fun oneLine(raw:String)=raw.lineSequence().map{it.trim()}.firstOrNull{it.isNotEmpty()}.orEmpty()
    fun dimensions(hasPhoto:Boolean,width:Int,height:Int,ms:Long?):String {
        if(!hasPhoto)return "选择照片，或前往相机"
        require(width>0 && height>0)
        return "$width×$height"+(ms?.takeIf{it>=0}?.let{" · ${ProcessingTime.seconds(it)}"}?:"")
    }
    /** The requested develop mode is deliberately absent: these are the pixels already available. */
    fun previewBadge(result:Boolean,compare:Boolean,sourceHdr:Boolean,resultHdr:Boolean,pending:Boolean,hdrVisible:Boolean):String {
        val actual=if(result)resultHdr else sourceHdr
        val text=when {
            compare->"${if(sourceHdr)"HDR"else "SDR"} / ${if(resultHdr)"HDR"else "SDR"} 对比"
            actual && !hdrVisible->"HDR · SDR 显示"
            result->if(actual)"HDR 成片"else "SDR 成片"
            actual->"HDR 预览"
            else->"SDR 原图"
        }
        return text+if(pending && (result || compare))"\n参数待应用"else ""
    }
    fun listener(running:Boolean,ready:Boolean,working:Boolean,stopping:Boolean,saved:Int):String {
        require(saved>=0)
        return when{
            !running->"外接未开启"
            stopping->"正在停止"
            !ready->"正在准备"
            working->"处理中 · 已保存 $saved"
            else->"监听中 · 已保存 $saved"
        }
    }
}
