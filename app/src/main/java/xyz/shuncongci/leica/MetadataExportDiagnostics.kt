package xyz.shuncongci.leica

/** A bounded, per-export error record, not a global callback or a success/failure decision.
 * collect() must enclose synchronous work on one worker thread, never a suspending lambda. */
internal object MetadataExportDiagnostics {
    private val current=ThreadLocal<Report?>()
    class Report {
        private val reasons=linkedSetOf<String>()
        private var omitted=false
        fun record(reason:String){
            val text=reason.trim().take(4096);if(text.isEmpty() || text in reasons)return
            if(reasons.size<8)reasons.add(text)else omitted=true
        }
        fun record(error:Exception){record(error.stackTraceToString())}
        fun description(saved:Boolean):String? {
            if(reasons.isEmpty())return null
            return buildString{
                append("元数据保留出错 · ")
                append(if(saved)"照片已保存；原图未改动。兼容异常不代表拍摄字段全部丢失"else "导出未完成，是否保存请以最终结果为准；原图未改动")
                append("\n以下为本次兼容导出的错误原因（本次会话保留）：\n")
                append(reasons.joinToString("\n\n"))
                if(omitted)append("\n其余重复或超出记录上限的错误已省略")
            }
        }
    }
    fun record(error:Exception){current.get()?.record(error)}
    fun record(reason:String){current.get()?.record(reason)}
    fun <T> collect(report:Report,block:()->T):T {
        val previous=current.get();current.set(report)
        try{return block()}finally{if(previous==null)current.remove()else current.set(previous)}
    }
    /** Keep the last metadata report even after another successful export or render. */
    fun retain(previous:String?,report:Report,saved:Boolean)=report.description(saved)?:previous
    fun errors(vararg values:String?)=values.filterNotNull().filter{it.isNotBlank()}.distinct().joinToString("\n\n").ifBlank{null}
    fun summary(error:String?)=error?.lineSequence()?.firstOrNull()?.take(180)
}
