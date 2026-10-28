package xyz.shuncongci.leica

/** Explicit monotonic stage accounting, including model compilation/compatibility attempts. */
class StageTiming(private val clock:()->Long) {
    private val durations=linkedMapOf<String,Long>()
    private var active:String?=null
    private var since=0L
    fun start(name:String){if(active==name)return;finish();active=name;since=clock()}
    fun finish(){active?.let{durations[it]=(durations[it] ?: 0L)+(clock()-since).coerceAtLeast(0L)};active=null}
    fun currentSeconds()=ProcessingTime.seconds(if(active==null)0 else clock()-since)
    fun hasStage(name:String)=durations.containsKey(name) || active==name
    fun summary()=durations.entries.joinToString("｜"){(name,ms)->"$name ${ProcessingTime.seconds(ms)}"}
}
