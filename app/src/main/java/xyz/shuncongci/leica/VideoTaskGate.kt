package xyz.shuncongci.leica

/** Main-thread ownership gate. Only a successful, still-current task may replace a
 * completed result; cancellation is checked again at UI handoff, not just in the worker. */
internal class VideoTaskGate {
    enum class End { SUCCESS, CANCELLED, FAILED, STALE }
    private var current:Any?=null
    fun begin(task:Any){check(current==null){"视频任务尚未结束"};current=task}
    fun finish(task:Any,cancelled:Boolean,failed:Boolean=false):End {
        if(current!==task)return End.STALE
        current=null
        return when {failed->End.FAILED;cancelled->End.CANCELLED;else->End.SUCCESS}
    }
    fun abandon(){current=null}
}
