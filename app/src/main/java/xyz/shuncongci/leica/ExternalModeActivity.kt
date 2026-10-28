package xyz.shuncongci.leica

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Merely opening this page never scans for work, starts a service or processes old images. */
class ExternalModeActivity:AppCompatActivity() {
    private var settings=ExternalSettings()
    private val folders by lazy{ExternalFolders(this)}
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var root:LinearLayout
    private lateinit var folderLabel:TextView
    private lateinit var status:TextView
    private lateinit var permissions:TextView
    private lateinit var output:TextView
    private lateinit var progress:ProgressBar
    private lateinit var style:StudioButton
    private lateinit var policy:StudioButton
    private lateinit var format:StudioButton
    private lateinit var original:StudioButton
    private lateinit var originalPermission:StudioButton
    private lateinit var watermark:StudioButton
    private var requestTreeWrite=false
    private var pendingOriginalId=""
    private var pendingOriginalRun=""
    private var approvalLaunched=false
    private lateinit var overlay:StudioButton
    private lateinit var monitor:StudioButton
    private lateinit var start:StudioButton
    private lateinit var stop:StudioButton
    private val configButtons=mutableListOf<View>()
    private var browsing=false
    private var finishAfterStop=false
    private val tick=object:Runnable{override fun run(){refresh();handler.postDelayed(this,1000)}}
    private val photoPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh()}
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){refresh()}
    private val originalApproval=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->finishOriginalApproval(result.resultCode==RESULT_OK)}
    private val treePicker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null && !ExternalModeRuntime.status.running)lifecycleScope.launch{
            try{
                val read=Intent.FLAG_GRANT_READ_URI_PERMISSION
                if(requestTreeWrite || settings.original==ExternalOriginalPolicy.DELETE_AFTER_SAVE){
                    try{contentResolver.takePersistableUriPermission(uri,read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                    catch(_:SecurityException){contentResolver.takePersistableUriPermission(uri,read)}
                }else contentResolver.takePersistableUriPermission(uri,read)
                requestTreeWrite=false
                val folder=withContext(Dispatchers.IO){folders.tree(uri)}
                setSettings(settings.copy(folder=folder))
            }catch(e:Exception){propagateFatalCause(e);message("无法使用目录",e.message.orEmpty())}
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=13f)=TextView(this).apply{this.text=value;textSize=size;setTextColor(0xffdcd5c9.toInt());setLineSpacing(dp(3).toFloat(),1f)}
    private fun add(view:View,margin:Int=10){root.addView(view,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(margin)})}
    private fun button(title:String,config:Boolean=false,action:()->Unit):StudioButton=StudioButton(this).apply{text=title;setOnClickListener{if(!config || !ExternalModeRuntime.status.running)action()}}.also{if(config)configButtons+=it}
    private fun heading(title:String){add(text(title,16f).apply{setTypeface(null,Typeface.BOLD)},22)}
    /** Same white/black selected state as the editor's mode buttons; screen readers still hear on/off. */
    private fun toggle(button:StudioButton,title:String,on:Boolean){button.text=title;button.isSelected=on;ViewCompat.setStateDescription(button,if(on)"已开启" else "已关闭")}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);settings=ExternalPreferences.load(this)
        pendingOriginalId=state?.getString("original-id").orEmpty();pendingOriginalRun=state?.getString("original-run").orEmpty();requestTreeWrite=state?.getBoolean("tree-write")?:false
        approvalLaunched=state?.getBoolean("approval-launched")?:false
        if(!approvalLaunched){pendingOriginalId="";pendingOriginalRun=""}
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(24));setBackgroundColor(0xff151617.toInt())}
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(root)};setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val b=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);insets}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(button("返回"){leave()},LinearLayout.LayoutParams(dp(68),-2))
        header.addView(text("外接模式",22f).apply{setPadding(dp(14),0,0,0)},LinearLayout.LayoutParams(0,-2,1f));add(header,0)
        add(text("拍一张，自动调色并保存 · 不支持虚化\n等“监听已就绪”后再拍照。已有照片和停止期间的照片不会处理。"))
        buildPermissions()
        heading("相机照片目录")
        folderLabel=text("");add(folderLabel)
        add(button("自动寻找相机相册",true){discover()})
        add(button("浏览所有已授权文件夹",true){browseRoots()})
        add(button("手动选择目录（系统授权）",true){treePicker.launch(null)})
        add(text("只读取所选文件夹，不读取子文件夹。不能选择本应用的导出目录或系统受保护目录。",12f))
        heading("预设调色与导出")
        style=button("",true){chooseStyle()};add(style)
        policy=button("",true){choosePolicy()};add(policy)
        format=button("",true){chooseFormat()};add(format)
        original=button("",true){chooseOriginal()};add(original)
        watermark=button("",true){chooseWatermark()};add(watermark)
        add(text("尽量保留原有拍摄信息；动态照片、HDR 和厂商附加数据可能无法完整转存。相册标识不改画面。",12f))
        add(text("HDR 只存为 Ultra HDR JPEG，需要 Android 14 以上且原片本身带 HDR；普通照片存为 SDR。保持原图尺寸，存到 Pictures/传奇一瞬/外接，不覆盖原片。",12f))
        heading("监听与进度")
        overlay=button("",true){setSettings(settings.copy(overlay=!settings.overlay))};add(overlay)
        monitor=button("",true){setSettings(settings.copy(monitor=!settings.monitor))};add(monitor)
        status=text("");add(status)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{isIndeterminate=true};add(progress)
        start=button("开始监听（跳过现有全部照片）"){startListening()};add(start)
        stop=button("停止监听并取消排队"){ExternalModeService.stop(this)};add(stop)
        add(button("打开系统相机"){
            if(!ExternalModeRuntime.status.ready){message("尚未就绪","请先开始监听，并等待页面显示“监听已就绪”，再去拍照。")}
            else try{startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))}catch(_:android.content.ActivityNotFoundException){message("请手动打开相机","本机没有可直接跳转的相机入口，可切换到你的相机应用。")}
        })
        output=text("",12f);add(output)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){leave()}})
        refresh()
    }
    private fun buildPermissions(){
        heading("权限与后台运行")
        permissions=text("");add(permissions)
        add(button("申请相册／照片读取权限"){requestPhotos()})
        add(button("申请原片位置信息（保留照片GPS元数据）"){
            if(Build.VERSION.SDK_INT>=29)photoPermission.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))else message("无需此权限","Android 8–9 的照片读取权限已覆盖照片内元数据；不会读取手机实时位置。")
        })
        add(button("开启所有文件访问权限"){requestAllFiles()})
        add(button("目录读写授权（删除原片需要）",true){requestTreeWrite=true;treePicker.launch(settings.folder?.takeIf{it.kind==ExternalFolderKind.TREE}?.let{Uri.parse(it.location)})})
        originalPermission=button("确认删除原片的系统授权"){showOriginalPermission()};add(originalPermission)
        add(button("允许通知（后台进度／停止入口）"){
            if(Build.VERSION.SDK_INT>=33 && !ExternalPermissions.notifications(this))notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        })
        add(button("允许显示在其他应用上层（进度弹窗）"){openSetting(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))})
        add(button("设置后台不限制／电池优化"){batteryGuide()})
        add(text("以上权限需要你在系统页面手动打开。删除原片如需系统确认，会先保留原片，再在这里逐张确认。部分机型还要允许自启动；系统仍可能关闭后台。",12f))
    }
    private fun setSettings(value:ExternalSettings){
        if(ExternalModeRuntime.status.running)return
        try{ExternalPreferences.save(this,value);settings=value;refresh()}catch(e:Exception){message("设置未保存",e.message.orEmpty())}
    }
    private fun refresh(){
        if(!::status.isInitialized)return
        val s=ExternalModeRuntime.status
        folderLabel.text=settings.folder?.let{"已选择：${it.label}\n类型：${when(it.kind){ExternalFolderKind.DIRECTORY->"本机文件夹";ExternalFolderKind.MEDIA->"系统相册";ExternalFolderKind.TREE->"手动授权目录"}}"}?:"尚未选择相机目录"
        style.text="调色风格 · ${settings.style.label}";policy.text=settings.policy.label;format.text="默认 SDR 格式 · ${settings.format.label}"
        original.text="原片处理 · ${settings.original.label}"
        toggle(watermark,"徕卡一瞬相册标识",settings.metadataWatermark)
        originalPermission.text="本次原片待系统授权 · ${s.pendingOriginals} 张"
        originalPermission.isEnabled=s.running && !s.stopping && s.pendingOriginals>0 && !browsing && pendingOriginalId.isEmpty()
        toggle(overlay,"处理时悬浮进度",settings.overlay)
        toggle(monitor,"通知栏显示运行参数",settings.monitor)
        permissions.text="相册全量读取：${if(ExternalPermissions.allPhotos(this))"已允许"else "未允许（仅选中照片不等于全部相册）"}\n所有文件访问：${if(ExternalPermissions.allFiles(this))"已允许"else if(Build.VERSION.SDK_INT==29)"Android 10 请使用系统目录授权"else "未允许"}\n通知：${if(ExternalPermissions.notifications(this))"已允许"else "未允许"}　悬浮窗：${if(ExternalPermissions.overlay(this))"已允许"else "未允许"}\n忽略电池优化：${if(ExternalPermissions.battery(this))"已允许（仍建议检查厂家后台限制）"else "未允许，建议设置为不限制"}"
        permissions.append("\n原片GPS元数据读取：${if(ExternalPermissions.originalMetadata(this))"已允许"else "未允许（媒体库原片需要，不是实时定位）"}")
        if(settings.folder?.kind==ExternalFolderKind.TREE)permissions.append("\n所选目录写入：${if(ExternalPermissions.treeWrite(this,checkNotNull(settings.folder)))"已授权"else "未授权（同时保留不需要）"}")
        configButtons.forEach{it.isEnabled=!s.running && !browsing}
        start.isEnabled=!s.running && !browsing;stop.isEnabled=s.running && !s.stopping
        status.text=s.description();progress.visibility=if(s.running && (!s.ready || s.working || s.stopping))View.VISIBLE else View.GONE
        output.text="最后保存：${s.lastOutput.ifEmpty{"暂无"}}\n重新开启后，只处理之后拍的新照片。"
        if(finishAfterStop && !s.running){finishAfterStop=false;finish()}
    }
    private fun requestPhotos(){
        val required=mutableListOf(if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34)required+=Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if(Build.VERSION.SDK_INT>=29)required+=Manifest.permission.ACCESS_MEDIA_LOCATION
        if(Build.VERSION.SDK_INT<=28)required+=Manifest.permission.WRITE_EXTERNAL_STORAGE
        photoPermission.launch(required.toTypedArray())
    }
    private fun requestAllFiles(){
        if(Build.VERSION.SDK_INT>=30)message("所有文件访问权限","用于列出共享存储文件夹并监听你选择的相机目录；不会扫描受保护应用目录，也不会上传文件。请在下一页手动允许。"){
            openSetting(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")),Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }else if(Build.VERSION.SDK_INT<=28)requestPhotos() else message("Android 10 目录授权","此版本没有所有文件访问开关，请使用“手动选择目录（系统授权）”。")
    }
    private fun openSetting(intent:Intent,fallback:Intent=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))){
        try{startActivity(intent)}catch(_:android.content.ActivityNotFoundException){try{startActivity(fallback)}catch(_:android.content.ActivityNotFoundException){message("请手动打开系统设置","应用信息 → 传奇一瞬 → 权限／后台电池设置。")}}
    }
    private fun batteryGuide(){
        AlertDialog.Builder(this).setTitle("建议开启后台不限制").setMessage("请在系统的应用信息 → 电池／后台耗电管理中选择“不限制”或“允许后台运行”。部分机型还需要允许自启动。设置后仍可能被系统关闭，请留意监听状态。")
            .setPositiveButton("申请忽略电池优化"){_,_->openSetting(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName")),Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}
            .setNeutralButton("打开应用设置"){_,_->openSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}.setNegativeButton("稍后",null).show()
    }
    private fun discover(){
        if(!ExternalPermissions.allFiles(this) && !ExternalPermissions.allPhotos(this)){message("需要目录读取授权","请先允许全部照片或所有文件访问；也可直接使用系统目录选择器授权一个相机目录。授权后不会自动开始处理。");return}
        browsing=true;refresh()
        lifecycleScope.launch{
            try{
                val found=withContext(Dispatchers.IO){folders.cameraFolders()}
                if(found.isEmpty())message("未找到相机相册","相机目录可能尚无照片或不叫 Camera，请使用手动目录选择。")
                else AlertDialog.Builder(this@ExternalModeActivity).setTitle("选择相机目录（不会开始处理）").setItems(found.map{it.label}.toTypedArray()){_,i->setSettings(settings.copy(folder=found[i]))}.setNegativeButton("取消",null).show()
            }catch(e:Exception){propagateFatalCause(e);message("查找失败",e.message.orEmpty())}finally{browsing=false;refresh()}
        }
    }
    private fun browseRoots(){
        if(!ExternalPermissions.allFiles(this)){message("需要所有文件访问授权","请开启“所有文件访问”，或使用不需要广泛权限的系统目录选择器。");return}
        lifecycleScope.launch{
            try{val roots=withContext(Dispatchers.IO){folders.roots()};AlertDialog.Builder(this@ExternalModeActivity).setTitle("选择共享存储").setItems(roots.map{it.path}.toTypedArray()){_,i->browse(roots[i])}.setNegativeButton("取消",null).show()}
            catch(e:Exception){propagateFatalCause(e);message("目录不可用",e.message.orEmpty())}
        }
    }
    private fun browse(file:File){
        browsing=true;refresh()
        lifecycleScope.launch{
            try{
                val children=withContext(Dispatchers.IO){folders.children(file)}
                AlertDialog.Builder(this@ExternalModeActivity).setTitle(file.path).setItems(children.map{"📁 ${it.name}"}.toTypedArray()){_,i->browse(children[i])}
                    .setPositiveButton("监听此文件夹"){_,_->try{setSettings(settings.copy(folder=folders.directory(file)))}catch(e:Exception){message("不能选择",e.message.orEmpty())}}
                    .setNeutralButton("上一级"){_,_->val parent=file.parentFile;if(parent!=null && folders.roots().any{file.canonicalPath!=it.canonicalPath && file.canonicalPath.startsWith(it.canonicalPath+File.separator)})browse(parent)else browseRoots()}
                    .setNegativeButton("取消",null).show()
            }catch(e:Exception){propagateFatalCause(e);message("无法列出目录",e.message.orEmpty())}finally{browsing=false;refresh()}
        }
    }
    private fun chooseStyle(){val options=LeicaStyle.entries;AlertDialog.Builder(this).setTitle("预设调色（不虚化）").setSingleChoiceItems(options.map{it.label}.toTypedArray(),options.indexOf(settings.style)){dialog,i->setSettings(settings.copy(style=options[i]));dialog.dismiss()}.setNegativeButton("取消",null).show()}
    private fun choosePolicy(){val options=ExternalExportPolicy.entries;AlertDialog.Builder(this).setTitle("自动导出策略").setSingleChoiceItems(options.map{it.label}.toTypedArray(),options.indexOf(settings.policy)){dialog,i->setSettings(settings.copy(policy=options[i]));dialog.dismiss()}.setNegativeButton("取消",null).show()}
    private fun chooseFormat(){val options=ExportFormat.available(Build.VERSION.SDK_INT);AlertDialog.Builder(this).setTitle("默认 SDR 导出格式").setSingleChoiceItems(options.map{it.label}.toTypedArray(),options.indexOf(settings.format)){dialog,i->setSettings(settings.copy(format=options[i]));dialog.dismiss()}.setNegativeButton("取消",null).show()}
    private fun chooseWatermark(){
        if(settings.metadataWatermark){setSettings(settings.copy(metadataWatermark=false));return}
        AlertDialog.Builder(this).setTitle("徕卡一瞬相册标识").setMessage("仅写入照片信息，不添加画面水印。建议使用 JPEG，标识显示以相册支持为准。")
            .setPositiveButton("开启并使用JPEG"){_,_->setSettings(settings.copy(metadataWatermark=true,format=ExportFormat.JPEG))}
            .setNeutralButton("开启，不改格式"){_,_->setSettings(settings.copy(metadataWatermark=true))}.setNegativeButton("取消",null).show()
    }
    private fun chooseOriginal(){
        val options=ExternalOriginalPolicy.entries
        AlertDialog.Builder(this).setTitle("成片保存后的原片处理").setSingleChoiceItems(options.map{it.label}.toTypedArray(),options.indexOf(settings.original)){dialog,i->
            dialog.dismiss()
            if(options[i]==ExternalOriginalPolicy.KEEP)setSettings(settings.copy(original=options[i],deletionConsent=0))
            else AlertDialog.Builder(this).setTitle(ExternalDeletionConsent.TITLE).setMessage(ExternalDeletionConsent.MESSAGE)
                .setPositiveButton(ExternalDeletionConsent.ACCEPT){_,_->setSettings(settings.copy(original=ExternalOriginalPolicy.DELETE_AFTER_SAVE,deletionConsent=ExternalDeletionConsent.VERSION))}.setNegativeButton(ExternalDeletionConsent.DECLINE,null).show()
        }.setNegativeButton("取消",null).show()
    }
    private fun showOriginalPermission(){
        val state=ExternalModeRuntime.status;val folder=settings.folder?:return
        if(!state.running || state.stopping || pendingOriginalId.isNotEmpty())return
        browsing=true;refresh()
        lifecycleScope.launch{
            try{
                val attempt=withContext(Dispatchers.IO){ExternalQueueStore(applicationContext).use{journal->
                    val item=journal.nextOriginalPermission(state.runId)?:return@use null
                    ExternalSourceDeletion(applicationContext,journal).attempt(item.job.id,folder,Cancel(),requestPermission=true)
                }}
                if(attempt?.sender!=null){
                    pendingOriginalId=attempt.id;pendingOriginalRun=attempt.run
                    AlertDialog.Builder(this@ExternalModeActivity).setTitle("仅授权这张已处理原片").setMessage("成片已保存，原片和成片也已核对。确认后只删除这张原片；取消则两张都保留。")
                        .setPositiveButton("打开系统确认"){_,_->
                            try{
                                if(ExternalModeRuntime.status.let{!it.running || it.stopping || it.runId!=attempt.run})finishOriginalApproval(false)
                                else{approvalLaunched=true;originalApproval.launch(IntentSenderRequest.Builder(checkNotNull(attempt.sender)).build())}
                            }
                            catch(e:Exception){finishOriginalApproval(false);message("系统授权未打开",e.message.orEmpty())}
                        }.setNegativeButton("保留原片"){_,_->finishOriginalApproval(false)}.setOnCancelListener{finishOriginalApproval(false)}.show()
                }else if(attempt!=null)message("原片处置",attempt.result.message)
            }catch(e:Exception){propagateFatalCause(e);message("原片保留",e.message.orEmpty())}
            finally{browsing=false;refresh()}
        }
    }
    private fun finishOriginalApproval(granted:Boolean){
        val id=pendingOriginalId;val run=pendingOriginalRun;pendingOriginalId="";pendingOriginalRun="";approvalLaunched=false
        val folder=settings.folder?:return;if(id.isEmpty())return
        lifecycleScope.launch{
            try{
                val note=withContext(Dispatchers.IO){ExternalQueueStore(applicationContext).use{journal->
                    val deletion=ExternalSourceDeletion(applicationContext,journal)
                    if(!granted){deletion.decline(id);"未同意系统授权，原片和成片同时保留"}
                    else if(!ExternalModeRuntime.status.let{it.running && !it.stopping && it.runId==run})"监听已停止或应用已重启；不补删原片"
                    else deletion.attempt(id,folder,Cancel(),permissionTried=true).result.message
                }}
                ExternalModeRuntime.update{it.copy(lastOriginalStatus=note)};message("原片删除结果",note)
            }catch(e:Exception){propagateFatalCause(e);message("请人工核对原片",e.message.orEmpty())}
            finally{refresh()}
        }
    }
    private fun startListening(){
        ExternalPermissions.startProblem(this,settings)?.let{message("尚不能开始",it);return}
        if(ExternalModeRuntime.editorBusy){message("编辑器仍在处理","请先等待或取消手动编辑任务，再开启外接模式，避免同时处理照片。");return}
        message("开始监听新增照片","当前目录已有的全部照片将被跳过。请等“监听已就绪”后再拍照。停止期间的照片和旧队列都不会补跑。${if(!ExternalPermissions.battery(this))"\n建议先将后台电池设为不限制。"else ""}${if(!ExternalPermissions.notifications(this))"\n通知未允许，后台进度／停止入口可能不可见，请在本页停止。"else ""}${if(settings.overlay && !ExternalPermissions.overlay(this))"\n悬浮窗未允许：改用页面／通知显示进度。"else ""}"){
            if(!ExternalModeService.start(this))message("未开始","请确认没有其他显影任务，或检查系统是否禁止启动前台服务。")
            refresh()
        }
    }
    private fun leave(){
        if(!ExternalModeRuntime.status.running){finish();return}
        AlertDialog.Builder(this).setTitle("外接模式正在运行").setItems(arrayOf("继续后台监听，返回桌面","停止监听并返回编辑器","留在此页")){_,i->when(i){0->moveTaskToBack(true);1->{finishAfterStop=true;ExternalModeService.stop(this)}}}.show()
    }
    private fun message(title:String,body:String,confirm:(()->Unit)?=null){AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(if(confirm==null)"知道了"else "继续"){_,_->confirm?.invoke()}.apply{if(confirm!=null)setNegativeButton("取消",null)}.show()}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("original-id",pendingOriginalId);outState.putString("original-run",pendingOriginalRun);outState.putBoolean("tree-write",requestTreeWrite);outState.putBoolean("approval-launched",approvalLaunched);super.onSaveInstanceState(outState)}
    override fun onResume(){super.onResume();settings=ExternalPreferences.load(this);handler.removeCallbacks(tick);handler.post(tick)}
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
    override fun onDestroy(){handler.removeCallbacks(tick);super.onDestroy()}
}
