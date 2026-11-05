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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var folderRow:SettingsRow
    private lateinit var status:TextView
    private lateinit var photoRow:SettingsRow;private lateinit var locationRow:SettingsRow;private lateinit var backgroundRow:SettingsRow
    private lateinit var output:TextView
    private lateinit var progress:ProgressBar
    private lateinit var style:SettingsRow
    private lateinit var policy:SettingsRow
    private lateinit var format:SettingsRow
    private lateinit var original:SettingsRow;private lateinit var directoryWriteRow:SettingsRow
    private lateinit var originalPermission:StudioButton
    private lateinit var watermark:SettingsRow
    private var requestTreeWrite=false
    private var pendingOriginalId=""
    private var pendingOriginalRun=""
    private var approvalLaunched=false
    private lateinit var overlay:SettingsRow
    private lateinit var monitor:SettingsRow
    private lateinit var start:StudioButton
    private lateinit var stop:StudioButton
    private lateinit var cameraButton:StudioButton;private lateinit var phaseLabel:TextView
    private val configButtons=mutableListOf<View>()
    private var compactActions=false
    private var browsing=false
    private var finishAfterStop=false
    private val panels by lazy{ExternalPanels(this)}
    private var panelAnchor:View?=null
    private lateinit var backButton:StudioButton
    private fun anchor():View=panelAnchor?.takeIf{it.isAttachedToWindow && it.isShown}?:backButton
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
    private fun button(title:String,config:Boolean=false,action:()->Unit):StudioButton=StudioButton(this).apply{text=title;setOnClickListener{if(!config || !ExternalModeRuntime.status.running){panelAnchor=this;action()}}}.also{if(config)configButtons+=it}
    private fun glyph(button:StudioButton,kind:WorkspaceGlyph.Kind){
        button.setCompoundDrawables(WorkspaceGlyph(kind){button.currentTextColor}.apply{setBounds(0,0,dp(20),dp(20))},null,null,null);button.compoundDrawablePadding=dp(7)
    }
    private fun headerAction(kind:WorkspaceGlyph.Kind,description:String,action:()->Unit)=button(""){action()}.apply{
        contentDescription=description;borderVisible=false;cornerRadiusDp=13f;setPadding(dp(13),dp(13),dp(13),dp(13));glyph(this,kind)
    }
    /** Flat reference groups: every row remains reachable without expanding nested cards. */
    private fun group(title:String,build:()->Unit){
        val parent=root
        add(text(title,12f).apply{setTextColor(0xff8f908b.toInt());setPadding(dp(5),0,0,0)},22)
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=android.graphics.drawable.GradientDrawable().apply{setColor(0xff1c1d1f.toInt());cornerRadius=dp(16).toFloat()};clipToOutline=true}
        add(card,8);root=card;try{build()}finally{root=parent}
    }
    private fun setting(title:String,kind:WorkspaceGlyph.Kind,config:Boolean=false,switching:Boolean=false,action:()->Unit):SettingsRow {
        if(root.childCount>0)root.addView(View(this).apply{setBackgroundColor(0xff303233.toInt());importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,dp(1)).apply{marginStart=dp(62)})
        return SettingsRow(this,title,kind,switching).apply{setOnClickListener{if(!config || !ExternalModeRuntime.status.running){panelAnchor=this;action()}}}.also{root.addView(it,LinearLayout.LayoutParams(-1,-2));if(config)configButtons+=it}
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state);settings=ExternalPreferences.load(this)
        pendingOriginalId=state?.getString("original-id").orEmpty();pendingOriginalRun=state?.getString("original-run").orEmpty();requestTreeWrite=state?.getBoolean("tree-write")?:false
        approvalLaunched=state?.getBoolean("approval-launched")?:false
        if(!approvalLaunched){pendingOriginalId="";pendingOriginalRun=""}
        val page=FrameLayout(this).apply{setBackgroundColor(0xff151617.toInt())}
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(12))}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        backButton=headerAction(WorkspaceGlyph.Kind.BACK,"返回编辑器"){leave()}
        header.addView(backButton,LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(text("外接模式",20f).apply{setPadding(dp(12),0,dp(12),0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(headerAction(WorkspaceGlyph.Kind.INFO,"外接运行详情"){message("运行详情",ExternalModeRuntime.status.description()+"\n\n"+output.text)},LinearLayout.LayoutParams(dp(48),dp(48)))
        val runCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(12));background=android.graphics.drawable.GradientDrawable().apply{setColor(0xff202224.toInt());cornerRadius=dp(26).toFloat();setStroke(dp(1),0xff36393a.toInt())};elevation=dp(14).toFloat();isClickable=true;clipToOutline=true}
        runCard.addView(header,LinearLayout.LayoutParams(-1,-2))
        val runCopy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        phaseLabel=text("",16f).apply{setTypeface(null,Typeface.BOLD);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};runCopy.addView(phaseLabel)
        status=text("",12f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=dp(36);gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true};runCopy.addView(status)
        // In a short/large-font window only the status copy may scroll; actions and approval stay fixed.
        val statusScroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;addView(runCopy)}
        runCard.addView(statusScroll,LinearLayout.LayoutParams(-1,-2,1f))
        start=button("开始监听"){if(ExternalWorkspacePolicy.canStart(ExternalModeRuntime.status.running,browsing,ExternalModeRuntime.editorBusy))startListening()}
        stop=button("停止监听"){val current=ExternalModeRuntime.status;if(current.running && !current.stopping)ExternalModeService.stop(this)}
        start.contentDescription="开始监听；先跳过全部现有照片，不补处理历史"
        stop.contentDescription="停止监听并取消排队；等待当前步骤安全退出"
        glyph(start,WorkspaceGlyph.Kind.EXTERNAL);glyph(stop,WorkspaceGlyph.Kind.STOP)
        cameraButton=button("打开相机"){
            val current=ExternalModeRuntime.status
            if(!ExternalWorkspacePolicy.canOpenCamera(current.running,current.ready,current.stopping)){message("尚未就绪","请先开始监听，并等待页面显示“监听已就绪”，再去拍照。")}
            else try{startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))}catch(_:android.content.ActivityNotFoundException){message("请手动打开相机","本机没有可直接跳转的相机入口，可切换到你的相机应用。")}
        }.apply{contentDescription="监听就绪后打开系统相机；本应用不内置拍摄";glyph(this,WorkspaceGlyph.Kind.CAMERA)}
        compactActions=ReferenceWorkspacePolicy.chrome(resources.configuration.screenHeightDp,resources.configuration.fontScale).compact
        val runActions=if(compactActions)LinearLayout(this).apply{
            gravity=Gravity.CENTER_VERTICAL
            listOf(start,stop,cameraButton).forEach{addView(it,LinearLayout.LayoutParams(0,dp(48),1f).apply{marginStart=dp(4);marginEnd=dp(4)})}
        }else WorkspaceActionRow(this).apply{addView(start);addView(stop);addView(cameraButton)}
        if(compactActions){
            listOf(start,stop,cameraButton).forEach{it.text="";it.gravity=Gravity.CENTER;it.setPadding(dp(12),dp(12),dp(12),dp(12))}
            phaseLabel.textSize=14f;status.textSize=11f;status.maxLines=1
        }
        runCard.addView(runActions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{isIndeterminate=true;progressTintList=android.content.res.ColorStateList.valueOf(0xffc4b8a6.toInt())};runCard.addView(progress,LinearLayout.LayoutParams(-1,dp(2)).apply{topMargin=dp(4)})
        originalPermission=button("确认删除原片的系统授权"){showOriginalPermission()}.apply{if(compactActions){textSize=11f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}}
        // Runtime approval never moves into the scrolling settings or a hidden dialog.
        runCard.addView(originalPermission,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        val scroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;clipToPadding=false;addView(root)}
        // Full-window scroll FIRST, floating rounded island LAST: no opaque full-width header strip.
        page.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        page.addView(runCard,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{leftMargin=dp(14);rightMargin=dp(14);topMargin=dp(8)})
        val settingsRoot=root
        var safeLeft=0;var safeTop=0;var safeRight=0;var safeBottom=0
        fun placeIsland(){
            val p=runCard.layoutParams as FrameLayout.LayoutParams
            val width=(page.width-safeLeft-safeRight-dp(28)).coerceIn(0,dp(680))
            if(width>0 && (p.width!=width || p.topMargin!=safeTop+dp(8) || p.leftMargin!=safeLeft+dp(14) || p.rightMargin!=safeRight+dp(14) || p.bottomMargin!=safeBottom+dp(8))){p.width=width;p.leftMargin=safeLeft+dp(14);p.rightMargin=safeRight+dp(14);p.topMargin=safeTop+dp(8);p.bottomMargin=safeBottom+dp(8);runCard.layoutParams=p}
            val top=FullscreenMotion.islandScrollTop(safeTop+dp(8),runCard.height,dp(12))
            if(settingsRoot.paddingTop!=top || settingsRoot.paddingLeft!=safeLeft+dp(14) || settingsRoot.paddingRight!=safeRight+dp(14) || settingsRoot.paddingBottom!=safeBottom+dp(20))settingsRoot.setPadding(safeLeft+dp(14),top,safeRight+dp(14),safeBottom+dp(20))
        }
        page.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->placeIsland()}
        runCard.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->placeIsland()}
        group("运行与通知"){
            monitor=setting("通知栏参数",WorkspaceGlyph.Kind.BELL,true,true){setSettings(settings.copy(monitor=!settings.monitor))}
        }
        group("权限"){buildPermissions()}
        group("目录与输出"){
            folderRow=setting("相机照片目录",WorkspaceGlyph.Kind.FOLDER,true){showFolderOptions()}
            style=setting("调色风格",WorkspaceGlyph.Kind.PALETTE,true){chooseStyle()}
            policy=setting("输出策略",WorkspaceGlyph.Kind.OUTPUT,true){choosePolicy()}
            format=setting("SDR 输出格式",WorkspaceGlyph.Kind.FILE,true){chooseFormat()}
        }
        group("原片与标识"){
            original=setting("原片处理",WorkspaceGlyph.Kind.ORIGINAL,true){chooseOriginal()}
            directoryWriteRow=setting("目录读写授权",WorkspaceGlyph.Kind.LOCK,true){requestTreeWrite=true;treePicker.launch(settings.folder?.takeIf{it.kind==ExternalFolderKind.TREE}?.let{Uri.parse(it.location)})}
            watermark=setting("徕卡一瞬相册标识",WorkspaceGlyph.Kind.WATERMARK,true,true){chooseWatermark()}
            overlay=setting("处理时悬浮进度",WorkspaceGlyph.Kind.OVERLAY,true,true){setSettings(settings.copy(overlay=!settings.overlay))}
        }
        output=text("",11f).apply{setTextIsSelectable(true)}
        add(text("只处理手动开启并就绪后的新照片，不补历史、不支持虚化。默认同时保留原片，保存到 Pictures/传奇一瞬/外接。HDR 需 Android 14+ 和带 HDR 的原片，使用 Ultra HDR JPEG；普通照片保存为 SDR。",11f),16)
        add(button("运行详情与最后保存位置"){message("运行详情",ExternalModeRuntime.status.description()+"\n\n"+output.text)},10)
        ViewCompat.setOnApplyWindowInsetsListener(page){_,insets->val b=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());safeLeft=b.left;safeTop=b.top;safeRight=b.right;safeBottom=b.bottom;placeIsland();insets}
        setContentView(page)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){leave()}})
        refresh()
    }
    private fun showFolderOptions(){
        if(ExternalModeRuntime.status.running)return
        panels.options(folderRow,"相机照片目录",listOf("自动寻找相机相册","浏览所有已授权文件夹","手动选择目录（系统授权）")){i->
            if(!ExternalModeRuntime.status.running)when(i){0->discover();1->browseRoots();2->treePicker.launch(null)}
        }
    }
    private fun buildPermissions(){
        photoRow=setting("相册／照片读取",WorkspaceGlyph.Kind.SHIELD){requestPhotos()}
        locationRow=setting("原图位置信息",WorkspaceGlyph.Kind.LOCATION){
            if(Build.VERSION.SDK_INT>=29)photoPermission.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))else message("无需此权限","Android 8–9 的照片读取权限已覆盖照片内元数据；不会读取手机实时位置。")
        }
        backgroundRow=setting("悬浮窗 · 通知 · 后台",WorkspaceGlyph.Kind.LOCK){
            panels.options(backgroundRow,"后台与目录授权",listOf("通知权限与后台进度","悬浮进度权限","后台不限制／电池优化","所有文件访问权限")){i->when(i){
                0->if(Build.VERSION.SDK_INT>=33 && !ExternalPermissions.notifications(this))notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else openSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                1->openSetting(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                2->batteryGuide();3->requestAllFiles()
            }}
        }
    }
    private fun setSettings(value:ExternalSettings){
        if(ExternalModeRuntime.status.running)return
        try{ExternalPreferences.save(this,value);settings=value;refresh()}catch(e:Exception){message("设置未保存",e.message.orEmpty())}
    }
    private fun refresh(){
        if(!::status.isInitialized)return
        val s=ExternalModeRuntime.status
        folderRow.subtitle(settings.folder?.label?:"尚未选择相机目录")
        style.subtitle(settings.style.label);policy.subtitle(settings.policy.label);format.subtitle(settings.format.label)
        val tree=settings.folder?.takeIf{it.kind==ExternalFolderKind.TREE}
        directoryWriteRow.subtitle(if(tree==null)"系统目录可单独授权；同时保留无需写权限"else if(ExternalPermissions.treeWrite(this,tree))"所选目录已获写权限；删除仍需原有确认"else "所选目录未获写权限；同时保留不受影响")
        original.subtitle(settings.original.label)
        watermark.setChecked(settings.metadataWatermark);watermark.subtitle(if(settings.metadataWatermark)"已开启 · 只写照片信息，不改画面"else "已关闭 · 仅 JPEG 相册标识")
        originalPermission.text=if(compactActions)"原片授权 · ${s.pendingOriginals} 张"else "本次原片待系统授权 · ${s.pendingOriginals} 张"
        originalPermission.contentDescription="本次原片待系统授权 · ${s.pendingOriginals} 张；只在明确确认并通过系统授权后处置，取消则原片和成片同时保留"
        originalPermission.visibility=if(s.running && s.pendingOriginals>0)View.VISIBLE else View.GONE
        originalPermission.isEnabled=s.running && !s.stopping && s.pendingOriginals>0 && !browsing && pendingOriginalId.isEmpty()
        overlay.setChecked(settings.overlay);overlay.subtitle(if(settings.overlay)"处理时显示；需系统悬浮窗授权"else "已关闭")
        monitor.setChecked(settings.monitor);monitor.subtitle(if(settings.monitor)"显示运行参数；需系统通知授权"else "已关闭 · 可手动开启")
        val photos=ExternalPermissions.allPhotos(this);val gps=ExternalPermissions.originalMetadata(this)
        photoRow.permission(photos);photoRow.subtitle(if(photos)"已授权全部照片"else "未授权全部照片 · 仅选中照片不等于全量")
        locationRow.permission(gps);locationRow.subtitle(if(gps)"已授权 · 保留照片 GPS；不读实时定位"else "保留照片 GPS 元数据所需；不读实时定位")
        backgroundRow.subtitle("通知${if(ExternalPermissions.notifications(this))"已允许"else "未允许"} · 悬浮窗${if(ExternalPermissions.overlay(this))"已允许"else "未允许"}\n后台${if(ExternalPermissions.battery(this))"不限制（仍需检查厂商设置）"else "建议设为不限制"}\n所有文件${if(ExternalPermissions.allFiles(this))"已授权"else "未授权；可改用系统目录授权"}")
        configButtons.forEach{it.isEnabled=!s.running && !browsing}
        val listenerUi=UiPolishPolicy.listenerAction(s.running,s.stopping,browsing,ExternalModeRuntime.editorBusy)
        start.isEnabled=!s.running && listenerUi.enabled;stop.isEnabled=s.running && listenerUi.enabled
        start.visibility=if(s.running)View.GONE else View.VISIBLE;stop.visibility=if(s.running)View.VISIBLE else View.GONE
        start.isSelected=listenerUi.highlighted;stop.isSelected=false
        if(s.running)stop.text=if(compactActions)""else listenerUi.label else start.text=if(compactActions)""else listenerUi.label
        stop.contentDescription=if(s.stopping)"正在停止，请等待当前步骤安全退出"else "停止监听并取消排队；等待当前步骤安全退出"
        ViewCompat.setStateDescription(if(s.running)stop else start,if(s.stopping)"正在停止，不可重复"else if(s.running)"监听运行中"else "监听未开启")
        cameraButton.isEnabled=ExternalWorkspacePolicy.canOpenCamera(s.running,s.ready,s.stopping)
        val phase=ExternalWorkspacePolicy.phase(s.running,s.ready,s.working,s.stopping)
        if(phaseLabel.text.toString()!=phase)phaseLabel.text=phase
        val recentError=s.lastError.ifEmpty{s.lastMetadataError}
        val summary=if(recentError.isNotEmpty())"${ReferenceWorkspacePolicy.oneLine(recentError)} · 详情 ›"else "已保存 ${s.saved} · 排队 ${s.queued} · 失败 ${s.failed}"
        if(status.text.toString()!=summary)status.text=summary
        status.contentDescription=s.description();status.setOnClickListener{panelAnchor=status;message("运行详情",ExternalModeRuntime.status.description())}
        status.setTextColor(if(recentError.isNotEmpty())0xffd8a18f.toInt()else 0xff9f9a92.toInt())
        progress.visibility=if(s.running && (!s.ready || s.working || s.stopping))View.VISIBLE else View.INVISIBLE
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
        panels.actions(anchor(),"建议开启后台不限制","请在系统的应用信息 → 电池／后台耗电管理中选择“不限制”或“允许后台运行”。部分机型还需要允许自启动。设置后仍可能被系统关闭，请留意监听状态。",listOf(
            ExternalPanels.Action("申请忽略电池优化"){openSetting(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName")),Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))},
            ExternalPanels.Action("打开应用设置"){openSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}),"稍后")
    }
    private fun discover(){
        if(!ExternalPermissions.allFiles(this) && !ExternalPermissions.allPhotos(this)){message("需要目录读取授权","请先允许全部照片或所有文件访问；也可直接使用系统目录选择器授权一个相机目录。授权后不会自动开始处理。");return}
        browsing=true;refresh()
        lifecycleScope.launch{
            try{
                val found=withContext(Dispatchers.IO){folders.cameraFolders()}
                if(found.isEmpty())message("未找到相机相册","相机目录可能尚无照片或不叫 Camera，请使用手动目录选择。")
                else panels.options(folderRow,"选择相机目录（不会开始处理）",found.map{it.label}){i->setSettings(settings.copy(folder=found[i]))}
            }catch(e:Exception){propagateFatalCause(e);message("查找失败",e.message.orEmpty())}finally{browsing=false;refresh()}
        }
    }
    private fun browseRoots(){
        if(!ExternalPermissions.allFiles(this)){message("需要所有文件访问授权","请开启“所有文件访问”，或使用不需要广泛权限的系统目录选择器。");return}
        lifecycleScope.launch{
            try{val roots=withContext(Dispatchers.IO){folders.roots()};panels.options(folderRow,"选择共享存储",roots.map{it.path}){i->browse(roots[i])}}
            catch(e:Exception){propagateFatalCause(e);message("目录不可用",e.message.orEmpty())}
        }
    }
    private fun browse(file:File){
        browsing=true;refresh()
        lifecycleScope.launch{
            try{
                val children=withContext(Dispatchers.IO){folders.children(file)}
                panels.directory(folderRow,file.path,children.map{"目录 · ${it.name}"},choose={i->browse(children[i])},
                    use={try{setSettings(settings.copy(folder=folders.directory(file)))}catch(e:Exception){message("不能选择",e.message.orEmpty())}},
                    up={val parent=file.parentFile;if(parent!=null && folders.roots().any{file.canonicalPath!=it.canonicalPath && file.canonicalPath.startsWith(it.canonicalPath+File.separator)})browse(parent)else browseRoots()})
            }catch(e:Exception){propagateFatalCause(e);message("无法列出目录",e.message.orEmpty())}finally{browsing=false;refresh()}
        }
    }
    private fun chooseStyle(){val options=LeicaStyle.entries;panels.options(style,"预设调色（不虚化）",options.map{it.label},options.indexOf(settings.style)){i->setSettings(settings.copy(style=options[i]))}}
    private fun choosePolicy(){val options=ExternalExportPolicy.entries;panels.options(policy,"自动导出策略",options.map{it.label},options.indexOf(settings.policy)){i->setSettings(settings.copy(policy=options[i]))}}
    private fun chooseFormat(){val options=ExportFormat.available(Build.VERSION.SDK_INT);panels.options(format,"默认 SDR 导出格式",options.map{it.label},options.indexOf(settings.format)){i->setSettings(settings.copy(format=options[i]))}}
    private fun chooseWatermark(){
        if(settings.metadataWatermark){setSettings(settings.copy(metadataWatermark=false));return}
        panels.actions(watermark,"徕卡一瞬相册标识","仅写入照片信息，不添加画面水印。建议使用 JPEG，标识显示以相册支持为准。",listOf(
            ExternalPanels.Action("开启并使用JPEG"){setSettings(settings.copy(metadataWatermark=true,format=ExportFormat.JPEG))},
            ExternalPanels.Action("开启，不改格式"){setSettings(settings.copy(metadataWatermark=true))}))
    }
    private fun chooseOriginal(){
        val options=ExternalOriginalPolicy.entries
        panels.options(original,"成片保存后的原片处理",options.map{it.label},options.indexOf(settings.original)){i->
            if(options[i]==ExternalOriginalPolicy.KEEP)setSettings(settings.copy(original=options[i],deletionConsent=0))
            else panels.actions(original,ExternalDeletionConsent.TITLE,ExternalDeletionConsent.MESSAGE,
                listOf(ExternalPanels.Action(ExternalDeletionConsent.ACCEPT){setSettings(settings.copy(original=ExternalOriginalPolicy.DELETE_AFTER_SAVE,deletionConsent=ExternalDeletionConsent.VERSION))}),ExternalDeletionConsent.DECLINE)
        }
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
                    panels.actions(originalPermission,"仅授权这张已处理原片","成片已保存，原片和成片也已核对。确认后只删除这张原片；取消则两张都保留。",
                        listOf(ExternalPanels.Action("打开系统确认"){
                            try{
                                if(ExternalModeRuntime.status.let{!it.running || it.stopping || it.runId!=attempt.run})finishOriginalApproval(false)
                                else{approvalLaunched=true;originalApproval.launch(IntentSenderRequest.Builder(checkNotNull(attempt.sender)).build())}
                            }
                            catch(e:Exception){finishOriginalApproval(false);message("系统授权未打开",e.message.orEmpty())}
                        }),"保留原片",onCancel={finishOriginalApproval(false)})
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
        panels.options(backButton,"外接模式正在运行",listOf("继续后台监听，返回桌面","停止监听并返回编辑器","留在此页")){i->when(i){0->moveTaskToBack(true);1->{finishAfterStop=true;ExternalModeService.stop(this)}}}
    }
    private fun message(title:String,body:String,confirm:(()->Unit)?=null){panels.message(anchor(),title,body,confirm)}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("original-id",pendingOriginalId);outState.putString("original-run",pendingOriginalRun);outState.putBoolean("tree-write",requestTreeWrite);outState.putBoolean("approval-launched",approvalLaunched);super.onSaveInstanceState(outState)}
    override fun onResume(){super.onResume();settings=ExternalPreferences.load(this);handler.removeCallbacks(tick);handler.post(tick)}
    override fun onPause(){panels.dismiss();handler.removeCallbacks(tick);super.onPause()}
    override fun onDestroy(){panels.dismiss();handler.removeCallbacks(tick);super.onDestroy()}
}
