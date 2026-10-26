package xyz.shuncongci.leica

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Plain-word text for the parameter notification. Pure, so the wording is unit-tested. */
object RuntimeMonitorText {
    data class Sample(val cpu:DiagnosticMath.Cpu?,val cores:Int,val appMiB:Double,val heapUsedMiB:Double,val heapMaxMiB:Double,
                      val nativeMiB:Double,val availMiB:Double,val totalMiB:Double,val lowMemory:Boolean,val thermal:Int?)
    private val THERMAL=arrayOf("正常","微热","较热","很热","过热","非常热","即将关机保护")
    fun thermal(status:Int?)=if(status==null)"系统不提供" else THERMAL.getOrElse(status){"未知"}
    private fun n(v:Double)=String.format(Locale.US,"%.0f",v)
    /** One collapsed line: the three numbers people actually check. */
    fun line(s:Sample)="处理器 ${s.cpu?.let{n(it.oneCorePercent)+"%"}?:"—"} · 内存 ${n(s.appMiB)} MB · 温度${thermal(s.thermal)}"
    fun detail(s:Sample,stage:String)=buildString{
        append("正在做：").append(stage.ifBlank{"空闲"}).append('\n')
        append("处理器 ").append(s.cpu?.let{"${n(it.oneCorePercent)}%（按单核算，占全部 ${n(it.capacityPercent)}%）"}?:"—").append(" · ${s.cores} 核\n")
        append("本应用内存 ${n(s.appMiB)} MB（Java ${n(s.heapUsedMiB)}/${n(s.heapMaxMiB)} · 原生 ${n(s.nativeMiB)}）\n")
        append("手机可用内存 ${n(s.availMiB)} / ${n(s.totalMiB)} MB${if(s.lowMemory)" · 内存紧张" else ""}\n")
        append("温度状态 ${thermal(s.thermal)} · 每 ${RuntimeMonitor.INTERVAL_MS/1000} 秒更新，只统计本应用")
    }
}

/** Editor-side switch; the external mode keeps its own [ExternalSettings.monitor]. Off by default. */
object MonitorPreferences {
    private fun prefs(c:Context)=c.getSharedPreferences("runtime-monitor",Context.MODE_PRIVATE)
    fun editor(c:Context)=prefs(c).getBoolean("editor-notification",false)
    fun saveEditor(c:Context,on:Boolean)=prefs(c).edit().putBoolean("editor-notification",on).apply()
}

/** Optional SECOND, silent notification with live process parameters (CPU, memory, temperature, current step).
 *  Samples only this process on a background-priority thread; no frame metrics, no storage reads, no image access.
 *  [stage] is always invoked on the main thread. Single use: after [stop] create a new instance. */
class RuntimeMonitor(context:Context,private val id:Int,private val title:String,private val target:Class<*>,private val stage:()->String) {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val manager=app.getSystemService(NotificationManager::class.java)
    @Volatile private var running=false
    @Volatile private var thread:HandlerThread?=null
    private var lastCpu=0L
    private var lastWall=0L
    fun start():Boolean {
        if(running)return true
        if(thread!=null || !ExternalPermissions.notifications(app))return false
        try {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"运行参数",NotificationManager.IMPORTANCE_LOW).apply{
            description="可选：在通知栏显示本应用的处理器、内存和温度";setSound(null,null);enableVibration(false);setShowBadge(false)
        })
        running=true
        lastCpu=android.os.Process.getElapsedCpuTime();lastWall=SystemClock.elapsedRealtime()
        val t=HandlerThread("runtime-monitor",android.os.Process.THREAD_PRIORITY_BACKGROUND).also{it.start()};thread=t
        val h=Handler(t.looper)
        h.postDelayed(object:Runnable{override fun run(){
            if(!running)return
            try{
                val s=sample()
                // notify/cancel both run on main, so a sample queued before stop() can never re-post afterwards.
                main.post{if(running)runCatching{manager.notify(id,build(s,stage()))}}
            }catch(e:Exception){propagateFatalCause(e)} // Optional telemetry must not abort a photo operation.
            if(running)h.postDelayed(this,INTERVAL_MS)
        }},FIRST_MS)
        return true
        }catch(e:Exception){propagateFatalCause(e);stop();return false}
    }
    fun stop(){
        running=false
        thread?.quitSafely();thread=null
        if(Looper.myLooper()==Looper.getMainLooper())runCatching{manager.cancel(id)}else main.post{runCatching{manager.cancel(id)}}
    }
    private fun sample():RuntimeMonitorText.Sample {
        val now=SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime();val cores=Runtime.getRuntime().availableProcessors()
        val usage=DiagnosticMath.cpu(lastCpu,cpu,lastWall,now,cores);lastCpu=cpu;lastWall=now
        val system=ActivityManager.MemoryInfo().also{app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        val mem=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)};val rt=Runtime.getRuntime()
        val thermal=if(Build.VERSION.SDK_INT>=29)app.getSystemService(PowerManager::class.java).currentThermalStatus else null
        return RuntimeMonitorText.Sample(usage,cores,mem.totalPss/1024.0,(rt.totalMemory()-rt.freeMemory())/MIB,rt.maxMemory()/MIB,
            Debug.getNativeHeapAllocatedSize()/MIB,system.availMem/MIB,system.totalMem/MIB,system.lowMemory,thermal)
    }
    private fun build(s:RuntimeMonitorText.Sample,stage:String)=NotificationCompat.Builder(app,CHANNEL).setSmallIcon(R.drawable.ic_external_notification)
        .setContentTitle(title).setContentText(RuntimeMonitorText.line(s)).setStyle(NotificationCompat.BigTextStyle().bigText(RuntimeMonitorText.detail(s,stage)))
        .setContentIntent(PendingIntent.getActivity(app,id,Intent(app,target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    companion object {
        const val INTERVAL_MS=2000L
        private const val FIRST_MS=1000L
        private const val MIB=1048576.0
        private const val CHANNEL="runtime-monitor"
        const val EXTERNAL_ID=602
        const val EDITOR_ID=603
    }
}
