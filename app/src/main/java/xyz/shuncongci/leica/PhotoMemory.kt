package xyz.shuncongci.leica

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/** Observational ONLY: never cancels inference, rejects an allocation or changes image size. */
class PhotoMemory(context: Context) {
    private val am=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val prefs=context.getSharedPreferences("legendary-memory",Context.MODE_PRIVATE)
    val previousRun: String = if(prefs.getBoolean("active",false)) "上次未正常结束（不一定是内存不足）："+prefs.getString("last","") else ""
    @Volatile var active=false; private set
    @Volatile var stage="待机"; private set
    @Volatile var low=false; private set
    @Volatile private var trimNotice=""
    @Volatile private var peakRss=0L
    @Volatile private var peakHeap=0L
    @Volatile private var lastText="正在读取内存…"
    private fun mb(n: Long)=MemoryMetrics.mib(n)
    private fun rss(): Long = try {
        File("/proc/self/status").useLines { lines -> MemoryMetrics.rssBytes(lines.firstOrNull{it.startsWith("VmRSS:")}) }
    } catch(_:Exception){-1L}
    @Synchronized fun sample(): String {
        val r=Runtime.getRuntime();val info=ActivityManager.MemoryInfo();am.getMemoryInfo(info)
        val used=r.totalMemory()-r.freeMemory();val rss=rss();val native=Debug.getNativeHeapAllocatedSize()
        low=info.lowMemory;peakRss=maxOf(peakRss,rss);peakHeap=maxOf(peakHeap,used)
        val text="进程驻留内存 ${if(rss<0)"不可读" else mb(rss)+" 兆字节"} · 采样峰值 ${if(peakRss==0L && rss<0)"不可读" else mb(peakRss)+" 兆字节"}\n"+
            "托管堆 ${mb(used)}/${mb(r.maxMemory())} 兆字节 · 原生堆 ${mb(native)} 兆字节\n"+
            "系统可用 ${mb(info.availMem)} 兆字节 · ${if(low)"系统低内存（仅提示）" else "仅监测，不限制生成"}\n$stage"
        lastText=text
        if(active)prefs.edit().putString("last",text).apply()
        return text
    }
    @Synchronized fun begin(message: String) {
        active=true;peakRss=0;peakHeap=0;trimNotice="";stage=message
        prefs.edit().putBoolean("active",true).putString("last",message).commit()
    }
    @Synchronized fun stage(message: String) {
        stage=message
        // Best-effort persistent record before inference. Cannot catch an OS process kill.
        prefs.edit().putBoolean("active",active).putString("last",message+"\n"+lastText).commit()
    }
    @Synchronized fun finish() {
        active=false;stage="待机 / 处理已结束"
        prefs.edit().putBoolean("active",false).putString("last",stage+"\n"+lastText).apply()
    }
    fun diagnostic()="内存诊断（单位为兆字节（二进制）；驻留内存、托管与原生堆不能相加；采样会漏掉瞬时峰值）\n$lastText\n托管堆采样峰值 ${mb(peakHeap)} 兆字节\n$trimNotice\n$previousRun"
    fun trim(level: Int) {trimNotice="收到系统内存通知 级别=$level（仅记录，不中止）"}
}
