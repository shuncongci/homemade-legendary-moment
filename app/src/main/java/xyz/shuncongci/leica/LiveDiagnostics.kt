package xyz.shuncongci.leica

import android.app.ActivityManager
import android.content.Context
import android.os.*
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.*
import java.util.Locale

class LiveDiagnostics(
    private val context:Context,
    private val window:Window,
    private val worker:()->Thread?,
    private val stage:()->String,
    private val failure:()->String?,
    private val detailsRequested:()->Boolean={true}
) {
    data class Snapshot(val summary:String,val details:String,val phase:String,val metrics:List<String>)
    private var job:Job?=null
    private var handlerThread:HandlerThread?=null
    private val samples=ArrayList<Double>()
    private var lost=0
    private val listener=Window.OnFrameMetricsAvailableListener{_,metrics,dropped->
        synchronized(samples){if(samples.size<600)samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0);lost+=dropped}
    }
    private fun number(v:Double)=String.format(Locale.US,"%.1f",v)
    private fun mib(v:Long)=number(v/1048576.0)
    fun start(scope:CoroutineScope,publish:(Snapshot)->Unit){
        stop();handlerThread=HandlerThread("window-metrics").also{it.start()}
        window.addOnFrameMetricsAvailableListener(listener,Handler(handlerThread!!.looper))
        job=scope.launch{
            var lastCpu=android.os.Process.getElapsedCpuTime();var lastWall=SystemClock.elapsedRealtime()
            while(isActive){
                delay(1500)
                val phase=stage();val recentFailure=failure();val readDetails=detailsRequested()
                val refresh=window.decorView.display?.refreshRate?.takeIf{it.isFinite() && it>0f}
                val snapshot=withContext(Dispatchers.Default){
                    val now=SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime();val cores=Runtime.getRuntime().availableProcessors()
                    val usage=DiagnosticMath.cpu(lastCpu,cpu,lastWall,now,cores);lastCpu=cpu;lastWall=now
                    val am=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val system=ActivityManager.MemoryInfo().also{am.getMemoryInfo(it)}
                    val mem=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)};val runtime=Runtime.getRuntime()
                    val (frames,dropped)=synchronized(samples){(samples.toList() to lost).also{samples.clear();lost=0}}
                    val slow=refresh?.let{rate->frames.count{it>1000/rate}}
                    val thread=worker()
                    val stack=if(!readDetails)"  展开详情后采样" else thread?.stackTrace?.take(12)?.joinToString("\n"){"  ${it.className}.${it.methodName}${if(it.isNativeMethod)" [原生方法]" else ""}"} ?: "  工作线程尚未启动"
                    val thermal=if(Build.VERSION.SDK_INT>=29){val pm=context.getSystemService(Context.POWER_SERVICE) as PowerManager;arrayOf("正常","轻度","中度","严重","临界","紧急","关机保护").getOrElse(pm.currentThermalStatus){"未知"}}else "系统不提供"
                    val summary=buildString {
                        append("当前设备 · 安卓 ${Build.VERSION.RELEASE}\n系统接口级别 ${Build.VERSION.SDK_INT}\n")
                        append("\n应用进程\n")
                        append("处理器  ${usage?.let{number(it.oneCorePercent)} ?: "—"}% 单核等效\n总容量  ${usage?.let{number(it.capacityPercent)} ?: "—"}% · $cores 个逻辑核\n")
                        append("分摊驻留内存  ${number(mem.totalPss/1024.0)}\n")
                        append("托管堆  ${mib(runtime.totalMemory()-runtime.freeMemory())} / ${mib(runtime.maxMemory())} 已用／上限\n")
                        append("原生堆  ${mib(Debug.getNativeHeapAllocatedSize())} / ${mib(Debug.getNativeHeapSize())} 已分配／堆大小\n")
                        append("系统内存  ${mib(system.availMem)} / ${mib(system.totalMem)} 可用／总量\n低内存  ${if(system.lowMemory)"是" else "否"} · 热状态  $thermal\n")
                        append("\n窗口呈现\n刷新率 ${refresh?.let{number(it.toDouble())} ?: "—"} 赫兹 · 本轮 ${frames.size} 帧\n")
                        append("中位耗时 ${DiagnosticMath.percentile(frames,.5)?.let{number(it)} ?: "—"} 毫秒\n百分之九十五分位 ${DiagnosticMath.percentile(frames,.95)?.let{number(it)} ?: "—"} 毫秒\n超刷新预算 ${slow?.toString() ?: "—"} 帧 · 回调丢失 $dropped\n")
                        append("\n工作线程 · ${UiText.threadState(thread?.state)}\n$phase\n")
                        append("\n内存单位：兆字节（二进制），各项不能相加。\n每1.5秒采样，仅本机；关闭或退到后台即停止。\n占用仅指本应用，不代表整机；图形与神经处理器占用不可用。\n帧耗时不是推理速度，静止窗口可能没有新帧。")
                    }
                    val details=buildString {
                        append("设备原始标识\n${Build.MANUFACTURER} ${Build.MODEL}\n处理架构 ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}\n")
                        append("\n工作线程调用栈\n$stack\n\n仅托管线程采样，原生库内部栈不可见。\n")
                        append("\n最近错误\n${recentFailure ?: "本次会话暂无错误"}")
                    }
                    Snapshot(summary,details,EditorPresentation.stage(phase.lineSequence().firstOrNull().orEmpty()),listOf(
                        usage?.let{number(it.capacityPercent)+"%"}?:"—",number(mem.totalPss/1024.0)+" MB",thermal,
                        mib(system.availMem)+" MB"+if(system.lowMemory)"\n内存紧张" else ""))
                }
                publish(snapshot)
            }
        }
    }
    fun stop(){job?.cancel();job=null;if(handlerThread!=null)window.removeOnFrameMetricsAvailableListener(listener);handlerThread?.quitSafely();handlerThread=null;synchronized(samples){samples.clear();lost=0}}
}
