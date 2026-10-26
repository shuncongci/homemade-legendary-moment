package xyz.shuncongci.leica

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// UI reads immutable snapshots; no Activity is retained by the background service.
data class ExternalModeStatus(val running:Boolean=false,val ready:Boolean=false,val stopping:Boolean=false,val working:Boolean=false,
    val stage:String="未开启 · 仅开始监听后的新增照片会处理",val currentId:String="",val name:String="",val queued:Int=0,
    val saved:Int=0,val failed:Int=0,val baseline:Int=0,val currentStarted:Long=0,val lastOutput:String="",val lastError:String="",
    val runId:String="",val deletedOriginals:Int=0,val pendingOriginals:Int=0,val retainedOriginals:Int=0,val lastOriginalStatus:String="") {
    fun description():String=buildString{
        append(stage);if(name.isNotEmpty())append("\n").append(name)
        append("\n已保存 ").append(saved).append(" 张 · 排队 ").append(queued).append(" 张 · 失败 ").append(failed).append(" 张")
        if(working && currentStarted>0)append("\n本张已用时 ").append((SystemClock.elapsedRealtime()-currentStarted)/1000).append(" 秒 · 按实际阶段显示，非估算百分比")
        if(deletedOriginals>0 || pendingOriginals>0 || retainedOriginals>0)append("\n原片已处置 ").append(deletedOriginals).append(" 张 · 待系统授权 ").append(pendingOriginals).append(" 张 · 未删除/需核对 ").append(retainedOriginals).append(" 张")
        if(lastOriginalStatus.isNotEmpty())append("\n原片：").append(lastOriginalStatus)
        if(lastError.isNotEmpty())append("\n最近错误：").append(lastError)
    }
}
object ExternalModeRuntime {
    @Volatile var status=ExternalModeStatus();private set
    @Volatile var editorBusy=false
    @Synchronized fun update(change:(ExternalModeStatus)->ExternalModeStatus){status=change(status)}
    @Synchronized fun begin():Boolean {if(status.running || editorBusy)return false;status=ExternalModeStatus(running=true,stage="正在启动；旧照片不会补跑");return true}
}

/** User-started local monitoring. START_NOT_STICKY; never starts at boot or backfills after a restart. */
class ExternalModeService:Service() {
    private val main=Handler(Looper.getMainLooper())
    private val watcher=Executors.newSingleThreadScheduledExecutor{Thread(it,"external-camera-watch")}
    private val renderer=Executors.newSingleThreadExecutor{Thread(it,"external-camera-render")}
    private val stopping=AtomicBoolean(false)
    private val draining=AtomicBoolean(false)
    private val run=UUID.randomUUID().toString()
    private lateinit var settings:ExternalSettings
    private lateinit var journal:ExternalQueueStore
    private lateinit var folders:ExternalFolders
    private lateinit var overlay:ExternalProgressOverlay
    private lateinit var exporter:ExternalPhotoExport
    @Volatile private var gate:ExternalArrivalGate?=null
    private val scanScheduled=AtomicBoolean(false)
    private var started=false
    private var stopReason="已停止；再次开启会重新跳过现有照片"
    @Volatile private var currentCancel:Cancel?=null
    @Volatile private var engine:LeicaEngine?=null
    @Volatile private var observing=false
    // Optional second notification; created and stopped on the main thread only.
    private var monitor:RuntimeMonitor?=null
    // Confined to the single watcher thread: the next safety-net scan and the MediaStore row cursor.
    private var timer:ScheduledFuture<*>?=null
    private var mediaCursor=0L
    private val observer=object:ContentObserver(main){override fun onChange(selfChange:Boolean){
        if(!stopping.get() && gate!=null && scanScheduled.compareAndSet(false,true)){
            runCatching{watcher.execute{
                if(stopping.get()){scanScheduled.set(false);return@execute}
                // One watcher timer for both signals: a notification replaces the idle safety scan.
                timer?.cancel(false)
                timer=watcher.schedule({try{scanNew()}finally{scanScheduled.set(false)}},400,TimeUnit.MILLISECONDS)
            }}.onFailure{scanScheduled.set(false)}
        }
    }}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate();journal=ExternalQueueStore(this);folders=ExternalFolders(this);overlay=ExternalProgressOverlay(this);exporter=ExternalPhotoExport(this,journal)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"外接模式 · 新照片监听",NotificationManager.IMPORTANCE_LOW).apply{description="仅用户开启时监听新照片，显示实际处理阶段并提供停止入口";setSound(null,null)})
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){requestStop();return START_NOT_STICKY}
        if(started)return START_NOT_STICKY
        started=true;settings=ExternalPreferences.load(this)
        ExternalModeRuntime.update{it.copy(running=true,runId=run,stage="正在建立基线 · 现有照片全部跳过")}
        try{
            val notification=notification()
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID,notification)
            ExternalPermissions.startProblem(this,settings)?.let{error(it)}
            if(settings.monitor)monitor=RuntimeMonitor(this,RuntimeMonitor.EXTERNAL_ID,"外接模式 · 运行参数",ExternalModeActivity::class.java){ExternalModeRuntime.status.stage}.takeIf{it.start()}
            watcher.execute{
                try{
                    // Reconcile only owned half-written outputs. Old inputs are abandoned, never resumed.
                    exporter.reconcile()
                    val previousWork=File(cacheDir,"external-camera-work").listFiles().orEmpty()
                    previousWork.filter{it.name!=run}.forEach{check(it.deleteRecursively()){"中断任务的私有临时文件清理失败，未开始新监听"}}
                    if(stopping.get())return@execute
                    val baseline=folders.scan(checkNotNull(settings.folder))
                    if(stopping.get())return@execute
                    mediaCursor=ExternalModePolicy.mediaCursor(baseline)
                    gate=ExternalArrivalGate(baseline,System.currentTimeMillis())
                    publish{it.copy(ready=true,baseline=baseline.size,stage="监听已就绪 · 已跳过现有 ${baseline.size} 张，仅处理此后新增照片")}
                    main.post{if(!stopping.get())try{contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer);observing=true}catch(_:SecurityException){}}
                    if(!stopping.get())scheduleNext(ExternalScanCadence.SETTLING_MS)
                }catch(e:Exception){propagateFatalCause(e);requestStop("无法开始监听：${e.message}")}
            }
        }catch(e:Exception){propagateFatalCause(e);requestStop("启动失败：${e.message}")}
        return START_NOT_STICKY
    }
    /** Runs on the watcher thread only. Known photos are skipped before any per-file stat and MediaStore rows that
     *  existed at the baseline are never read again; the timer is re-armed after every scan (observer or timer). */
    private fun scanNew(){
        if(stopping.get())return
        val folder=checkNotNull(settings.folder)
        try{
            val g=gate?:return
            val scanned=folders.scan(folder,skip=g::known,mediaAfter=mediaCursor)
            val ready=g.observe(scanned,SystemClock.elapsedRealtime())
            if(folder.kind==ExternalFolderKind.MEDIA)mediaCursor=ExternalModePolicy.advanceMediaCursor(mediaCursor,scanned){g.known(it.key) || !ExternalModePolicy.image(it.name)}
            if(stopping.get())return
            ready.forEach{p->if(!stopping.get())journal.enqueue(run,p)}
            if(ready.isNotEmpty()){publishCounts();kick()}
            scheduleNext(ExternalScanCadence.next(g.isSettling,folder.kind,observing))
        }catch(e:Exception){propagateFatalCause(e);requestStop("监听已停止：${e.message}")}
    }
    private fun scheduleNext(delay:Long){
        timer?.cancel(false)
        timer=if(stopping.get())null else runCatching{watcher.schedule({scanNew()},delay,TimeUnit.MILLISECONDS)}.getOrNull()
    }
    private fun kick(){
        if(stopping.get() || !draining.compareAndSet(false,true))return
        renderer.execute{
            try{while(!stopping.get()){
                val job=journal.next(run)?:break
                process(job)
            }}finally{
                draining.set(false)
                if(!stopping.get() && journal.next(run)!=null)kick()
            }
        }
    }
    private fun process(job:ExternalQueueStore.Job){
        val cancel=Cancel();currentCancel=cancel;cancel.cancelled=stopping.get()
        val work=File(cacheDir,"external-camera-work/$run/${job.id}")
        var result:LeicaEngine.Result?=null
        var published=false
        var originalEligible=false
        val wake=(getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:external-photo").apply{setReferenceCounted(false)}
        try{
            cancel.check();journal.state(job.id,"PROCESSING")
            publish{it.copy(working=true,currentId=job.id,name=job.photo.name,stage="读取新增照片 · 等待写入稳定后建立私有副本",currentStarted=SystemClock.elapsedRealtime())}
            wake.acquire(30*60*1000L)
            val source=ExternalPhotoInput.stage(File(work,"input"),job.photo,cancel,
                observe={folders.find(checkNotNull(settings.folder),job.photo.key)},
                open={photo->ExternalCaptureMetadata.openOriginal(this,android.net.Uri.parse(photo.uri))},
                onWait={publish{it.copy(stage="本张新照片仍在写入 · 等待稳定，不读取其他旧照片")}})
            cancel.check()
            if(settings.original==ExternalOriginalPolicy.DELETE_AFTER_SAVE){
                val snapshot=folders.find(checkNotNull(settings.folder),job.photo.key)?.takeIf{it.key==job.photo.key && it.uri==job.photo.uri && it.created>0 && it.created==job.photo.created && !it.pending && it.size==source.length()}
                if(snapshot!=null){journal.rememberOriginal(job.id,snapshot,ExternalOriginalGuard.digest(source.inputStream()){cancel.check()});originalEligible=true}
            }
            val e=LeicaEngine(applicationContext,resultDirectory=File(work,"render"));engine=e
            cancel.cancelled=cancel.cancelled || stopping.get();cancel.check()
            result=e.process(android.net.Uri.fromFile(source),settings.style,cancel,blurEnabled=false,colorEnabled=true,subjectEnabled=false,hdrMode=settings.policy.mode()){message->
                publish{it.copy(stage=message)}
            }
            cancel.check();publish{it.copy(stage=if(result.hdrReady)"导出 HDR · Ultra HDR JPEG"else "导出 SDR · ${settings.format.label}${result.hdrFallback.takeIf{v->v.isNotEmpty()}?.let{v->" · HDR不支持：$v"}.orEmpty()}")}
            val metadata=ExternalCaptureMetadata.prepare(source,File(work,"metadata"),result.width,result.height,settings.metadataWatermark,cancel)
            val output=exporter.save(job,result,settings.format,cancel,metadata.exif,File(work,"export"))
            published=true
            publish{it.copy(lastOutput=output)}
            if(settings.original==ExternalOriginalPolicy.DELETE_AFTER_SAVE){
                val outcome=try{
                    cancel.check()
                    if(!originalEligible)OriginalOutcome(OriginalAction.CHANGED,"原片在复制后发生变化；成片已保存，原片未删除")
                    else if(!metadata.complete)OriginalOutcome(OriginalAction.KEPT,"成片已保留可读取的拍摄EXIF，但无法完整核对所有源格式私有元数据；原片保留")
                    else{
                        publish{it.copy(stage="成片及元数据已保存 · 校验原片和已发布成片，再处置原片")}
                        // Expected output hash was pinned from the private verified file BEFORE publication.
                        // attempt() compares the published bytes to that pinned value; it never learns a new one.
                        ExternalSourceDeletion(this,journal).attempt(job.id,checkNotNull(settings.folder),cancel).result
                    }
                }catch(e:java.util.concurrent.CancellationException){OriginalOutcome(OriginalAction.EXPIRED,"已停止；成片保留，不补删原片")}
                catch(e:Exception){propagateFatalCause(e);OriginalOutcome(OriginalAction.FAILED,"成片已保存；原片安全校验未完成：${e.message}。未重试删除，请人工核对")}
                journal.originalOutcome(job.id,outcome)
                publish{it.copy(lastOriginalStatus=outcome.message)}
            }else publish{it.copy(lastOriginalStatus="原片与成片同时保留${if(metadata.complete)"" else "；部分非EXIF/特殊格式私有扩展无法完整转存，原片已保留"}")}
        }catch(e:Exception){
            propagateFatalCause(e)
            if(!published)journal.state(job.id,if(cancel.cancelled)"CANCELLED"else "FAILED",e.message.orEmpty())
            // A source-retention failure must never relabel/requeue a successfully published output.
            if(!cancel.cancelled)publish{it.copy(lastError="${job.photo.name}：${e.message}")}
        }finally{
            result?.original?.recycle();result?.result?.let{if(it!==result?.original)it.recycle()}
            try{engine?.close()}finally{engine=null;currentCancel=null;if(wake.isHeld)wake.release();work.deleteRecursively()}
            publish{it.copy(working=false,name="",currentId="",stage=if(stopping.get())"正在停止并清理本张半成品"else "监听中 · 等待新增照片；旧照片不补跑")}
            publishCounts()
        }
    }
    private fun publishCounts(){
        if(stopping.get())return
        val queued=journal.count(run,"QUEUED");val saved=journal.count(run,"DONE");val failed=journal.count(run,"FAILED")
        val deleted=journal.originalCount(run,OriginalAction.DELETED,OriginalAction.TRASHED)
        val pending=journal.originalCount(run,OriginalAction.WAIT_PERMISSION)
        val retained=journal.originalCount(run,OriginalAction.KEPT,OriginalAction.CHANGED,OriginalAction.FAILED,OriginalAction.DECLINED,OriginalAction.EXPIRED,OriginalAction.UNKNOWN)
        publish{it.copy(queued=queued,saved=saved,failed=failed,deletedOriginals=deleted,pendingOriginals=pending,retainedOriginals=if(settings.original==ExternalOriginalPolicy.DELETE_AFTER_SAVE)retained else 0)}
    }
    private fun publish(change:(ExternalModeStatus)->ExternalModeStatus){
        ExternalModeRuntime.update(change)
        main.post{if(!stopping.get() && started){getSystemService(NotificationManager::class.java).notify(ID,notification());overlay.render(ExternalModeRuntime.status,settings.overlay)}}
    }
    private fun notification():android.app.Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,ExternalModeActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,ExternalModeService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val s=ExternalModeRuntime.status
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_external_notification).setContentTitle("外接模式 · ${if(s.working)"正在显影"else if(s.stopping)"正在停止"else "新照片监听"}")
            .setContentText(s.stage).setStyle(NotificationCompat.BigTextStyle().bigText(s.description())).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(0,0,s.working).addAction(0,"停止监听",stop).build()
    }
    private fun requestStop(reason:String="已停止；再次开启会重新跳过现有照片"){
        if(!stopping.compareAndSet(false,true))return
        stopReason=reason;currentCancel?.cancelled=true;engine?.cancelRun();watcher.shutdownNow()
        ExternalModeRuntime.update{it.copy(stopping=true,ready=false,stage="正在停止 · 不再接收照片")}
        main.post{monitor?.stop();monitor=null;overlay.hide();if(observing){contentResolver.unregisterContentObserver(observer);observing=false};if(started)getSystemService(NotificationManager::class.java).notify(ID,notification())}
        renderer.execute{
            try{watcher.awaitTermination(5,TimeUnit.SECONDS);journal.cancelRun(run)}finally{
                val saved=journal.count(run,"DONE");val failed=journal.count(run,"FAILED");journal.close()
                main.post{
                    ExternalModeRuntime.update{it.copy(running=false,ready=false,stopping=false,working=false,queued=0,pendingOriginals=0,name="",currentId="",saved=saved,failed=failed,stage=stopReason)}
                    overlay.hide();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                }
            }
        }
        renderer.shutdown()
    }
    override fun onDestroy(){requestStop("系统停止了外接模式；重新开启只监听新照片，不补跑旧队列");overlay.hide();super.onDestroy()}
    companion object {
        private const val CHANNEL="external-camera-progress"
        private const val ID=601
        private const val STOP="xyz.shuncongci.leica.EXTERNAL_STOP"
        fun start(context:Context):Boolean {
            if(!ExternalModeRuntime.begin())return false
            try{ContextCompat.startForegroundService(context,Intent(context,ExternalModeService::class.java));return true}
            catch(e:Exception){ExternalModeRuntime.update{it.copy(running=false,stage="无法启动后台服务：${e.message}")};return false}
        }
        fun stop(context:Context){context.startService(Intent(context,ExternalModeService::class.java).setAction(STOP))}
    }
}
