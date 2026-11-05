package xyz.shuncongci.leica

/** Workspace-only decisions. Never encodes pixels, starts work or changes existing rendered files. */
object EditorWorkspacePolicy {
    /** Four stable actions; choosing HDR/SDR no longer adds/removes primary action buttons. */
    fun actionWeights()=listOf(1f,1f,1.7f,1f)
    fun canChooseMode(sourceHdr:Boolean,onlyComposition:Boolean,hasPhoto:Boolean,busy:Boolean,composing:Boolean)=
        sourceHdr && !onlyComposition && hasPhoto && !busy && !composing
    fun normalizedMode(sourceHdr:Boolean,value:HdrDevelopMode)=HdrDevelopMode.restore(value.name,sourceHdr)
    /** Future intent only: preserve saved pixels, saved flag, focus, subject completion and export preference. */
    fun chooseMode(flow:PhotoFlow,current:HdrDevelopMode,value:HdrDevelopMode,sourceHdr:Boolean):HdrDevelopMode {
        check(!flow.busy)
        val selected=normalizedMode(sourceHdr,value)
        if(selected!=current)flow.parametersChanged()
        return selected
    }
    fun primaryLabel(action:String,requested:HdrDevelopMode,sourceHdr:Boolean,onlyComposition:Boolean):String {
        if(!sourceHdr || onlyComposition || action !in listOf("显影","重新显影","显影并构图","显影中"))return action
        val mode=normalizedMode(sourceHdr,requested).name
        return if(action=="显影中")"${mode}显影中"else if(action=="显影并构图")"${mode}显影＋构图"else "${mode}显影"
    }
    /** Describes the export target, deliberately has no requested-mode argument. */
    fun renderedSummary(hasResult:Boolean,resultHdr:Boolean,pending:Boolean):String {
        if(!hasResult)return "尚无成片"
        return "现有成片 · ${if(resultHdr)"HDR"else "SDR"}"+if(pending)"\n参数待应用 · 导出仍为上次成片"else ""
    }
}

/** Presentation/interlocks only. Service admission, permissions, queue and deletion gates stay authoritative. */
object ExternalWorkspacePolicy {
    fun canStart(running:Boolean,browsing:Boolean,editorBusy:Boolean)=!running && !browsing && !editorBusy
    fun canOpenCamera(running:Boolean,ready:Boolean,stopping:Boolean)=running && ready && !stopping
    fun phase(running:Boolean,ready:Boolean,working:Boolean,stopping:Boolean)=when {
        !running->"未开启"
        stopping->"正在停止"
        !ready->"正在准备 · 请勿拍照"
        working->"监听已就绪 · 正在处理"
        else->"监听已就绪 · 可拍新照片"
    }
}
