package xyz.shuncongci.leica

/** UI-only state decisions. Never starts/stops work or changes a render parameter. */
object UiPolishPolicy {
    fun parametersVisible(expanded:Boolean,composing:Boolean,fullscreen:Boolean)=!fullscreen && (composing || expanded)
    fun tabsIconOnly(heightDp:Int,font:Float)=ReferenceWorkspacePolicy.chrome(heightDp,font).compact || font>=1.8f
    fun stackInfo(widthDp:Int,font:Float):Boolean {require(widthDp>=0 && font>0 && font.isFinite());return widthDp<360 || font>=1.4f}
    fun stackActions(widthDp:Int,font:Float,count:Int):Boolean {
        require(widthDp>=0 && font>0 && font.isFinite() && count>=0)
        return count>1 && widthDp<count*(104+28*(font-1).coerceAtLeast(0f))
    }
    fun dock(widthDp:Int,font:Float,compact:Boolean):ReferenceWorkspacePolicy.Dock {
        val old=ReferenceWorkspacePolicy.dock(widthDp,font,compact)
        val space=(widthDp-28-120-2*old.margin-10).coerceAtLeast(0)
        val iconOnly=space<56*font+8
        return old.copy(iconOnly=iconOnly,labelOnly=!iconOnly && space<56*font+43)
    }
    data class CancelUi(val visible:Boolean,val enabled:Boolean,val label:String)
    fun cancelUi(op:PhotoFlow.Operation,cancelling:Boolean,hasToken:Boolean):CancelUi {
        val visible=hasToken && op in listOf(PhotoFlow.Operation.GENERATE,PhotoFlow.Operation.FOCUS_PREVIEW,PhotoFlow.Operation.DEPTH,PhotoFlow.Operation.COMPOSITION,PhotoFlow.Operation.SAVE)
        return CancelUi(visible,visible && !cancelling,if(cancelling)"正在取消"else "取消处理")
    }
    fun runLabel(op:PhotoFlow.Operation,composition:Boolean,result:Boolean)=when {
        op==PhotoFlow.Operation.GENERATE->"显影中"
        op==PhotoFlow.Operation.SAVE->"导出中"
        op==PhotoFlow.Operation.PREVIEW->"读取中"
        op!=PhotoFlow.Operation.IDLE->"处理中"
        composition->"选择构图"
        result->"重新显影"
        else->"显影"
    }
    data class ListenerAction(val label:String,val enabled:Boolean,val highlighted:Boolean)
    fun listenerAction(running:Boolean,stopping:Boolean,browsing:Boolean,editorBusy:Boolean)=
        ListenerAction(if(running){if(stopping)"正在停止"else "停止监听"}else "开始监听",
            if(running)!stopping else ExternalWorkspacePolicy.canStart(running,browsing,editorBusy),!running)
    fun monitorState(notifying:Boolean)=if(notifying)"运行参数通知已开启；点击查看实时监测"else "运行参数通知未开启；点击查看实时监测"
}
