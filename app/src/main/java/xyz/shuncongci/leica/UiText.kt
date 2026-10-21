package xyz.shuncongci.leica

object UiText {
    const val SUBJECT_LABEL="主体保护"
    fun subjectLoading(index:Int,total:Int,refining:Boolean):String {
        require(index in 1..total)
        return "$SUBJECT_LABEL · 第${index}/${total}个待算点 · ${if(refining)"细化边缘" else "识别范围"}"
    }
    fun operation(value: PhotoFlow.Operation): String = when (value) {
        PhotoFlow.Operation.IDLE -> "待机"
        PhotoFlow.Operation.PREVIEW -> "读取照片"
        PhotoFlow.Operation.FOCUS_PREVIEW -> "计算景深提示"
        PhotoFlow.Operation.DEPTH -> "解析深度"
        PhotoFlow.Operation.GENERATE -> "生成成片"
        PhotoFlow.Operation.COMPOSITION -> "分析构图"
        PhotoFlow.Operation.SAVE -> "保存成片"
    }

    fun threadState(value: Thread.State?): String = when (value) {
        null -> "尚未启动"
        Thread.State.NEW -> "尚未运行"
        Thread.State.RUNNABLE -> "可运行"
        Thread.State.BLOCKED -> "等待锁"
        Thread.State.WAITING -> "等待任务"
        Thread.State.TIMED_WAITING -> "限时等待"
        Thread.State.TERMINATED -> "已结束"
    }

    fun failure(error: Exception): String {
        val message = error.message
        val publicMessages = setOf("无法打开这张照片", "不支持的照片格式", "无法解码这张照片", "请至少选择调色或虚化")
        return when {
            message in publicMessages -> "处理失败：$message"
            error is SecurityException -> "访问权限不足，请重新选择照片或允许访问。原始详情可在监测中查看。"
            error is java.io.IOException -> "文件读取或写入失败，请检查照片与剩余空间。原始详情可在监测中查看。"
            else -> "处理未完成，请展开监测中的原始详情查看原因。"
        }
    }
}
