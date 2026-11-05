package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class LeicaActivity:AppCompatActivity() {
    private val session by lazy{EditSession(java.io.File(noBackupFilesDir,"latest-edit"))}
    private var restored=false
    private var lastEditorState:Map<String,String>?=null
    private var interruptedKind=""
    private lateinit var resumeButton:StudioButton
    private lateinit var subjectResolutionButton:StudioButton
    private lateinit var engine:LeicaEngine
    private var workerThread:Thread?=null
    private val worker=Executors.newSingleThreadExecutor{Thread(it,"legendary-worker").also{t->workerThread=t}}
    private val dispatcher by lazy{worker.asCoroutineDispatcher()}
    private val flow=PhotoFlow();private val previewUpdates=FocusPreviewUpdates()
    private var workflow=PhotoWorkflow.COLOR
    private var pendingComposition=false
    private val gaic=GaicComposition()
    private var crop=CompositionGeometry.FULL
    private var sourceWidth=1;private var sourceHeight=1
    private var composing=false;private var leaveCompositionWhenIdle=false;private var parametersBeforeComposition=true
    private var compositionLoaded:CompositionFiles.Loaded?=null
    private var compositionLoadingBitmap:Bitmap?=null
    private var compositionItems=emptyList<CompositionAnalysis.Suggestion>()
    private var compositionSelected=0
    private lateinit var compositionPreview:CompositionCropView
    private lateinit var compositionPanel:LinearLayout;private lateinit var compositionChoices:LinearLayout
    private lateinit var compositionTabs:HorizontalScrollView;private lateinit var compositionActions:LinearLayout
    private lateinit var compositionExit:StudioButton
    private lateinit var compositionInfo:TextView;private lateinit var compositionApply:StudioButton;private lateinit var compositionRetry:StudioButton
    private lateinit var workflowPanel:LinearLayout
    private lateinit var compositionBack:OnBackPressedCallback
    private var photo:Uri?=null;private var current:LeicaEngine.Result?=null;private var sourceHdr=false
    private var developMode=HdrDevelopMode.SDR
    private lateinit var developActions:LinearLayout
    private lateinit var nextModeRow:LinearLayout
    private lateinit var modeSwitch:HdrModeSwitch
    private lateinit var viewerTabs:HorizontalScrollView;private lateinit var parameterScroll:ScrollView
    private lateinit var parameterCloseButton:StudioButton;private lateinit var parameterTitle:TextView
    private lateinit var parametersBack:OnBackPressedCallback;private lateinit var moreButton:StudioButton
    private lateinit var titleLabel:StudioButton;private lateinit var actualPreviewBadge:TextView
    private lateinit var subjectPanel:LinearLayout;private lateinit var outputPanel:LinearLayout;private lateinit var compositionEntry:LinearLayout
    private lateinit var outputHint:TextView;private lateinit var actionStatus:LinearLayout
    private lateinit var header:LinearLayout;private lateinit var tabs:LinearLayout
    private lateinit var fullscreenButton:StudioButton;private lateinit var diagnosticsButton:StudioButton
    private lateinit var albumThumbnail:WorkspaceThumbnail;private lateinit var monitorGlyph:WorkspaceGlyph
    private var stylePopup:ExportUi.Handle?=null;private var morePopup:ExportUi.Handle?=null
    private var choicePanel:ExportUi.Handle?=null;private var helpPanel:ExportUi.Handle?=null
    private var compositionClosing=false
    private var compositionOptionsExpanded=false
    private var recordSummary=""
    private val visibleViewers get()=WorkspaceMotion.viewers.map{Viewer.valueOf(it)}
    private var photoFullscreen:FullscreenOverlay?=null
    private var resultAppearance:CompositionAppearance?=null
    private lateinit var compositionButton:StudioButton
    private var cancel:Cancel?=null;private var job:Job?=null;private var ticker:Job?=null
    private var lastExportMs:Long?=null
    private var completedGenerationMs:Long?=null;private var progressMessage="";private var phase="";private var phaseStarted=0L;private var started=0L
    private var outputSize=BokehOutput.SIZE_2048;private var regionVisible=true
    private enum class Viewer(val label:String){ORIGINAL("原图"),GRAY("黑白深度"),THERMAL("伪彩深度"),RELIEF("空间"),RESULT("成片"),COMPARE("对比")}
    private var viewer=Viewer.ORIGINAL
    private var visual:DepthVisualMath.Data?=null;private var gray:Bitmap?=null;private var thermal:Bitmap?=null
    private var depthCalculating=false
    private lateinit var editor:EditorLayout;private lateinit var root:LinearLayout;private lateinit var controls:LinearLayout;private lateinit var actions:LinearLayout
    private lateinit var compare:CompareView;private lateinit var stageView:DepthStageView
    private lateinit var spaceTools:LinearLayout
    private var spaceFullscreen:SpaceFullscreen?=null
    private lateinit var developView:DevelopView
    private lateinit var emptyState:LinearLayout;private lateinit var previewTag:TextView;private lateinit var focusLegend:TextView
    private lateinit var status:TextView;private lateinit var elapsedLabel:TextView;private lateinit var progress:ProgressBar;private lateinit var progressLabel:TextView
    private lateinit var depthControls:LinearLayout;private lateinit var apertureDial:ApertureDial
    private lateinit var processingButton:StudioButton;private lateinit var outputButton:StudioButton;private lateinit var focusButton:StudioButton;private lateinit var regionButton:StudioButton
    private lateinit var colorControls:LinearLayout;private lateinit var styleButtons:List<StudioButton>;private lateinit var styleHint:TextView
    private lateinit var parametersButton:StudioButton;private var exportMenu:ExportUi.Handle?=null
    private lateinit var subjectButton:StudioButton
    private lateinit var subjectSelectButton:StudioButton;private lateinit var subjectComputeButton:StudioButton
    private lateinit var subjectUndoButton:StudioButton;private lateinit var subjectClearButton:StudioButton
    private lateinit var subjectControls:LinearLayout
    private val subjectWork=SubjectPreviewWork()
    private lateinit var apertureButtons:List<StudioButton>;private lateinit var modeButtons:List<StudioButton>
    private lateinit var pickButton:StudioButton;private lateinit var runButton:StudioButton;private lateinit var saveButton:StudioButton;private lateinit var cancelButton:StudioButton
    private lateinit var diagnostics:LiveDiagnostics;private var diagnosticsDialog:ExportUi.Handle?=null
    private var diagnosticsPanel:DiagnosticsPanel?=null;private var lastFailure:String?=null
    private var lastMetadataFailure:String?=null
    private lateinit var compositionDock:CompositionActionDock
    private var lastChrome:List<Any>?=null
    private var lastViewer:Viewer?=null
    // Optional second notification while editing; main thread only, lives with this Activity.
    private var runtimeMonitor:RuntimeMonitor?=null
    private val pendingPhoto=PendingPhotoImport()
    private var pickerNotice:AlertDialog?=null
    private var pendingMetadataImport:Uri?=null
    private val metadataPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        val uri=pendingMetadataImport;pendingMetadataImport=null
        if(granted && uri!=null && !isDestroyed)acceptPicked(uri,true)
        else if(!isDestroyed)AlertDialog.Builder(this).setTitle("未读取脱敏副本").setMessage("完整保留照片原有GPS需允许读取照片位置信息；这不是获取手机实时位置。也可通过系统文件选择原文件。原图和上次编辑未改动。").setPositiveButton("知道了",null).show()
    }
    private val monitorPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)setEditorMonitor(true)else {
            diagnosticsPanel?.syncNotification()
            AlertDialog.Builder(this).setTitle("通知未开启").setMessage("可在系统设置中允许通知，再显示运行参数；不影响照片处理。")
                .setPositiveButton("去设置"){_,_->openMonitorSettings()}.setNegativeButton("取消",null).show()
        }
    }
    private val photoImports by lazy{PhotoImports(applicationContext)}
    private val documentPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)acceptPicked(uri)}
    private val picker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)acceptPicked(uri)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun surface(color:String,radius:Int=8)=GradientDrawable().apply{setColor(Color.parseColor(color));cornerRadius=dp(radius).toFloat()}
    private fun label(value:String,size:Float=12f,color:Int=0xff9f9a92.toInt())=TextView(this).apply{text=value;textSize=size;setTextColor(color);setLineSpacing(dp(2).toFloat(),1f)}
    private fun button(value:String,primary:Boolean=false)=StudioButton(this,primary).apply{text=value}
    private fun row(vararg children:View)=WorkspaceActionRow(this).apply{children.forEach{addView(it,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))}}
    private fun LinearLayout.block(view:View,margin:Int=10){addView(view,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(margin)})}
    private fun section(title:String,topic:String="editor")=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;setPadding(0,dp(8),0,dp(8))
        val heading=LinearLayout(this@LeicaActivity).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.addView(label(title,13f,0xffc4c0b8.toInt()).apply{ViewCompat.setAccessibilityHeading(this,true)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        heading.addView(iconButton(WorkspaceGlyph.Kind.INFO,"$title · 操作说明"){ }.apply{setOnClickListener{showHelp(this,topic)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        block(heading,0)
    }
    private fun showHelp(anchor:StudioButton,id:String?=null){
        if(helpPanel?.isShowing==true)return
        helpPanel=WorkspacePanels.help(anchor,id)
    }
    private fun explain(button:StudioButton,id:String){button.setOnLongClickListener{showHelp(button,id);true}}
    private fun showChoices(anchor:StudioButton,title:String,labels:List<String>,index:Int,summary:String,chosen:(Int)->Unit){
        if(choicePanel?.isShowing==true)return
        choicePanel=WorkspacePanels.options(anchor,title,labels,index,summary,chosen)
    }
    private fun setParametersExpanded(value:Boolean){
        if(composing)return
        flow.showParameters(value);refresh()
        if(value)parameterScroll.post{parameterScroll.smoothScrollTo(0,0)}
    }
    private fun selector(button:StudioButton,kind:WorkspaceGlyph.Kind){
        val left=glyph(button,kind)
        val right=WorkspaceGlyph(WorkspaceGlyph.Kind.CHEVRON){button.currentTextColor}.apply{setBounds(0,0,dp(16),dp(16))}
        button.gravity=Gravity.START or Gravity.CENTER_VERTICAL;button.setCompoundDrawables(left,null,right,null)
    }
    private fun transition(){
        // The overlay owns its one rectangle animation. Never combine ChangeBounds/Fade with it.
    }

    private fun glyph(button:StudioButton,kind:WorkspaceGlyph.Kind,size:Int=20,top:Boolean=false):WorkspaceGlyph {
        val icon=WorkspaceGlyph(kind){button.currentTextColor}.apply{setBounds(0,0,dp(size),dp(size))}
        if(top)button.setCompoundDrawables(null,icon,null,null)else button.setCompoundDrawables(icon,null,null,null)
        button.compoundDrawablePadding=dp(if(top)6 else 5);return icon
    }
    private fun iconButton(kind:WorkspaceGlyph.Kind,description:String,action:()->Unit)=button("").apply{
        cornerRadiusDp=13f;borderVisible=false;setPadding(dp(12),dp(12),dp(12),dp(12));contentDescription=description;glyph(this,kind,22);setOnClickListener{action()}
    }
    private fun compactUi()=ReferenceWorkspacePolicy.chrome(resources.configuration.screenHeightDp,resources.configuration.fontScale).compact
    private fun chooseStyle(style:LeicaStyle){
        if(flow.busy || composing || !workflow.allowsColorSelection || flow.style==style)return
        flow.selectStyle(style);getPreferences(MODE_PRIVATE).edit().putString("color-style",style.id).apply()
        status.text=if(flow.hasResult)"已选择${style.label} · 重新显影后应用；导出只保存现有成片。"else "已选择${style.label} · 点击显影后应用。"
        refresh()
    }
    private fun showStylePicker(){
        if(flow.busy || composing || !workflow.allowsColorSelection || stylePopup?.isShowing==true)return
        stylePopup=WorkspacePanels.options(titleLabel,"调色风格",LeicaStyle.entries.map{it.label},LeicaStyle.entries.indexOf(flow.style),
            "只选择下次显影风格，不改变现有成片。选择后回到工作台点击显影；更多和参数中不再重复放置风格入口。"){index->chooseStyle(LeicaStyle.entries[index])}
    }

    private fun chooseDevelopMode(value:HdrDevelopMode){
        if(!EditorWorkspacePolicy.canChooseMode(sourceHdr,workflow.onlyComposition,flow.hasPhoto,flow.busy,composing))return
        developMode=EditorWorkspacePolicy.chooseMode(flow,developMode,value,sourceHdr)
        status.text="下次使用 ${developMode.name} 显影 · 选择不会自动处理；导出仍使用现有成片。";refresh()
    }
    private fun openExternal(){
        if(flow.busy || composing)return
        startActivity(android.content.Intent(this,ExternalModeActivity::class.java))
    }
    private fun showProcessingRecord(anchor:View){
        if(choicePanel?.isShowing==true)return
        val record=status.text.toString().ifBlank{"暂无处理记录；选择照片后手动显影。"}
        val completed=current?.let{"\n\n最近完整成片\n${ProcessTimingText.details(it.totalMs,it.stageSummary,lastExportMs)}"}.orEmpty()
        val content=label(record+completed,13f).apply{setTextIsSelectable(true)}
        choicePanel=ExportUi.panel(anchor,"处理记录",content,600,460,below=false)
    }

    private fun showMore(){
        if(morePopup?.isShowing==true)return
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun item(title:String,kind:WorkspaceGlyph.Kind,enabled:Boolean=true,action:()->Unit){
            body.addView(SettingsRow(this,title,kind).apply{isEnabled=enabled;setOnClickListener{morePopup?.close{action()}}},LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        }
        item("外接模式",WorkspaceGlyph.Kind.EXTERNAL,!flow.busy && !composing){openExternal()}
        item("处理记录",WorkspaceGlyph.Kind.FILE){showProcessingRecord(moreButton)}
        item("耗时明细",WorkspaceGlyph.Kind.CLOCK,!flow.busy && current!=null){showProcessTiming()}
        item("操作说明",WorkspaceGlyph.Kind.INFO){showHelp(moreButton)}
        morePopup=ExportUi.panel(moreButton,"更多",body,420,360)
    }

    private fun showPicker(){
        if(flow.busy)return
        showChoices(pickButton,"导入照片",listOf("从文件选择","从相册选择"),-1,"只读取你明确选中的照片；相册提供器可能隐藏位置等信息。不会扫描其他照片。"){i->
            if(!flow.busy){if(i==0)documentPicker.launch(arrayOf("image/*"))else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        pendingMetadataImport=savedInstanceState?.getString("import.metadata")?.let(Uri::parse)
        savedInstanceState?.getString("import.selected")?.let{pendingPhoto.offer(it,savedInstanceState.getBoolean("import.accepted"))}
        engine=LeicaEngine(applicationContext,recovery=session,depthBusy={busy->runOnUiThread{
            if(!isDestroyed && flow.busy){
                depthCalculating=busy
                if(busy && cancel?.cancelled!=true){stageView.beginLoading();compare.visibility=View.INVISIBLE}
                else if(!busy){
                    if(cancel?.cancelled!=true && visual!=null)stageView.finishLoading{if(!isDestroyed)applyViewer()}
                    else {stageView.endLoading();compare.visibility=View.VISIBLE;applyViewer()}
                }
            }
        }},depthReady={data->runOnUiThread{if(!isDestroyed && flow.busy && cancel?.cancelled!=true)acceptDepth(data)}},
            depthPreview={data->runOnUiThread{
                if(!isDestroyed && flow.busy && depthCalculating && cancel?.cancelled!=true){
                    stageView.setPreview(data)
                    previewTag.text="粗深度 · 仅预览 · 可拖动 · 完整深度计算中"
                }
            }},subjectBusy={busy->runOnUiThread{
                if(!isDestroyed && flow.busy && cancel?.cancelled!=true){
                    if(busy){stageView.beginSubject();compare.visibility=View.INVISIBLE}
                    else stageView.endSubjectWait()
                }
            }},subjectReady={data,_->runOnUiThread{
                if(!isDestroyed && flow.busy && flow.subjectEnabled && cancel?.cancelled!=true && !stageView.displaysSubject(data)){
                    if(flow.operation==PhotoFlow.Operation.FOCUS_PREVIEW)viewer=Viewer.RELIEF
                    compare.visibility=View.INVISIBLE;stageView.presentSubject(data,true)
                }
            }})
        engine.clearCache()
        outputSize=BokehOutput.entries.getOrElse(getPreferences(MODE_PRIVATE).getInt("bokeh-output",BokehOutput.SIZE_2048.ordinal)){BokehOutput.SIZE_2048}
        flow.selectSubjectResolution(SubjectResolution.from(getPreferences(MODE_PRIVATE).getInt("subject-resolution",2048)))
        flow.selectStyle(LeicaStyle.fromId(getPreferences(MODE_PRIVATE).getString("color-style",null)))
        flow.selectExportFormat(ExportPreferences.load(this));flow.showParameters(false)
        root=LinearLayout(this).apply{clipChildren=false;clipToPadding=false;orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff151617.toInt())}
        header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,dp(10))}
        titleLabel=button(flow.style.label).apply{borderVisible=false;quietSelection=true;setPadding(dp(4),dp(4),dp(4),dp(4));textSize=21f;letterSpacing=.02f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=dp(48);gravity=Gravity.CENTER;setTypeface(Typeface.create("serif",Typeface.NORMAL));isClickable=true;isFocusable=true;setOnClickListener{showStylePicker()}}
        header.addView(titleLabel,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        diagnosticsButton=iconButton(WorkspaceGlyph.Kind.MONITOR,"实时运行监测"){showDiagnostics()}
        monitorGlyph=glyph(diagnosticsButton,WorkspaceGlyph.Kind.MONITOR,22)
        header.addView(diagnosticsButton,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(6)})
        parametersButton=iconButton(WorkspaceGlyph.Kind.PARAMETERS,"展开参数"){if(!compositionClosing){editor.anchor=parametersButton;if(composing){compositionOptionsExpanded=!compositionOptionsExpanded;refresh()}else setParametersExpanded(!flow.parametersExpanded)}}
        header.addView(parametersButton,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(6)})
        moreButton=iconButton(WorkspaceGlyph.Kind.MORE,"更多：外接模式、记录、耗时与操作说明"){showMore()}
        header.addView(moreButton,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(6)})
        root.addView(header)
        val photoPane=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val imageArea=FrameLayout(this).apply{background=surface("#0D0E0F",18);clipToOutline=true}
        compare=CompareView(this);imageArea.addView(compare,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        stageView=DepthStageView(this).apply{visibility=View.GONE;onSubjectSettled={if(!isDestroyed && !isFinishing){applyViewer();refresh()}}};imageArea.addView(stageView,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        developView=DevelopView(this).apply{onPresentationChanged={if(!isDestroyed && !isFinishing){updateProgress();updateHdrWindow()}}};imageArea.addView(developView,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        compositionPreview=CompositionCropView(this).apply{visibility=View.GONE};imageArea.addView(compositionPreview,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        emptyState=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(22),dp(16),dp(22),dp(16));isClickable=true;isFocusable=true;contentDescription="选择照片";setOnClickListener{showPicker()}
            addView(label("选择照片",22f,0xffc4b8a6.toInt()).apply{gravity=Gravity.CENTER})
            addView(label("相册导入，或从右上角更多进入外接",12f).apply{gravity=Gravity.CENTER;setPadding(0,dp(8),0,0)})
        };imageArea.addView(emptyState,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        actualPreviewBadge=label("",11f,0xffeee8dd.toInt()).apply{setPadding(dp(10),dp(6),dp(10),dp(6));background=surface("#B3151617",14);maxLines=2;isClickable=false}
        imageArea.addView(actualPreviewBadge,FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.TOP or Gravity.END).apply{topMargin=dp(12);marginEnd=dp(12)})
        fullscreenButton=iconButton(WorkspaceGlyph.Kind.FULLSCREEN,"全屏预览；不复制照片"){togglePhotoFullscreen()}
        imageArea.addView(fullscreenButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.END).apply{bottomMargin=dp(8);marginEnd=dp(8)})
        photoPane.addView(imageArea,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        previewTag=label("原图",11f).apply{maxLines=2;setPadding(dp(10),dp(6),dp(10),dp(6));background=surface("#B3151617",10)}
        imageArea.addView(previewTag,FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.TOP or Gravity.START).apply{topMargin=dp(12);marginStart=dp(12)})
        // One fullscreen entry on the image; zoom/reset remain in its adapted fullscreen toolbar.
        spaceTools=LinearLayout(this).apply{visibility=View.GONE}
        tabs=LinearLayout(this).apply{minimumWidth=dp(ReferenceWorkspacePolicy.chrome(resources.configuration.screenHeightDp,resources.configuration.fontScale).tabsMinWidth)}
        val tabIcons=visibleViewers.map{WorkspaceGlyph.Kind.valueOf(WorkspaceIcons.viewer(it.name))}
        modeButtons=visibleViewers.mapIndexed{i,v->button(when(v){Viewer.GRAY->"黑白";Viewer.THERMAL->"深度";else->v.label}).apply{
            cornerRadiusDp=14f;quietSelection=true;textSize=11f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(3),dp(9),dp(3),dp(8));glyph(this,tabIcons[i],18,true)
            contentDescription=v.label;setOnClickListener{selectViewer(v)}
        }}
        modeButtons.forEachIndexed{i,b->tabs.addView(b,LinearLayout.LayoutParams(0,MATCH_PARENT,1f).apply{if(i>0)marginStart=dp(6)})}
        viewerTabs=HorizontalScrollView(this).apply{isFillViewport=true;isHorizontalScrollBarEnabled=false;addView(tabs,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))}
        controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),0,dp(14),dp(14))}
        parameterScroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;clipToPadding=false;addView(controls)}
        val parameterShell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=surface("#1C1D1F",18);clipToOutline=true}
        val panelHeading=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(2),dp(4),dp(2))}
        parameterTitle=label("编辑参数",15f,0xffeee8dd.toInt())
        panelHeading.addView(parameterTitle,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        parameterCloseButton=iconButton(WorkspaceGlyph.Kind.CHEVRON_DOWN,"收起参数"){if(composing){compositionOptionsExpanded=false;refresh()}else setParametersExpanded(false)}
        panelHeading.addView(parameterCloseButton,LinearLayout.LayoutParams(dp(48),dp(48)))
        parameterShell.addView(panelHeading,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        parameterShell.addView(View(this).apply{setBackgroundColor(0xff303233.toInt());importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(MATCH_PARENT,dp(1)))
        parameterShell.addView(parameterScroll,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        parametersBack=object:OnBackPressedCallback(false){override fun handleOnBackPressed(){setParametersExpanded(false)}}
        onBackPressedDispatcher.addCallback(this,parametersBack)
        workflowPanel=section("处理流程","workflow")
        processingButton=button(workflow.label).apply{setOnClickListener{
            if(!flow.busy)showChoices(this,"处理流程",PhotoWorkflow.entries.map{it.label}+"视频模式",workflow.ordinal,WorkspaceGuide.topic("workflow").steps){index->
                if(!flow.busy && index==PhotoWorkflow.entries.size){startActivity(android.content.Intent(this@LeicaActivity,VideoActivity::class.java))}
                else if(!flow.busy){workflow=PhotoWorkflow.entries[index];workflow.processing?.let{flow.selectProcessingMode(it)};stageView.enableSubject(flow.blur && flow.subjectReady);compare.focusPicking=false;refresh()}
            }
        }}
        outputButton=button(outputSize.label).apply{setOnClickListener{
            if(!flow.busy && workflow.allowsOutputSelection)showChoices(this,"出图尺寸",BokehOutput.entries.map{it.label},outputSize.ordinal,WorkspaceGuide.topic("output").steps){index->
                if(!flow.busy && workflow.allowsOutputSelection){outputSize=BokehOutput.entries[index];getPreferences(MODE_PRIVATE).edit().putInt("bokeh-output",index).apply();flow.outputChanged();refresh();requestFocusPreview()}
            }
        }}
        selector(processingButton,WorkspaceGlyph.Kind.FLOW);selector(outputButton,WorkspaceGlyph.Kind.OUTPUT)
        workflowPanel.block(processingButton)
        workflowPanel.block(label("选择后点击显影，不会自动处理。",11f),6)
        compositionButton=button("智能构图").apply{setOnClickListener{openComposition()}};selector(compositionButton,WorkspaceGlyph.Kind.CROP)
        controls.addView(workflowPanel)
        colorControls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(6))}
        styleButtons=emptyList()
        styleHint=label("风格仅从左上角选择",11f)
        depthControls=section("焦点与景深","focus")
        depthControls.block(label("先点选一个焦点，再调整光圈；保护点不是焦点。",11f),6)
        apertureDial=ApertureDial(this);depthControls.addView(apertureDial,LinearLayout.LayoutParams(MATCH_PARENT,dp(102)))
        val strip=LinearLayout(this)
        apertureButtons=LegendaryMath.apertures.map{value->button("$value").apply{contentDescription="光圈$value，控制景深与焦外虚化";setOnClickListener{if(flow.canAdjustOptics && flow.blur){flow.aperture(value);refresh();requestFocusPreview()}}}}
        apertureButtons.forEachIndexed{i,b->strip.addView(b,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{if(i>0)marginStart=dp(5)})}
        depthControls.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(strip)})
        focusButton=button("点选焦点").apply{setOnClickListener{if(!flow.busy && flow.hasPhoto){flow.chooseFocus();if(flow.picking)flow.showParameters(false);viewer=Viewer.ORIGINAL;applyViewer();status.text=if(flow.picking)"轻点目标内部。双指可放大，单指可平移。" else "已退出选焦。";refresh()}}}
        regionButton=button("景深提示").apply{setOnClickListener{regionVisible=!regionVisible;refresh()}}
        depthControls.block(row(focusButton,regionButton))
        subjectButton=button(UiText.SUBJECT_LABEL).apply{contentDescription="主体保护开关；焦点仍只有一个，保护点可以多个，选好后统一计算";setOnClickListener{
            if(!flow.busy){flow.toggleSubject();subjectWork.invalidate()
                if(!flow.subjectEnabled)stageView.enableSubject(false)
                else if(!flow.subjectReady && flow.hasPhoto && flow.blur){flow.chooseSubjects();flow.showParameters(false);viewer=Viewer.ORIGINAL;applyViewer()}
                status.text=if(flow.subjectEnabled){if(flow.subjectReady)"主体保护已开启 · 已有结果可用。" else "轻点要保护的主体，选好后点「统一计算」。"} else "主体保护已关闭 · 已有选点保留。"
                refresh();if(!flow.subjectPicking && flow.subjectReady)requestFocusPreview()
            }
        }}
        focusLegend=label("绿：清晰区 · 金：过渡区",11f);depthControls.block(focusLegend)
        controls.block(depthControls,8)
        subjectPanel=section("主体保护 · 可选","subject")
        subjectPanel.block(label("默认关闭。多个保护点可分批计算，已经完成的结果会保留。",11f),6)
        subjectPanel.block(subjectButton)
        subjectSelectButton=button("选择保护点").apply{setOnClickListener{
            if(!flow.busy && flow.hasPhoto && flow.subjectEnabled){flow.chooseSubjects();if(flow.subjectPicking)flow.showParameters(false);viewer=Viewer.ORIGINAL;applyViewer();status.text=if(flow.subjectPicking)"连续轻点多个目标；蓝色数字为保护点，金色十字为唯一焦点。选好后点击「统一计算」。" else "已退出保护点选择；已有选择保留。";refresh();if(!flow.subjectPicking && flow.subjectReady)requestFocusPreview()}
        }}
        subjectComputeButton=button("统一计算",true).apply{setOnClickListener{if(flow.canComputeSubjects)requestFocusPreview(computeSubject=true)}}
        subjectUndoButton=button("移除指定点").apply{setOnClickListener{showSubjectRemoval()}}
        subjectClearButton=button("清空保护点").apply{setOnClickListener{if(!flow.busy && flow.subjectEnabled){flow.clearSubjectPoints();worker.execute{engine.clearSubjectCache()};subjectSelectionChanged()}}}
        subjectControls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;block(row(subjectSelectButton,subjectComputeButton));block(row(subjectUndoButton,subjectClearButton))}
        subjectResolutionButton=button("保护分辨率 · 长边 2048").apply{setOnClickListener{
            if(!flow.busy)showChoices(this,"主体保护分辨率",SubjectResolution.entries.map{it.label},flow.subjectResolution.ordinal,WorkspaceGuide.topic("subject-resolution").overview){index->
                val value=SubjectResolution.entries[index]
                if(!flow.busy && value!=flow.subjectResolution){
                    flow.selectSubjectResolution(value);getPreferences(MODE_PRIVATE).edit().putInt("subject-resolution",value.longEdge).apply()
                    worker.execute{engine.clearSubjectCache()};subjectSelectionChanged()
                    status.text="保护分辨率：${value.longEdge}长边。已保留选点，请按新分辨率计算；不改变成片尺寸。"
                }
            }
        }}
        subjectControls.block(subjectResolutionButton)
        subjectPanel.block(subjectControls);controls.block(subjectPanel,8)
        outputPanel=section("输出尺寸","output")
        outputPanel.block(outputButton)
        outputHint=label("",11f);outputPanel.block(outputHint,6);controls.block(outputPanel,8)
        compositionEntry=section("构图","composition")
        compositionEntry.block(compositionButton)
        compositionEntry.block(label("选方案 → 应用裁框 → 导出；应用裁框不重新调色或虚化。",11f),6);controls.block(compositionEntry,8)
        // A bounded notice stays on the dock so failures/cancellation are never hidden under More.
        status=label("导入照片，选择成像方式。深度图可单独查看，不会自动选焦。",11f).apply{setTextIsSelectable(false);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;ellipsize=android.text.TextUtils.TruncateAt.END;isClickable=true;isFocusable=true;setOnClickListener{showProcessingRecord(progressLabel)}}
        compositionPanel=section("智能构图 · 当前编辑","composition")
        compositionInfo=label("在照片下方直接切换方案，再点应用编辑；退出构图保留原裁框，不自动保存。",12f).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};compositionPanel.block(compositionInfo)
        compositionChoices=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        compositionTabs=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;isFillViewport=false;contentDescription="构图方案，左右滚动，点击直接切换";addView(compositionChoices)}
        compositionApply=button("应用编辑",true).apply{setOnClickListener{applyComposition()}}
        compositionExit=button("退出构图").apply{setOnClickListener{leaveComposition()}}
        compositionActions=WorkspaceActionRow(this).apply{addView(compositionApply);addView(compositionExit)}
        compositionRetry=button("重新分析").apply{setOnClickListener{analyseComposition()}}
        compositionPanel.block(compositionRetry);controls.addView(compositionPanel)
        compositionBack=object:OnBackPressedCallback(false){override fun handleOnBackPressed(){if(editor.expanded){compositionOptionsExpanded=false;refresh()}else leaveComposition()}}
        onBackPressedDispatcher.addCallback(this,compositionBack)
        editor=EditorLayout(this,photoPane,parameterShell).apply{anchor=parametersButton};root.addView(editor,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        photoPane.addView(viewerTabs,LinearLayout.LayoutParams(MATCH_PARENT,dp(64)).apply{topMargin=dp(10)})
        photoPane.addView(compositionTabs,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(10)})
        actions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(6),0,dp(2))}
        modeSwitch=HdrModeSwitch(this,::chooseDevelopMode)
        nextModeRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(modeSwitch,LinearLayout.LayoutParams(dp(132),WRAP_CONTENT))}
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=7;progressTintList=android.content.res.ColorStateList.valueOf(0xffc4b8a6.toInt())}
        imageArea.addView(progress,FrameLayout.LayoutParams(MATCH_PARENT,dp(2),Gravity.BOTTOM).apply{marginStart=dp(18);marginEnd=dp(18)})
        progressLabel=label("处理记录 ›",11f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=dp(48);gravity=Gravity.CENTER_VERTICAL;background=surface("#1C1D1F",14);setPadding(dp(10),dp(6),dp(10),dp(6));isClickable=true;isFocusable=true;setOnClickListener{showProcessingRecord(progressLabel)}}
        elapsedLabel=label("",10f).apply{visibility=View.GONE}
        status.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updateProgress()};override fun afterTextChanged(s:Editable?){}})
        pickButton=button("").apply{cornerRadiusDp=14f;setPadding(0,0,0,0);contentDescription="相册：选择一张照片；缩略图只显示当前已导入照片";setOnClickListener{showPicker()}}
        albumThumbnail=WorkspaceThumbnail().apply{setBounds(0,0,dp(54),dp(54))};pickButton.setCompoundDrawables(albumThumbnail,null,null,null)
        runButton=button("显影").apply{borderVisible=false;cornerRadiusDp=25f;textSize=14f;maxLines=1;setPadding(dp(10),dp(8),dp(10),dp(8));contentDescription="按当前参数生成成片";setOnClickListener{if(!compositionClosing){if(composing)applyComposition()else generate(developMode)}}}
        saveButton=button("").apply{borderVisible=false;cornerRadiusDp=14f;setPadding(dp(16),dp(14),dp(16),dp(14));glyph(this,WorkspaceGlyph.Kind.EXPORT,22);setOnClickListener{showExport()}}
        cancelButton=button("取消处理").apply{borderVisible=false;cornerRadiusDp=25f;textSize=14f;maxLines=1;glyph(this,WorkspaceGlyph.Kind.STOP,18);setOnClickListener{if(UiPolishPolicy.cancelUi(flow.operation,flow.cancelling,cancel!=null).enabled){previewUpdates.discard();flow.cancel();cancel?.cancelled=true;engine.cancelRun();gaic.cancelRun();developView.skipPresentation();stageView.endLoading();compare.visibility=View.VISIBLE;progressMessage="正在取消 · 等待当前步骤退出";refresh()}}}
        val capsule=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5));background=surface("#1C1D1F",32).apply{setStroke(dp(1),0xff373839.toInt())}}
        capsule.addView(runButton,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        capsule.addView(cancelButton,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        fun edgeAction(button:StudioButton,title:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;addView(button,LinearLayout.LayoutParams(dp(54),dp(54)));addView(label(title,10f).apply{gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(4)})}
        developActions=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        developActions.addView(edgeAction(pickButton,"相册"),LinearLayout.LayoutParams(dp(60),WRAP_CONTENT))
        developActions.addView(capsule,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(14);marginEnd=dp(14)})
        developActions.addView(edgeAction(saveButton,"导出"),LinearLayout.LayoutParams(dp(60),WRAP_CONTENT))
        resumeButton=button("恢复上次处理").apply{visibility=View.GONE;setOnClickListener{offerRecovery()}}
        actionStatus=object:LinearLayout(this){
            override fun onMeasure(widthSpec:Int,heightSpec:Int){
                val d=resources.displayMetrics.density
                val available=(View.MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight).coerceAtLeast(0)
                val font=resources.configuration.fontScale
                // Never stack the record above SDR/HDR. Shrink its summary, not its hit target.
                orientation=HORIZONTAL
                val labelParams=progressLabel.layoutParams as LayoutParams
                if(labelParams.width!=0 || labelParams.weight!=1f){labelParams.width=0;labelParams.weight=1f;progressLabel.layoutParams=labelParams}
                val modeParams=nextModeRow.layoutParams as LayoutParams
                if(modeParams.marginStart!=dp(8) || modeParams.topMargin!=0){modeParams.marginStart=dp(8);modeParams.topMargin=0;modeParams.gravity=Gravity.CENTER_VERTICAL;nextModeRow.layoutParams=modeParams}
                val switchWidth=FullscreenMotion.recordModeWidth(available,d,font)
                if(modeSwitch.layoutParams.width!=switchWidth){modeSwitch.layoutParams=modeSwitch.layoutParams.apply{width=switchWidth}}
                val room=(available-(if(nextModeRow.visibility==View.GONE)0 else switchWidth+dp(8))).coerceAtLeast(0)/d
                val caption=FullscreenMotion.recordLabel(recordSummary,room,font)
                if(progressLabel.text.toString()!=caption)progressLabel.text=caption
                progressLabel.minLines=1;progressLabel.maxLines=2
                super.onMeasure(widthSpec,heightSpec)
            }
        }.apply{gravity=Gravity.CENTER_VERTICAL}
        actionStatus.addView(progressLabel,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        actionStatus.addView(nextModeRow,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{marginStart=dp(8)})
        val normalActions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(actionStatus);addView(resumeButton,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));block(developActions,6)}
        compositionDock=CompositionActionDock(this,normalActions,compositionActions)
        actions.addView(compositionDock,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        status.visibility=View.GONE;actions.addView(status);root.addView(actions)
        compare.onFocusPoint={point->if(!flow.busy){flow.picked(point);subjectWork.invalidate();compare.subjectOverlay=null;stageView.clearSubject();worker.execute{engine.clearSubjectCache()};refresh();requestFocusPreview()}}
        compare.onSubjectPoint={point->if(!flow.busy && flow.subjectPicking && flow.addSubjectPoint(point))subjectSelectionChanged()}
        compare.onSubjectMoved={old,point->if(!flow.busy && flow.subjectEnabled && flow.moveSubjectPoint(old,point))subjectSelectionChanged()}
        diagnostics=LiveDiagnostics(applicationContext,window,{workerThread},{if(flow.busy)progressMessage else status.text.toString()+current?.let{"\n\n上次显影\n${it.reason}\n${it.hardwareSummary}\n${it.stageSummary}"}.orEmpty()},{diagnosticFailure()},{diagnosticsPanel?.expanded==true})
        if(MonitorPreferences.editor(this) && ExternalPermissions.notifications(this))startEditorMonitor()
        ViewCompat.setOnApplyWindowInsetsListener(root){v,i->val b=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());v.setPadding(dp(14)+b.left,dp(8)+b.top,dp(14)+b.right,dp(8)+b.bottom);i}
        listOf(processingButton to "workflow",outputButton to "output",focusButton to "focus",regionButton to "focus",subjectButton to "subject",subjectSelectButton to "subject",subjectComputeButton to "subject",subjectUndoButton to "subject",subjectClearButton to "subject",subjectResolutionButton to "subject-resolution",compositionButton to "composition",pickButton to "import",runButton to "develop",saveButton to "export",fullscreenButton to "space",titleLabel to "style",parametersButton to "editor",diagnosticsButton to "monitor",resumeButton to "develop").forEach{(button,topic)->explain(button,topic)}
        apertureButtons.forEach{explain(it,"aperture")};modeButtons.forEach{explain(it,"views")}
        modeSwitch.setOnLongClickListener{showHelp(parametersButton,"hdr");true}
        setContentView(root);refresh();adaptChrome();restoreLatest()
    }
    private fun acceptDepth(data:DepthVisualMath.Data){
        if(visual===data)return
        compare.inspection=null;gray?.recycle();thermal?.recycle()
        visual=data;gray=Bitmap.createBitmap(data.gray,data.w,data.h,Bitmap.Config.ARGB_8888);thermal=Bitmap.createBitmap(data.thermal,data.w,data.h,Bitmap.Config.ARGB_8888);stageView.setDepth(data)
    }
    private fun clearDepthViews(){compare.inspection=null;gray?.recycle();thermal?.recycle();gray=null;thermal=null;visual=null;stageView.setDepth(null);stageView.hide()}
    private fun selectViewer(value:Viewer){
        if(!flow.hasPhoto || flow.busy)return
        if(flow.picking)flow.chooseFocus()
        flow.endSubjectPicking()
        if(value in listOf(Viewer.RESULT,Viewer.COMPARE) && !flow.hasResult)return
        viewer=value
        if(value in listOf(Viewer.GRAY,Viewer.THERMAL,Viewer.RELIEF) && visual==null){
            val uri=photo ?: return
            operation(PhotoFlow.Operation.DEPTH,"解析照片空间"){
                val c=checkNotNull(cancel);withContext(dispatcher){engine.inspectDepth(uri,c){message->runOnUiThread{stage(message)}}};c.check()
                status.text="完整深度已缓存。深度：暖近冷远；空间视图可拖动旋转。"
            }
        }else applyViewer()
    }
    private fun applyViewer(){
        compositionPreview.visibility=if(composing)View.VISIBLE else View.GONE
        if(composing){compare.visibility=View.INVISIBLE;stageView.hide();return}
        compare.crop=crop
        if(depthCalculating || stageView.isLoading)return
        if(viewer in listOf(Viewer.GRAY,Viewer.THERMAL,Viewer.RELIEF) && visual==null)viewer=Viewer.ORIGINAL
        compare.inspection=when(viewer){Viewer.GRAY->gray;Viewer.THERMAL->thermal;else->null}
        compare.mode=when(viewer){Viewer.RESULT->CompareView.Mode.RESULT;Viewer.COMPARE->CompareView.Mode.SLIDER;else->CompareView.Mode.ORIGINAL}
        compare.visibility=if(viewer==Viewer.RELIEF)View.INVISIBLE else View.VISIBLE
        if(viewer==Viewer.RELIEF)stageView.showRelief()else stageView.hide()
        compare.animate().cancel();updateHdrWindow()
        // No repeated alpha dip on refresh/completion; HDR always stays on the live view.
        if(lastViewer!=null && lastViewer!=viewer && !flow.busy && ValueAnimator.areAnimatorsEnabled() && !visibleHdrPreview()){
            compare.alpha=.85f;compare.animate().alpha(1f).setDuration(180).start()
        }else compare.alpha=1f
        lastViewer=viewer
        updateViewerLabels()
    }
    private fun visibleHdrPreview():Boolean {
        if(composing)return HdrPreview.hasGainmap(compositionLoadingBitmap?:compositionLoaded?.bitmap)
        val originals=viewer in listOf(Viewer.ORIGINAL,Viewer.COMPARE) || !flow.hasResult || flow.picking || flow.subjectPicking
        val results=viewer in listOf(Viewer.RESULT,Viewer.COMPARE) && flow.hasResult && !flow.picking && !flow.subjectPicking
        return HdrDisplayPolicy.windowHdr(android.os.Build.VERSION.SDK_INT,
            !depthCalculating && !stageView.isLoading && viewer !in listOf(Viewer.GRAY,Viewer.THERMAL,Viewer.RELIEF),
            originals,results,HdrPreview.hasGainmap(compare.original),HdrPreview.hasGainmap(compare.result))
    }
    private fun updateHdrWindow(){
        if(!::compare.isInitialized)return
        val hdr=if(::developView.isInitialized && developView.presenting)developView.hdrContent else visibleHdrPreview()
        HdrPreview.configure(window,hdr && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    private fun updateDevelopActions(){
        nextModeRow.visibility=if(sourceHdr && !workflow.onlyComposition && flow.hasPhoto && !composing)View.VISIBLE else View.GONE
        val allowed=EditorWorkspacePolicy.canChooseMode(sourceHdr,workflow.onlyComposition,flow.hasPhoto,flow.busy,composing)
        modeSwitch.bind(developMode,allowed)
        runButton.isSelected=flow.hasPhoto
        albumThumbnail.bitmap=compare.original
        val listener=ExternalModeRuntime.status
        monitorGlyph.indicator=runtimeMonitor!=null || (listener.running && listener.ready && !listener.stopping)
        diagnosticsButton.contentDescription="实时运行监测；${UiPolishPolicy.monitorState(runtimeMonitor!=null)}；${ReferenceWorkspacePolicy.listener(listener.running,listener.ready,listener.working,listener.stopping,listener.saved)}"
        ViewCompat.setStateDescription(diagnosticsButton,if(listener.running)ReferenceWorkspacePolicy.listener(listener.running,listener.ready,listener.working,listener.stopping,listener.saved)else if(runtimeMonitor!=null)"运行参数通知已开启"else "运行参数通知未开启")
        fullscreenButton.isEnabled=flow.hasPhoto && !flow.busy && !composing
        fullscreenButton.contentDescription=if(viewer==Viewer.RELIEF)"全屏空间视图与陀螺仪"else "全屏预览；返回键退出"
    }
    private fun togglePhotoFullscreen(){
        photoFullscreen?.takeIf{it.isShowing}?.let{it.close();return}
        if(!flow.hasPhoto || flow.busy || composing)return
        flow.showParameters(false);refresh();editor.settle()
        if(viewer==Viewer.RELIEF){showSpaceFullscreen();return}
        photoFullscreen=FullscreenOverlay(this,compare,if(viewer==Viewer.THERMAL)"深度"else viewer.label){photoFullscreen=null}.also{it.show()}
    }
    private fun spaceToolsVisible()=EditorLayoutPolicy.showSpaceTools(viewer==Viewer.RELIEF,visual!=null,editor.expanded,composing,flow.busy,stageView.isLoading)
    private fun showSpaceFullscreen(){
        if(spaceFullscreen!=null || isDestroyed || isFinishing || !spaceToolsVisible() || stageView.visibility!=View.VISIBLE)return
        spaceFullscreen=SpaceFullscreen(this,stageView,fullscreenButton){dialog->if(spaceFullscreen===dialog)spaceFullscreen=null}.also{it.show()}
    }
    private fun updateViewerLabels(){
        spaceTools.visibility=View.GONE
        modeButtons.forEachIndexed{i,b->b.isSelected=visibleViewers[i]==viewer || (viewer==Viewer.GRAY && visibleViewers[i]==Viewer.THERMAL)}
        previewTag.text=when{
            composing->if(flow.busy)"智能构图 · ${EditorPresentation.stage(progressMessage)}"else "智能构图 · ${compositionItems.getOrNull(compositionSelected)?.title?:"等待方案"} · 下方应用或退出"
            !flow.hasPhoto->"原图 · 双指缩放 · 原像素细节按需加载"
            flow.subjectPicking->"选择保护点 · 已选${flow.subjectPoints.size}处 · 选好后统一计算"
            flow.picking->"点选唯一焦点 / 不会增加保护点"
            flow.resultStale && viewer in listOf(Viewer.RESULT,Viewer.COMPARE)->"上次成片 · ${currentCropPlan().label()} / 参数待重新显影；保存仅用现有像素"
            viewer==Viewer.GRAY->"黑白深度 · 白近黑远 · 相对深度"
            viewer==Viewer.THERMAL->"伪彩深度 · 暖近冷远 · 相对深度"
            viewer==Viewer.RELIEF->if(flow.subjectReady && flow.blur)"主体保护 · 主体与焦平面对齐 · 单指旋转 / 双指缩放平移" else "空间 · 相对深度 · 单指旋转 / 双指缩放平移"
            viewer in listOf(Viewer.RESULT,Viewer.COMPARE)->"${viewer.label} · ${currentCropPlan().label()} · ${if(flow.saved)"已保存" else "未保存"}"
            else->"${viewer.label}${if(flow.blur)" · 光圈 ${flow.aperture}" else ""}"
        } + (if(viewer in listOf(Viewer.RESULT,Viewer.COMPARE) && resultAppearance?.color==true)" · ${resultAppearance?.style?.label}" else "") + (if(flow.blur && flow.subjectEnabled && flow.subjectReady && viewer==Viewer.ORIGINAL)" · 主体保护" else "") +
            if(visibleHdrPreview())" · ${HdrPreview.label(compare)}" else if(viewer==Viewer.RESULT && flow.hasResult)" · SDR" else ""
        actualPreviewBadge.visibility=if(flow.hasPhoto && !flow.busy && !composing && !flow.picking && !flow.subjectPicking && viewer !in listOf(Viewer.GRAY,Viewer.THERMAL,Viewer.RELIEF))View.VISIBLE else View.GONE
        val displayHdr=visibleHdrPreview() && compare.display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty()==true
        actualPreviewBadge.text=ReferenceWorkspacePolicy.previewBadge(viewer==Viewer.RESULT,viewer==Viewer.COMPARE,sourceHdr,current?.hdrReady?:sourceHdr,flow.resultStale,displayHdr)
        actualPreviewBadge.contentDescription=actualPreviewBadge.text
        updateHdrWindow()
    }
    private fun refresh(){
        // Migrate an old saved grayscale tab to the single public depth entry without changing depth inference.
        if(viewer==Viewer.GRAY){viewer=Viewer.THERMAL;applyViewer()}
        ExternalModeRuntime.editorBusy=flow.busy
        val chrome=listOf<Any>(workflow,composing,flow.subjectEnabled,sourceHdr,interruptedKind.isNotEmpty(),flow.parametersExpanded)
        if(lastChrome!=null && lastChrome!=chrome)transition()
        lastChrome=chrome
        updateDevelopActions()
        pickButton.isEnabled=!flow.busy;runButton.isEnabled=if(composing)!flow.busy && !compositionClosing && compositionItems.isNotEmpty()else workflow.canStart(flow);saveButton.isEnabled=!flow.busy && flow.hasResult && !composing
        compositionButton.isEnabled=!flow.busy && flow.hasPhoto
        processingButton.isEnabled=!flow.busy;processingButton.text=workflow.label
        val compact=compactUi()
        val dock=UiPolishPolicy.dock(resources.configuration.screenWidthDp,resources.configuration.fontScale,compact)
        colorControls.visibility=View.GONE
        titleLabel.text=if(workflow.allowsColorSelection)flow.style.label else workflow.label
        titleLabel.contentDescription=if(workflow.allowsColorSelection)"当前风格${flow.style.label}，轻点选择；显影后应用"else workflow.label
        titleLabel.isEnabled=!flow.busy && !composing && workflow.allowsColorSelection
        previewTag.visibility=if(composing || flow.picking || flow.subjectPicking || depthCalculating)View.VISIBLE else View.GONE
        actualPreviewBadge.visibility=if(previewTag.visibility==View.VISIBLE)View.GONE else actualPreviewBadge.visibility
        val compositionChrome=FullscreenMotion.composition(composing,flow.busy,compositionClosing,flow.cancelling,compositionItems.size)
        viewerTabs.visibility=if(compositionChrome.normalViews)View.VISIBLE else View.GONE
        compositionTabs.visibility=if(compositionChrome.proposals)View.VISIBLE else View.GONE
        compositionDock.bind(composing)
        styleButtons.forEachIndexed{i,b->
            val style=LeicaStyle.entries[i];b.isSelected=flow.style==style;b.isEnabled=!flow.busy && !composing
            b.contentDescription="${style.label} · ${if(b.isSelected)"已选择" else "未选择"} · 选择后点击显影应用"
        }
        styleHint.text=if(flow.resultStale && flow.hasResult)"已选${flow.style.label} · 重新显影后应用" else "已选${flow.style.label} · 点击显影"
        compositionButton.visibility=if(workflow.onlyComposition)View.GONE else View.VISIBLE
        compositionEntry.visibility=if(!workflow.onlyComposition && !composing)View.VISIBLE else View.GONE
        outputPanel.visibility=if(composing)View.GONE else View.VISIBLE
        subjectPanel.visibility=if(flow.blur && !workflow.onlyComposition && !composing)View.VISIBLE else View.GONE
        outputHint.text=if(workflow.allowsOutputSelection)"仅虚化提供三档尺寸；原图档≤1250万像素，小图和裁框不放大。"else "自动保留可用原像素，最高5000万；小图和裁框内像素不足时不放大。"
        compositionButton.text="智能构图"
        outputButton.visibility=if(workflow.allowsOutputSelection)View.VISIBLE else View.GONE
        outputButton.isEnabled=!flow.busy && workflow.allowsOutputSelection;outputButton.text=outputSize.label
        val expanded=if(composing)compositionOptionsExpanded else UiPolishPolicy.parametersVisible(flow.parametersExpanded,false,false)
        if(!composing)editor.anchor=parametersButton
        if(editor.expanded!=expanded)editor.expanded=expanded
        parametersBack.isEnabled=expanded && !composing
        parameterTitle.text=if(composing)"构图详情"else "编辑参数"
        parameterCloseButton.contentDescription=if(composing)"收起构图详情，查看完整裁框"else "收起参数"
        parameterCloseButton.isEnabled=!composing || !flow.cancelling
        parametersButton.text=""
        parametersButton.isSelected=false
        parametersButton.isEnabled=!compositionClosing
        ViewCompat.setStateDescription(parametersButton,if(expanded)"已展开"else "已收起")
        parametersButton.contentDescription=if(composing){if(expanded)"收起构图详情"else "构图详情与重新分析；方案在照片下方直接切换"}else if(expanded)"收起参数"else "展开参数：流程、焦点、主体保护、尺寸与构图"
        depthControls.visibility=if(flow.blur && !workflow.onlyComposition && !composing)View.VISIBLE else View.GONE
        apertureDial.setAperture(flow.aperture)
        apertureButtons.forEachIndexed{i,b->b.isSelected=LegendaryMath.apertures[i]==flow.aperture;b.isEnabled=flow.canAdjustOptics && flow.blur}
        focusButton.isEnabled=!flow.busy && flow.hasPhoto && flow.blur;focusButton.isSelected=flow.picking
        focusButton.text=when{flow.picking->"退出选焦";flow.focus!=null->"重选焦点";else->"点选焦点"}
        regionButton.isSelected=regionVisible;regionButton.isEnabled=flow.blur && flow.canAdjustOptics
        subjectButton.isEnabled=!flow.busy
        subjectButton.isSelected=flow.subjectEnabled
        subjectButton.text=UiText.SUBJECT_LABEL
        subjectButton.contentDescription="主体保护 · ${if(flow.subjectEnabled)"已开启" else "已关闭"} · 已完成${flow.subjectCompletedCount}/${flow.subjectPoints.size}点"
        subjectControls.visibility=if(flow.subjectEnabled)View.VISIBLE else View.GONE
        subjectSelectButton.isEnabled=!flow.busy && flow.hasPhoto && flow.blur
        subjectSelectButton.isSelected=flow.subjectPicking;subjectSelectButton.text=if(flow.subjectPicking)"结束选点（${flow.subjectPoints.size}）" else "选择保护点（${flow.subjectPoints.size}）"
        val subjectTicket=flow.subjectTicket();val subjectQueued=subjectWork.needs(subjectTicket)
        subjectComputeButton.isEnabled=flow.canComputeSubjects && !subjectQueued && (!flow.subjectReady || !subjectWork.isPresented(subjectTicket))
        subjectComputeButton.text=when{flow.focus==null->"先选一个焦点";subjectQueued && flow.subjectReady->"保护已算完 · 正在应用";subjectQueued->"计算中 · ${flow.subjectCompletedCount}/${flow.subjectPoints.size}点";flow.subjectReady && !subjectWork.isPresented(subjectTicket)->"应用已算结果";flow.subjectReady->"已完成 ${flow.subjectCompletedCount}/${flow.subjectPoints.size}点";flow.subjectCompletedCount>0->"计算新增/待算 ${flow.subjectPendingCount}点";else->"统一计算"}
        subjectResolutionButton.isEnabled=!flow.busy
        subjectResolutionButton.text="保护分辨率 · 长边 ${flow.subjectResolution.longEdge}"
        resumeButton.visibility=if(interruptedKind.isNotEmpty() && !flow.busy)View.VISIBLE else View.GONE
        subjectUndoButton.isEnabled=!flow.busy && flow.subjectPoints.isNotEmpty();subjectClearButton.isEnabled=subjectUndoButton.isEnabled
        focusLegend.text="金色焦点 / 蓝色编号均可拖动 · 松手提交 · 蓝色区域为已完成保护"
        compare.showSubject=flow.blur && flow.subjectEnabled && flow.subjectCompletedCount>0
        compare.pointsEditable=!flow.busy && flow.blur
        compare.subjectPicking=flow.subjectPicking;compare.subjectPoints=if(flow.blur && flow.subjectEnabled)flow.subjectPoints else emptyList()
        compare.focusPicking=flow.picking;compare.focusPoint=if(flow.blur)flow.focus else null;compare.showFocusRegion=flow.blur && regionVisible && !flow.subjectPicking
        modeButtons.forEachIndexed{i,b->b.isEnabled=!flow.busy && flow.hasPhoto && (visibleViewers[i] !in listOf(Viewer.RESULT,Viewer.COMPARE) || flow.hasResult)}
        emptyState.visibility=if(flow.hasPhoto)View.GONE else View.VISIBLE
        val composingAction=workflow.onlyComposition || HdrDisplayPolicy.reuseForComposition(workflow.composeAfter,current!=null,flow.resultStale,developMode,current?.hdrReady==true)
        val actionLabel=when{flow.busy->UiPolishPolicy.runLabel(flow.operation,composingAction,flow.hasResult);composingAction->"选择构图";flow.picking->"请点焦点";flow.hasPhoto && flow.blur && flow.focus==null->"先选焦点";flow.blur && flow.subjectEnabled && !flow.subjectReady->"先计算保护";flow.subjectPicking->"请结束选点";workflow.composeAfter->"显影并构图";flow.hasResult->"重新显影";else->"显影"}
        runButton.text=if(composing && !flow.busy)"应用构图"else if(flow.busy)actionLabel else ReferenceWorkspacePolicy.shortAction(actionLabel)
        runButton.contentDescription=if(composing)"应用当前构图，返回编辑后再导出"else "$actionLabel · 下次${developMode.name}；只在点击后处理，HDR失败不转SDR"
        saveButton.text=""
        saveButton.contentDescription="导出成片；向上展开格式菜单，当前${if(current?.hdrReady?:sourceHdr)ExportMenuChoices.ULTRA_HDR_LABEL else ExportMenuChoices.exportFormat(flow.exportFormat,ExportPreferences.badge(this)).label}"
        val cancelUi=UiPolishPolicy.cancelUi(flow.operation,flow.cancelling,cancel!=null)
        cancelButton.visibility=if(cancelUi.visible)View.VISIBLE else View.GONE
        runButton.visibility=if(cancelUi.visible)View.GONE else View.VISIBLE
        cancelButton.isEnabled=cancelUi.enabled;cancelButton.isSelected=true
        cancelButton.text=if(dock.iconOnly)""else cancelUi.label
        cancelButton.contentDescription=if(flow.cancelling)"正在取消，请等待当前步骤安全退出；不会发布半成品"else "取消当前处理；原片与上次完整成片保留"
        ViewCompat.setStateDescription(cancelButton,if(flow.cancelling)"正在取消，不可重复"else "可取消当前处理")
        progress.visibility=if(flow.busy)View.VISIBLE else View.INVISIBLE;progressLabel.visibility=View.VISIBLE
        // The persisted full record stays intact; its failures/cancellation are mirrored into the visible info line.
        status.visibility=View.GONE;elapsedLabel.visibility=View.GONE
        status.contentDescription="${status.text} · 轻点信息行查看完整处理记录"
        actionStatus.minimumHeight=dp(44)
        // The status row measures the current content width (including window insets), not the display configuration.
        if(flow.busy)nextModeRow.visibility=View.GONE
        completedGenerationMs?.let{ms->val blocks=ProcessTimingText.compact(current?.stageSummary.orEmpty());elapsedLabel.text="显影 ${ProcessingTime.seconds(ms)} · 耗时明细 ›${if(flow.resultStale)" · 待应用"else ""}"+if(blocks.isBlank())""else "\n$blocks"}
        workflowPanel.visibility=if(composing)View.GONE else View.VISIBLE
        compositionPanel.visibility=if(composing)View.VISIBLE else View.GONE
        compositionBack.isEnabled=composing
        compositionApply.isEnabled=compositionChrome.apply
        compositionExit.isEnabled=compositionChrome.exit
        compositionExit.text=if(flow.cancelling)"正在退出…"else "退出构图"
        compositionExit.contentDescription=if(flow.busy)"退出构图；先取消并等待分析安全退出，不应用裁框"else "退出构图，不应用当前方案，不自动保存"
        compositionApply.contentDescription="应用编辑：只应用当前裁框，返回编辑后再手动导出"
        compositionRetry.isEnabled=composing && !flow.busy && !compositionClosing
        if(composing){pickButton.isEnabled=false;modeSwitch.bind(developMode,false);saveButton.isEnabled=false;modeButtons.forEach{it.isEnabled=false}}
        for(i in 0 until compositionChoices.childCount){val v=compositionChoices.getChildAt(i);v.isEnabled=compositionChrome.select;v.isSelected=i==compositionSelected;v.background=WorkspacePanels.selectionBackground(v,i==compositionSelected);ViewCompat.setStateDescription(v,if(v.isSelected)"已选择"else "未选择")}
        if(composing && compositionItems.isNotEmpty() && !flow.busy)showCompositionSize()
        updateViewerLabels();updateProgress();persistEditor()
    }
    private fun adaptChrome(){
        val cfg=resources.configuration
        val chrome=ReferenceWorkspacePolicy.chrome(cfg.screenHeightDp,cfg.fontScale)
        modeSwitch.requestLayout()
        apertureDial.layoutParams.height=dp((102*cfg.fontScale.coerceAtLeast(1f)).toInt())
        val iconOnlyTabs=UiPolishPolicy.tabsIconOnly(cfg.screenHeightDp,cfg.fontScale)
        tabs.minimumWidth=dp(if(iconOnlyTabs)visibleViewers.size*48+(visibleViewers.size-1)*6 else 5*58+4*6)
        (viewerTabs.layoutParams as LinearLayout.LayoutParams).also{it.height=dp(if(iconOnlyTabs)48 else chrome.tabHeight);it.topMargin=dp(if(chrome.compact)6 else 10);viewerTabs.layoutParams=it}
        modeButtons.forEachIndexed{i,b->
            b.text=if(iconOnlyTabs)""else when(visibleViewers[i]){Viewer.THERMAL->"深度";else->visibleViewers[i].label}
            b.setPadding(dp(3),dp(if(iconOnlyTabs)4 else 8),dp(3),dp(4));b.compoundDrawablePadding=dp(if(iconOnlyTabs)2 else 6)
        }
        header.setPadding(0,0,0,dp(if(chrome.compact)6 else 10));titleLabel.textSize=if(chrome.compact)18f else 21f
        val dock=UiPolishPolicy.dock(cfg.screenWidthDp,cfg.fontScale,chrome.compact)
        val capsule=runButton.parent as LinearLayout
        (capsule.layoutParams as LinearLayout.LayoutParams).also{it.marginStart=dp(dock.margin);it.marginEnd=dp(dock.margin);capsule.layoutParams=it}
        listOf(runButton,cancelButton).forEachIndexed{i,b->
            b.setPadding(dp(if(dock.labelOnly)4 else 10),dp(6),dp(if(dock.labelOnly)4 else 10),dp(6))
            if(i==0 || dock.labelOnly)b.setCompoundDrawables(null,null,null,null)else glyph(b,WorkspaceGlyph.Kind.STOP,18)
            b.minimumHeight=dp(dock.buttonHeight);b.minHeight=b.minimumHeight;b.layoutParams.height=WRAP_CONTENT;b.requestLayout()
        }
        // Only live layout bounds change; no HDR screenshot, copy, decoding or reparenting.
        editor.requestLayout()
    }
    private fun diagnosticFailure()=MetadataExportDiagnostics.errors(lastFailure,lastMetadataFailure,ExternalModeRuntime.status.lastError,ExternalModeRuntime.status.lastMetadataError)
    private fun publishDiagnostics(snapshot:LiveDiagnostics.Snapshot){diagnosticsPanel?.publish(snapshot)}
    private fun showDiagnostics(){
        if(diagnosticsDialog?.isShowing==true)return
        val panel=DiagnosticsPanel(this,{
            val on=runtimeMonitor==null
            if(on && !ExternalPermissions.notifications(this)){
                if(android.os.Build.VERSION.SDK_INT>=33 && !ExternalPermissions.granted(this,android.Manifest.permission.POST_NOTIFICATIONS))monitorPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else openMonitorSettings()
            }else setEditorMonitor(on)
        },{runtimeMonitor!=null})
        panel.publishError(diagnosticFailure())
        diagnosticsPanel=panel
        diagnosticsDialog=ExportUi.panel(diagnosticsButton,"实时运行监测",panel,620,460)?.also{handle->
            handle.onDismiss={diagnostics.stop();diagnosticsPanel=null;diagnosticsDialog=null}
        }
        if(diagnosticsDialog!=null)diagnostics.start(lifecycleScope,::publishDiagnostics)
    }

    private fun openMonitorSettings(){
        MonitorPreferences.saveEditor(this,true)
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
    }
    private fun startEditorMonitor(){
        if(runtimeMonitor!=null || ExternalModeRuntime.status.running || !ExternalPermissions.notifications(this))return
        runtimeMonitor=RuntimeMonitor(this,RuntimeMonitor.EDITOR_ID,"传奇一瞬 · 运行参数",LeicaActivity::class.java){(if(flow.busy)EditorPresentation.stage(progressMessage)else "等待编辑")+MetadataExportDiagnostics.summary(lastMetadataFailure)?.let{"\n最近错误：$it"}.orEmpty()}.takeIf{it.start()}
    }
    private fun stopEditorMonitor(){runtimeMonitor?.stop();runtimeMonitor=null;diagnosticsPanel?.syncNotification()}
    private fun setEditorMonitor(on:Boolean){
        MonitorPreferences.saveEditor(this,on)
        if(on)startEditorMonitor() else stopEditorMonitor()
        diagnosticsPanel?.syncNotification();if(::runButton.isInitialized)updateDevelopActions()
    }
    private fun updateProgress(){
        if(!::progressLabel.isInitialized)return
        recordSummary=if(flow.cancelling)"正在取消 · 等待当前步骤安全退出"else if(flow.busy){
            val actual="${EditorPresentation.stage(progressMessage)} · ${ProcessingTime.seconds(SystemClock.elapsedRealtime()-phaseStarted)}"
            if(flow.operation==PhotoFlow.Operation.GENERATE && ::developView.isInitialized)developView.motion.statusText(actual)else actual
        }else when{
            ReferenceWorkspacePolicy.urgent(status.text.toString())->ReferenceWorkspacePolicy.importantLine(status.text.toString())
            !flow.hasPhoto->"选择照片，或从右上角更多进入外接"
            composing->"选择方案，再点应用"
            flow.picking->"轻点照片，选择唯一焦点"
            flow.subjectPicking->"选择保护点，选好后统一计算"
            flow.resultStale->"参数待应用 · 可导出上次成片"
            flow.hasPhoto && flow.blur && flow.focus==null->"请在参数中点选一个焦点"
            flow.hasPhoto && flow.blur && flow.subjectEnabled && !flow.subjectReady->"请先完成主体保护计算"
            flow.hasPhoto && !flow.hasResult->"未显影 · ${sourceWidth}×${sourceHeight}"
            else->{val plan=if(flow.hasResult)currentCropPlan()else null;ReferenceWorkspacePolicy.dimensions(flow.hasPhoto,plan?.width?:sourceWidth,plan?.height?:sourceHeight,completedGenerationMs)}
        }
        progressLabel.text=FullscreenMotion.recordLabel(recordSummary,(progressLabel.width/resources.displayMetrics.density).coerceAtLeast(0f),resources.configuration.fontScale)
        progressLabel.visibility=View.VISIBLE
        progressLabel.contentDescription="处理记录。$recordSummary。${previewTag.text}。${status.text}。轻点从此入口展开完整处理记录，处理完成后仍可查看。"
        progressLabel.setTextColor(if(!flow.busy && ReferenceWorkspacePolicy.urgent(status.text.toString()))0xffd8a18f.toInt()else 0xff9f9a92.toInt())
    }
    private fun showProcessTiming(){
        if(flow.busy || choicePanel?.isShowing==true)return;val r=current?:return
        val content=label(ProcessTimingText.details(r.totalMs,r.stageSummary,lastExportMs),13f).apply{setTextIsSelectable(true)}
        choicePanel=ExportUi.panel(moreButton,"耗时明细",content,600,460)
    }

    private fun stage(message:String){
        if(!flow.busy || isDestroyed)return
        val key=BokehProgress.key(message);if(key!=phase){phase=key;phaseStarted=SystemClock.elapsedRealtime()}
        progressMessage=message;if(composing)compositionInfo.text=message
        val real=BokehProgress.bar(message)
        if(real!=null){progress.isIndeterminate=false;progress.max=real.second;progress.progress=real.first}
        else if(BokehProgress.owns(message)){progress.isIndeterminate=true}
        else {val n=message.substringBefore('/').toIntOrNull();progress.isIndeterminate=n==null;if(n!=null){progress.max=7;progress.progress=n}}
        updateProgress()
    }
    private fun operation(op:PhotoFlow.Operation,message:String,afterSuccess:()->Unit={},action:suspend()->Unit){
        if(flow.busy)return
        if(op==PhotoFlow.Operation.GENERATE || op==PhotoFlow.Operation.PREVIEW){completedGenerationMs=null;lastExportMs=null}
        cancel=if(op in listOf(PhotoFlow.Operation.GENERATE,PhotoFlow.Operation.FOCUS_PREVIEW,PhotoFlow.Operation.DEPTH,PhotoFlow.Operation.COMPOSITION,PhotoFlow.Operation.SAVE))Cancel()else null
        flow.begin(op);started=SystemClock.elapsedRealtime();phaseStarted=started;phase="";progressMessage=message;progress.isIndeterminate=true;refresh()
        ticker=lifecycleScope.launch{while(isActive){delay(1000);updateProgress()}}
        job=lifecycleScope.launch{
            var settled=false;var completed=false
            try{withContext(Dispatchers.IO){engine.memory.begin(message)};cancel?.check();action();if(op==PhotoFlow.Operation.GENERATE && completedGenerationMs==null)completedGenerationMs=SystemClock.elapsedRealtime()-started;completed=true;settled=true}
            catch(e:CancellationException){settled=true;status.text="已取消，未发布半成品。已有结果仍可保存。"}
            catch(e:Exception){propagateFatalCause(e);settled=true;if(cancel?.cancelled==true)status.text="已取消，可以重新开始。" else {lastFailure=e.stackTraceToString();status.text=UiText.failure(e)}}
            finally{if(settled){
                withContext(NonCancellable+Dispatchers.IO){engine.memory.finish()}
                val again=op==PhotoFlow.Operation.FOCUS_PREVIEW && completed && cancel?.cancelled!=true && previewUpdates.pending
                if(op==PhotoFlow.Operation.FOCUS_PREVIEW && !completed){previewUpdates.discard();viewer=Viewer.ORIGINAL}
                val success=completed && cancel?.cancelled!=true
                if(op==PhotoFlow.Operation.GENERATE){interruptedKind=withContext(NonCancellable+dispatcher){session.store.get("job.kind").orEmpty()}}
                if(op==PhotoFlow.Operation.COMPOSITION && !success){compositionPreview.restoreSelection();compositionInfo.text=status.text}
                if(op==PhotoFlow.Operation.FOCUS_PREVIEW && !success)subjectWork.abort()
                cancel=null;ticker?.cancel();flow.finish();depthCalculating=false
                if(op==PhotoFlow.Operation.GENERATE){developView.reset();compare.visibility=View.VISIBLE;compare.alpha=1f}
                if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))stopEditorMonitor()
                if(!isDestroyed && !isFinishing){if(leaveCompositionWhenIdle){leaveCompositionWhenIdle=false;closeComposition()};if(!stageView.isLoading)applyViewer();refresh();if(op==PhotoFlow.Operation.FOCUS_PREVIEW)refreshCachedSubjects();if(again && pendingPhoto.pending==null)startFocusPreview();if(success)afterSuccess();window.decorView.post{drainPicked()}}
            }}
        }
    }
    private fun acceptPicked(uri:Uri,renditionAccepted:Boolean=false){
        pendingPhoto.offer(uri.toString(),renditionAccepted)
        if(flow.busy)status.text="已选中照片，当前操作结束后自动导入。原图和上次编辑暂未改动。"
        window.decorView.post{drainPicked()}
    }
    private fun drainPicked(){
        if(isDestroyed || isFinishing || pickerNotice?.isShowing==true || pendingMetadataImport!=null)return
        val next=pendingPhoto.ready(flow.busy,lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))?:return
        val uri=Uri.parse(next.uri)
        if(PhotoImportPolicy.picker(uri.scheme,uri.authority,uri.pathSegments.firstOrNull()) && !next.renditionAccepted){
            pickerNotice=AlertDialog.Builder(this).setTitle("相册选择器提供的照片")
                .setMessage("系统相册可能隐藏照片的位置等信息。继续导入不会主动压缩图片，但无法找回已隐藏的信息。需要完整拍摄信息时，建议改用“从文件选择”。")
                .setPositiveButton("继续导入"){_,_->pendingPhoto.offer(next.uri,true);pickerNotice=null;window.decorView.post{drainPicked()}}
                .setNeutralButton("从文件选择"){_,_->pendingPhoto.clear();pickerNotice=null;documentPicker.launch(arrayOf("image/*"))}
                .setNegativeButton("取消"){_,_->pendingPhoto.clear();pickerNotice=null}
                .setOnCancelListener{pendingPhoto.clear();pickerNotice=null}.show()
            return
        }
        pendingPhoto.clear();onPicked(uri)
    }
    override fun onSaveInstanceState(outState:Bundle){
        outState.putString("import.metadata",pendingMetadataImport?.toString())
        pendingPhoto.pending?.let{outState.putString("import.selected",it.uri);outState.putBoolean("import.accepted",it.renditionAccepted)}
        super.onSaveInstanceState(outState)
    }
    private fun onPicked(uri:Uri){
        if(PhotoImportPolicy.original(android.os.Build.VERSION.SDK_INT,uri.scheme,uri.authority,uri.pathSegments.firstOrNull()) && checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingMetadataImport=uri;metadataPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION);return
        }
        operation(PhotoFlow.Operation.PREVIEW,"读取原图"){
            val owner=currentCoroutineContext()
            var pending:Bitmap?=null
            try {
                // Complete all fallible source inspection before replacing the durable edit.
                val prepared=withContext(dispatcher){
                    val imported=photoImports.import(uri){owner.ensureActive()}
                    val dimensions=PhotoPixels.inspect(applicationContext,imported).displaySize
                    val hdr=HdrAutoSource.detect(applicationContext,imported)
                    pending=engine.loadPreview(imported,hdr)
                    owner.ensureActive();Triple(imported,dimensions,hdr)
                }
                owner.ensureActive()
                // Once the checkpoint commits, cancellation must not leave the UI on the deleted old source.
                withContext(NonCancellable){
                    val (imported,dimensions,hdr)=prepared
                    val local=withContext(dispatcher){
                        session.importSource(java.io.File(checkNotNull(imported.path))){owner.ensureActive()}
                        Uri.fromFile(checkNotNull(session.source()))
                    }
                    val bmp=checkNotNull(pending)
                    previewUpdates.discard();subjectWork.invalidate();interruptedKind="";lastEditorState=null
                    crop=CompositionGeometry.FULL;compare.crop=crop;sourceWidth=dimensions.first;sourceHeight=dimensions.second
                    clearDepthViews();developView.reset();stageView.setPhoto(bmp);compare.setSources(local,null);compare.original=bmp;pending=null;compare.result=null;compare.resetViewport();compare.focusOverlay=null;compare.subjectOverlay=null
                    photo=local;sourceHdr=hdr;developMode=HdrDevelopMode.restore(null,hdr);current=null;resultAppearance=null;flow.photoLoaded();viewer=Viewer.ORIGINAL;restored=true
                    withContext(dispatcher){engine.clearDepthCache();engine.clearCache()}
                    status.text=if(sourceHdr)"HDR 照片已导入 · 请选择 HDR 或 SDR 显影。"else "照片已导入 · 选择参数后点显影。"
                }
            }finally{pending?.recycle()}
        }
    }
    private fun showSubjectRemoval(){
        if(flow.busy || !flow.subjectEnabled || flow.subjectPoints.isEmpty())return
        val points=flow.subjectPoints.toList();val ticket=flow.subjectTicket()
        showChoices(subjectUndoButton,"移除指定保护点",points.mapIndexed{i,p->"${i+1}号点 · 横向${(p.x*100).toInt()}% / 纵向${(p.y*100).toInt()}%"},-1,"只移除选点，不重新运行模型。"){index->
            if(!flow.busy && flow.subjectTicket()==ticket && flow.removeSubjectPoint(points[index]))subjectSelectionChanged()
        }
    }

    private fun subjectSelectionChanged(){
        subjectWork.invalidate();previewUpdates.discard();stageView.clearSubject()
        if(flow.subjectCompletedCount==0)compare.subjectOverlay=null
        status.text="已选${flow.subjectPoints.size}点，已完成${flow.subjectCompletedCount}点；新增或移动只计算缺失点，移除不运行模型。可拖动蓝色编号调整位置。"+if(flow.focus==null)"请先另选一个景深焦点。" else ""
        viewer=Viewer.ORIGINAL;applyViewer();refresh()
        val uri=photo?:return;val points=flow.subjectPoints.toList();val focus=flow.focus;val resolution=flow.subjectResolution
        // Selection is an explicit ordered mutation; asynchronous presentation reads below are read-only.
        worker.execute{engine.selectSubjects(uri,points,focus,resolution)}
        refreshCachedSubjects()
    }
    /** Selection edits only merge existing alpha; no decode, depth or subject model call. */
    private fun refreshCachedSubjects(){
        val uri=photo?:return;val ticket=flow.subjectTicket();val bmp=compare.original?:return
        val w=bmp.width;val h=bmp.height;val focus=flow.focus;val readEpoch=subjectWork.epoch;val resolution=flow.subjectResolution
        lifecycleScope.launch{
            try{
                val state=withContext(dispatcher){
                    if(!engine.subjectSelectionMatches(uri,ticket.points,focus,resolution))return@withContext null
                    val done=engine.completedSubjects(uri,ticket.points)
                    val alpha=engine.cachedSubjects(uri,ticket.points,Cancel())
                    // A 3D allocation/presentation failure must not discard completion or the blue mask.
                    val motion=try{engine.cachedSubjectMotion(uri,ticket.points,focus,Cancel())}catch(e:Exception){propagateFatalCause(e);null}
                    Triple(done,alpha?.let{SubjectProtectionMath.overlay(it,w,h,Cancel())},motion)
                }
                if(state!=null && photo==uri && flow.subjectTicket()==ticket && subjectWork.acceptsRead(readEpoch) && !flow.busy && flow.subjectEnabled && !isDestroyed){
                    val overlay=state.second?.let{Bitmap.createBitmap(it,w,h,Bitmap.Config.ARGB_8888)}
                    flow.acceptSubjectCompletion(ticket,state.first)
                    compare.subjectOverlay=overlay
                    if(flow.subjectReady && overlay!=null)subjectWork.publish(ticket,state.first,true)
                    state.third?.let{stageView.presentSubject(it,false)};applyViewer();refresh()
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){propagateFatalCause(e);if(flow.subjectTicket()==ticket && subjectWork.acceptsRead(readEpoch) && !flow.busy){lastFailure=e.stackTraceToString();status.text=UiText.failure(e)}}
        }
    }
    private fun requestFocusPreview(computeSubject:Boolean=false){
        if(photo==null || flow.focus==null || !flow.blur || !flow.canAdjustOptics)return
        if(computeSubject){
            if(!flow.canComputeSubjects || subjectWork.needs(flow.subjectTicket()))return
            subjectWork.request(flow.subjectTicket());flow.endSubjectPicking()
        }else if(flow.subjectPicking)return
        subjectWork.changed();previewUpdates.request();compare.focusOverlay=null;viewer=Viewer.ORIGINAL
        if(!flow.busy){applyViewer();startFocusPreview()}
        else {if(computeSubject)stage("主体保护 · 请求已排队，景深提示后继续计算待完成点");refresh()}
    }
    private fun startFocusPreview(){
        val uri=photo ?: return;val point=flow.focus ?: return
        if(flow.busy || !flow.blur || !previewUpdates.pending)return
        val selectedPoints=flow.subjectPoints.toList();val subjectTicket=flow.subjectTicket();val resolution=flow.subjectResolution
        val editorState=editorState()
        subjectWork.changed()
        operation(PhotoFlow.Operation.FOCUS_PREVIEW,if(subjectWork.needs(subjectTicket))"主体保护 · 准备计算待完成点" else "计算景深提示"){
            val c=checkNotNull(cancel)
            withContext(dispatcher){session.ui(editorState);engine.selectSubjects(uri,selectedPoints,point,resolution);if(subjectWork.needs(subjectTicket))session.job("subject")}
            try{while(true){
                delay(90);c.check();val ticket=previewUpdates.ticket;val ap=flow.aperture;val size=outputSize
                // Re-evaluate each pass: a subject request may arrive during an optical-only pass.
                val includeSubject=flow.subjectEnabled && (flow.subjectReady || subjectWork.needs(subjectTicket))
                val preview=withContext(dispatcher){engine.focusPreview(uri,point,ap,size,c,subjectEnabled=includeSubject,subjectPoints=selectedPoints,onSubjectCommitted={done->runOnUiThread{
                    if(!isDestroyed && photo==uri && cancel===c && !c.cancelled && flow.acceptSubjectCompletion(subjectTicket,done)){subjectWork.changed();refresh()}
                }}){message->runOnUiThread{if(cancel===c)stage(message)}}}
                c.check();currentCoroutineContext().ensureActive()
                if(photo!=uri || flow.subjectTicket()!=subjectTicket)throw CancellationException("保护选择已变更")
                flow.acceptSubjectCompletion(subjectTicket,preview.completedSubjects)
                if(!previewUpdates.publish(ticket))continue
                val overlay=preview.overlay
                if(overlay==null){flow.focusUnreliable();subjectWork.invalidate();stageView.clearSubject();compare.subjectOverlay=null;withContext(dispatcher){engine.clearSubjectCache()};status.text="该点深度不可靠，未自动换焦点。请点选目标内部。"}
                else{
                    if(includeSubject)check(preview.subjectProtected && preview.completedSubjects.toSet()==selectedPoints.toSet() && preview.subjectOverlay!=null){"保护结果尚未完整应用，请重新计算待完成点"}
                    val focusBitmap=Bitmap.createBitmap(overlay.argb,overlay.width,overlay.height,Bitmap.Config.ARGB_8888)
                    val subjectBitmap=preview.subjectOverlay?.let{Bitmap.createBitmap(it,overlay.width,overlay.height,Bitmap.Config.ARGB_8888)}
                    compare.focusOverlay=focusBitmap
                    if(preview.subjectProtected){
                        compare.subjectOverlay=subjectBitmap
                        subjectWork.publish(subjectTicket,preview.completedSubjects,subjectBitmap!=null)
                        viewer=Viewer.RELIEF
                    }
                    status.text="光圈 $ap · ${if(preview.reusedDepth)"复用深度" else "深度就绪"}\n绿：清晰区 · 金：过渡区。提示不写入成片。"+when{
                        preview.subjectProtected->"\n主体保护 · ${preview.completedSubjects.size}/${selectedPoints.size}点已计算并应用；蓝色范围为实际保护范围。"
                        flow.subjectEnabled && flow.subjectPendingCount>0->"\n本次仅更新景深提示；还有${flow.subjectPendingCount}个保护点待算，请点击「计算新增/待算」。"
                        else->""
                    }
                };break
            }}finally{
                // Actual cache commits survive later presentation errors. Never consume a request here.
                val done=withContext(NonCancellable+dispatcher){engine.completedSubjects(uri,selectedPoints)}
                interruptedKind=withContext(NonCancellable+dispatcher){session.store.get("job.kind").orEmpty()}
                if(photo==uri && flow.acceptSubjectCompletion(subjectTicket,done))subjectWork.changed()
            }
        }
    }
    private fun generate(requested:HdrDevelopMode=developMode){
        val uri=photo ?: return;if(!workflow.canStart(flow))return
        val choice=if(sourceHdr)requested else HdrDevelopMode.SDR
        if(workflow.onlyComposition || HdrDisplayPolicy.reuseForComposition(workflow.composeAfter,current!=null,flow.resultStale,choice,current?.hdrReady==true)){openComposition();return}
        check(choice!=HdrDevelopMode.AUTO)
        if(developMode!=choice){developMode=choice;flow.parametersChanged()}
        val selectedWorkflow=workflow;val selectedStyle=flow.style
        val savedEditor=editorState();val resolution=flow.subjectResolution
        val blur=flow.blur;val subject=flow.subjectEnabled;val selectedPoints=flow.subjectPoints.toList();val color=flow.colorEnabled;val point=flow.focus;val ap=flow.aperture;val size=selectedWorkflow.output(outputSize)
        previewUpdates.discard()
        operation(PhotoFlow.Operation.GENERATE,"准备${choice.name}显影 · ${flow.processingMode.label}",afterSuccess={
            if(selectedWorkflow.shouldOpenComposition(true,false)){
                if(!blur || resultAppearance?.blur==true){pendingComposition=true;continueComposition()}
                else status.text="${status.text}\n未自动进入构图：虚化未成功，请重新点选焦点。已有成片保留。"
            }
        }){
            val c=checkNotNull(cancel)
            withContext(dispatcher){session.ui(savedEditor);engine.selectSubjects(uri,selectedPoints,point,resolution)}
            interruptedKind=""
            compare.original?.let{developView.beginSource(it)};updateHdrWindow()
            val unpublished=DevelopTransfer<LeicaEngine.Result>{r->r.original.recycle();r.result.recycle();if(r.file.parentFile!=session.store.directory){r.file.delete();HdrResultStore.remove(r.file)}}
            try {
            val result=withContext(dispatcher){engine.process(uri,selectedStyle,c,blur,point,ap,size,color,subjectEnabled=subject,subjectPoints=selectedPoints,renderSize=if(crop==CompositionGeometry.FULL)null else EditorCrop.renderPlan(sourceWidth,sourceHeight,crop,size,blur).let{it.workWidth to it.workHeight},hdrMode=choice,onVisual={event->
                runOnUiThread{
                    if(isDestroyed || isFinishing || cancel!==c || c.cancelled){event.dispose();return@runOnUiThread}
                    stageView.hide();compare.visibility=View.INVISIBLE;developView.accept(event);updateHdrWindow()
                }
            }){message->runOnUiThread{if(cancel===c)stage(message)}}.also{unpublished.offer(it)}}
            try{
                c.check();currentCoroutineContext().ensureActive()
                // Presentation waits for real result data; inference is never delayed by the animation.
                completedGenerationMs=result.totalMs
                stage("生成完成 · 正在呈现")
                suspendCancellableCoroutine<Unit>{cont->
                    developView.finish(result.result,result.rendered,crop){completed->
                        if(cont.isActive){if(completed)cont.resume(Unit)else cont.cancel(CancellationException("已取消呈现"))}
                    }
                    updateHdrWindow()
                    cont.invokeOnCancellation{runOnUiThread{if(cancel===c)developView.reset()}}
                }
                c.check();currentCoroutineContext().ensureActive()
            }catch(e:Exception){unpublished.close();throw e}
            check(unpublished.take()===result)
            current=result;resultAppearance=CompositionAppearance(color,result.rendered,point,ap,result.style);flow.generated();if(result.subjectProtected)flow.subjectCompleted()
            stageView.setPhoto(result.original);compare.setSources(uri,Uri.fromFile(result.previewFile));compare.original=result.original;compare.result=result.result;viewer=Viewer.RESULT;lastViewer=viewer;compare.resetViewport();compare.mode=CompareView.Mode.RESULT;compare.alpha=1f;compare.visibility=View.VISIBLE
            withContext(dispatcher){engine.clearCache(result.file)}
            status.text="显影完成 · ${currentCropPlan().label()} · ${if(result.hdrReady)"HDR" else "SDR"} · 尚未导出"+if(blur && !result.rendered)"\n${result.reason}"else ""
            }finally{unpublished.close()}
        }
    }
    private fun continueComposition(){
        if(pendingComposition && !flow.busy && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){
            pendingComposition=false;openComposition()
        }
    }
    private fun currentCropPlan():EditorCrop.Plan = EditorCrop.plan(current?.width?:sourceWidth,current?.height?:sourceHeight,crop,workflow.output(outputSize),workflow.allowsOutputSelection)
    private fun openComposition(){
        if(flow.busy || photo==null || composing)return
        parametersBeforeComposition=flow.parametersExpanded;flow.showParameters(true)
        compositionOptionsExpanded=false;editor.anchor=parametersButton;composing=true;applyViewer();refresh();analyseComposition()
    }
    private fun analyseComposition(){
        if(flow.busy || !composing)return
        val uri=current?.let{Uri.fromFile(it.file)}?:photo?:return
        // Analysis still reads the existing SDR pixels; the display reads the true gainmap companion.
        val hdrDisplay=current?.hdrReady?:sourceHdr
        val displayUri=current?.let{Uri.fromFile(it.previewFile)}?:uri
        val fullW=current?.width?:sourceWidth;val fullH=current?.height?:sourceHeight
        val parent=CompositionGeometry.snapped(crop,fullW,fullH)
        val box=CompositionGeometry.pixels(parent,fullW,fullH)
        // Existing results are not secretly rerendered to satisfy the old pixel budget filter.
        val budget=if(current!=null)BokehOutput.ORIGINAL else workflow.output(outputSize)
        compositionPreview.close();compositionLoaded?.bitmap?.recycle();compositionLoaded=null
        compositionLoadingBitmap?.recycle();compositionLoadingBitmap=null
        compositionItems=emptyList();compositionChoices.removeAllViews();compositionTabs.scrollTo(0,0);compositionSelected=0
        compositionPreview.beginAnalysis()
        operation(PhotoFlow.Operation.COMPOSITION,"正在分析当前画面的构图"){
            val c=checkNotNull(cancel)
            val transfer=DevelopTransfer<CompositionFiles.Loaded>{it.bitmap.recycle()}
            try {
                val (data,items)=withContext(dispatcher){
                    val loaded=CompositionFiles.loadSource(applicationContext,uri,parent,c){decoded->
                        val display=if(hdrDisplay)HdrPreview.crop(engine.loadPreview(displayUri,true),parent)else HdrPreview.copy(decoded)
                        runOnUiThread{
                            if(isDestroyed || isFinishing || cancel!==c || c.cancelled || !composing){display.recycle();return@runOnUiThread}
                            val old=compositionLoadingBitmap;compositionLoadingBitmap=display
                            compositionPreview.setPhoto(display,box.w,box.h);old?.recycle();updateHdrWindow()
                        }
                    }.also{transfer.offer(it)}
                    loaded to gaic.analyse(applicationContext,loaded.image,loaded.sourceW,loaded.sourceH,budget,c)
                }
                c.check();currentCoroutineContext().ensureActive();check(transfer.take()===data)
                compositionLoaded=data;compositionItems=items
                val display=if(hdrDisplay)checkNotNull(compositionLoadingBitmap){"HDR构图预览尚未就绪，未用SDR替代"}else data.bitmap
                compositionPreview.setPhoto(display,data.sourceW,data.sourceH)
                if(!hdrDisplay){compositionLoadingBitmap?.recycle();compositionLoadingBitmap=null}
                updateHdrWindow()
                compositionPreview.showProposals(items.map{it.crop},0)
                items.forEachIndexed{index,item->
                    val card=LinearLayout(this@LeicaActivity).apply{
                        gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));isFocusable=true;isClickable=true
                        contentDescription="方案${index+1} · ${item.title}";setOnClickListener{if(!flow.busy && !compositionClosing){compositionSelected=index;compositionPreview.selectProposal(index);refresh()}}
                    }
                    card.addView(CompositionThumbnail(this@LeicaActivity,display,item.crop),LinearLayout.LayoutParams(dp(60),dp(48)))
                    card.addView(label("方案${index+1}\n${item.title}",11f).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(dp(88),WRAP_CONTENT).apply{marginStart=dp(8)})
                    compositionChoices.addView(card,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{marginEnd=dp(8)})
                }
                status.text="构图分析完成 · 应用只更新裁框，不自动保存"
            }finally{transfer.close()}
        }
    }
    private fun selectedCompositionCrop():CompositionGeometry.Crop {
        val w=current?.width?:sourceWidth;val h=current?.height?:sourceHeight
        return EditorCrop.combine(crop,compositionItems[compositionSelected].crop,w,h)
    }
    private fun showCompositionSize(){
        val w=current?.width?:sourceWidth;val h=current?.height?:sourceHeight
        val plan=EditorCrop.plan(w,h,selectedCompositionCrop(),workflow.output(outputSize),workflow.allowsOutputSelection)
        val target=workflow.output(outputSize).longEdge
        val limited=target!=null && maxOf(plan.width,plan.height)<target
        compositionInfo.text="${if(current!=null)"现有成片" else "当前原图"} · ${plan.label()}${if(limited)"（裁后可用像素，不放大补足$target）" else ""}\n选择方案 → 应用；不重新调色、虚化，不自动保存。"
    }
    private fun applyComposition(){
        if(flow.busy || !composing || compositionItems.isEmpty())return
        crop=selectedCompositionCrop()
        flow.compositionApplied()
        if(current==null){compare.result=compare.original;compare.setSources(photo,photo)}
        compare.crop=crop;viewer=Viewer.RESULT
        val dimensions=currentCropPlan().label()
        closeComposition(applied=true)
        status.text="已应用构图 · $dimensions · 未保存\n复用当前像素，未重新调色或虚化。点击保存才导出。"
    }
    private fun leaveComposition(){
        if(flow.busy){leaveCompositionWhenIdle=true;cancel?.cancelled=true;gaic.cancelRun();if(!flow.cancelling)flow.cancel();refresh()}
        else closeComposition()
    }
    private fun closeComposition(applied:Boolean=false){
        if(!composing || compositionClosing)return
        compositionClosing=true;compositionOptionsExpanded=false
        runButton.isEnabled=false;parametersButton.isEnabled=false;compositionApply.isEnabled=false;compositionExit.isEnabled=false;compositionRetry.isEnabled=false
        for(i in 0 until compositionChoices.childCount)compositionChoices.getChildAt(i).isEnabled=false
        editor.close{
            compositionClosing=false
            flow.showParameters(if(applied)false else parametersBeforeComposition)
            composing=false;compositionPreview.close();compositionPreview.visibility=View.GONE
            compositionChoices.removeAllViews();compositionItems=emptyList()
            compositionLoaded?.bitmap?.recycle();compositionLoaded=null;compositionLoadingBitmap?.recycle();compositionLoadingBitmap=null
            applyViewer();refresh()
        }
    }
    private fun showExport(){
        if(flow.busy || !flow.hasResult || composing || exportMenu?.isShowing==true)return
        exportMenu=ExportUi.show(saveButton,flow.exportFormat,currentCropPlan().label(),selected={format->
            if(!flow.busy && !isDestroyed){flow.selectExportFormat(format);ExportPreferences.save(this,format);refresh()}
        },export={format->
            if(!flow.busy && !isDestroyed && !isFinishing){flow.selectExportFormat(format);ExportPreferences.save(this,format);save(keepHdr=false)}
        },sourceHdr=current?.hdrReady?:sourceHdr,hdrUnavailableReason=null,exportHdr={
            if(!flow.busy && !isDestroyed && !isFinishing)save(keepHdr=true)
        },badge=ExportPreferences.badge(this),badgeChanged={on->if(!isDestroyed){ExportPreferences.saveBadge(this,on);refresh()}},preserveMetadata=true,stateLabel=EditorWorkspacePolicy.renderedSummary(flow.hasResult,resultHdr=current?.hdrReady?:sourceHdr,pending=flow.resultStale))
    }
    private fun save(keepHdr:Boolean=current?.hdrReady?:sourceHdr){
        if(flow.busy || !flow.hasResult || composing)return
        val uri=current?.let{Uri.fromFile(it.file)}?:photo?:return
        val badge=ExportPreferences.badge(this)
        val plan=currentCropPlan();val format=ExportMenuChoices.exportFormat(flow.exportFormat,badge)
        val label=(if(keepHdr)"Ultra HDR JPEG"else format.label)+if(badge)" · 徕卡一瞬标识" else ""
        // Recheck capability in the worker too; never replace edited pixels with the original.
        val hdrResult=current
        operation(PhotoFlow.Operation.SAVE,"保存成片 · ${plan.label()} · $label"){
            val token=checkNotNull(cancel)
            // Explicit HDR export either succeeds as HDR or reports failure. Never retry it as SDR.
            val saveStarted=SystemClock.elapsedRealtime()
            val metadataErrors=MetadataExportDiagnostics.Report();var exportPublished=false
            try{
            val location=withContext(dispatcher){MetadataExportDiagnostics.collect(metadataErrors){
                val metadataSource=checkNotNull(session.source()){"原图元数据来源已失效"}
                if(keepHdr && hdrResult!=null)engine.saveHdr(hdrResult,plan.crop,plan.width to plan.height,token,badge,metadataSource){message->runOnUiThread{if(cancel===token && !isDestroyed)status.text=message}}
                else if(keepHdr)HdrPhotoExport.saveSource(applicationContext,uri,plan.crop,token,plan.width to plan.height,badge=badge,metadataSource=metadataSource)
                else CompositionFiles.saveSource(applicationContext,uri,plan.crop,token,plan.width to plan.height,format,badge,metadataSource)
            }}
            exportPublished=true
            lastExportMs=(SystemClock.elapsedRealtime()-saveStarted).coerceAtLeast(0)
            flow.saved();status.text="已保存 ${plan.label()} · $label\n$location";BlurToast.show(this@LeicaActivity,"成片已保存")
            }finally{
                lastMetadataFailure=MetadataExportDiagnostics.retain(lastMetadataFailure,metadataErrors,exportPublished)
                diagnosticsPanel?.publishError(diagnosticFailure())
            }
        }
    }
    private fun editorState():Map<String,String> = flow.snapshot()+mapOf(
        "workflow" to workflow.name,"output" to outputSize.name,"crop" to "${crop.l},${crop.t},${crop.r},${crop.b}",
        "width" to sourceWidth.toString(),"height" to sourceHeight.toString(),"hdrSource" to sourceHdr.toString(),"hdrDevelop" to developMode.name,
        "viewer" to viewer.name,"exportMs" to (lastExportMs?.toString()?:""),"status" to status.text.toString(),"region" to regionVisible.toString())
    private fun persistEditor(){
        if(!restored || flow.busy || photo==null || worker.isShutdown)return
        val state=editorState();if(state==lastEditorState)return
        lastEditorState=state
        worker.execute{try{session.ui(state)}catch(e:Exception){propagateFatalCause(e);runOnUiThread{lastEditorState=null;lastFailure=e.stackTraceToString();status.text="恢复记录保存失败：${e.message}；请勿关闭应用"}}}
    }
    private fun restoreLatest(){
        operation(PhotoFlow.Operation.PREVIEW,"恢复上次编辑",afterSuccess={
            restored=true;refresh();refreshCachedSubjects();if(interruptedKind.isNotEmpty())offerRecovery()
        }){
            val restoredData=withContext(dispatcher){
                val file=session.source()?:return@withContext null
                val uri=Uri.fromFile(file);val ui=session.ui()
                val result=engine.restoreResult(uri)
                Triple(uri,ui,result)
            }
            if(restoredData!=null){
                val (uri,state,result)=restoredData
                photo=uri;current=result
                if(state.containsKey("mode"))flow.restore(state)else flow.photoLoaded()
                workflow=state["workflow"]?.let{PhotoWorkflow.valueOf(it)}?:PhotoWorkflow.COLOR
                outputSize=state["output"]?.let{BokehOutput.valueOf(it)}?:outputSize
                state["crop"]?.split(",")?.map{it.toDouble()}?.let{crop=CompositionGeometry.Crop(it[0],it[1],it[2],it[3])}
                val dimensions=withContext(dispatcher){PhotoPixels.inspect(applicationContext,uri).displaySize}
                sourceWidth=dimensions.first;sourceHeight=dimensions.second;sourceHdr=withContext(dispatcher){HdrAutoSource.detect(applicationContext,uri)}
                val bmp=result?.original?:withContext(dispatcher){engine.loadPreview(uri,sourceHdr)}
                developMode=HdrDevelopMode.restore(state["hdrDevelop"],sourceHdr)
                compare.original=bmp;compare.result=result?.result?:if(flow.hasResult)bmp else null
                compare.setSources(uri,result?.let{Uri.fromFile(it.previewFile)}?:if(flow.hasResult)uri else null)
                stageView.setPhoto(bmp);compare.crop=crop
                if(result!=null){
                    val meta=withContext(dispatcher){session.store.properties()}
                    val p=meta["result.focus"]?.takeIf{it.isNotEmpty()}?.split(",")?.let{LegendaryMath.Point(it[0].toFloat(),it[1].toFloat())}
                    resultAppearance=CompositionAppearance(result.fused,result.rendered,p,meta["result.aperture"]?.toFloatOrNull()?:2.8f,result.style)
                    completedGenerationMs=result.totalMs
                }
                viewer=if(flow.hasResult)Viewer.RESULT else Viewer.ORIGINAL
                lastExportMs=state["exportMs"]?.toLongOrNull()?.takeIf{it>=0}
                regionVisible=state["region"]!="false"
                val restoredTicket=flow.subjectTicket();val restoredFocus=flow.focus;val restoredResolution=flow.subjectResolution
                val done=withContext(dispatcher){engine.selectSubjects(uri,restoredTicket.points,restoredFocus,restoredResolution);engine.completedSubjects(uri,restoredTicket.points)}
                flow.acceptSubjectCompletion(restoredTicket,done)
                interruptedKind=withContext(dispatcher){session.store.get("job.kind").orEmpty()}
                status.text=if(interruptedKind.isEmpty())"已恢复上次编辑 · ${state["status"].orEmpty()}"else "上次处理被中断 · 已保留最后完成步骤和已计算保护点，可继续或重新开始。"
            }
        }
    }
    private fun offerRecovery(){
        if(flow.busy || interruptedKind.isEmpty())return
        val kind=interruptedKind
        AlertDialog.Builder(this).setTitle("恢复上次处理")
            .setMessage("只保留这一份最新进度。继续会复用已保存的准备、完整基础区域、光能提取／全局散射、合成步骤和保护点；中断中的内部行或层会重算。照片或参数变化会重新计算。重新开始只清除本次中间计算，原片和已完成成片保留。")
            .setPositiveButton("继续"){_,_->
                interruptedKind=""
                if(kind=="subject")requestFocusPreview(computeSubject=true)else generate()
            }.setNegativeButton("重新开始"){_,_->
                operation(PhotoFlow.Operation.PREVIEW,"重置本次计算",afterSuccess={
                    interruptedKind=""
                    if(kind=="subject")requestFocusPreview(computeSubject=true)else generate()
                }){
                    withContext(dispatcher){session.clearRender();if(kind=="subject")engine.clearSubjectCache()}
                    if(kind=="subject"){flow.subjectInvalidated();subjectWork.invalidate();compare.subjectOverlay=null;stageView.clearSubject()}
                }
            }.setNeutralButton("稍后",null).show()
    }
    override fun onPostResume(){super.onPostResume();continueComposition();window.decorView.post{drainPicked()}}
    // Lifecycle reaches RESUMED after onResume returns on Android 14+. Recheck after dispatch;
    // updateHdrWindow still rejects a queued callback if the Activity was paused/destroyed meanwhile.
    override fun onResume(){super.onResume();if(ExternalModeRuntime.status.running)startActivity(android.content.Intent(this,ExternalModeActivity::class.java))else {if(MonitorPreferences.editor(this))startEditorMonitor()};diagnosticsPanel?.syncNotification();window.decorView.post{if(!isDestroyed){updateHdrWindow();if(::runButton.isInitialized)updateDevelopActions()}};if(::compositionPreview.isInitialized)compositionPreview.setActive(true);if(::developView.isInitialized)developView.setActive(true);if(::stageView.isInitialized)stageView.setActive(true);if(diagnosticsDialog?.isShowing==true)diagnostics.start(lifecycleScope,::publishDiagnostics)}
    override fun onPause(){stylePopup?.dismiss();morePopup?.dismiss();choicePanel?.dismiss();helpPanel?.dismiss();diagnosticsDialog?.dismiss();if(!flow.busy)stopEditorMonitor();if(::editor.isInitialized)editor.settle();if(::compositionDock.isInitialized)compositionDock.settle();HdrPreview.configure(window,false);photoFullscreen?.dismiss();photoFullscreen=null;spaceFullscreen?.dismiss();spaceFullscreen=null;exportMenu?.dismiss();exportMenu=null;if(::compositionPreview.isInitialized)compositionPreview.setActive(false);if(::developView.isInitialized)developView.setActive(false);if(::stageView.isInitialized)stageView.setActive(false);if(::diagnostics.isInitialized)diagnostics.stop();super.onPause()}
    override fun onConfigurationChanged(config:Configuration){stylePopup?.dismiss();morePopup?.dismiss();choicePanel?.dismiss();helpPanel?.dismiss();diagnosticsDialog?.dismiss();exportMenu?.dismiss();exportMenu=null;super.onConfigurationChanged(config);photoFullscreen?.resize();spaceFullscreen?.resize();if(::editor.isInitialized){if(::compositionDock.isInitialized)compositionDock.settle();adaptChrome();refresh()}}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);engine.memory.trim(level)}
    override fun onLowMemory(){super.onLowMemory();engine.memory.trim(15)}
    override fun onDestroy(){
        photoFullscreen?.dismiss();photoFullscreen=null;spaceFullscreen?.dismiss();spaceFullscreen=null;pickerNotice?.dismiss();pickerNotice=null
        previewUpdates.discard();cancel?.cancelled=true;engine.cancelRun();gaic.cancelRun();compositionPreview.close();compositionLoaded?.bitmap?.recycle();compositionLoadingBitmap?.recycle();job?.cancel();ticker?.cancel();diagnostics.stop();diagnosticsDialog?.dismiss();runtimeMonitor?.stop();runtimeMonitor=null;developView.close();stageView.close();clearDepthViews();compare.closeSources();compare.original=null;compare.result=null
        persistEditor()
        worker.execute{try{engine.close()}finally{engine.clearCache();photoImports.close();ExternalModeRuntime.editorBusy=false}};worker.shutdown();super.onDestroy()
    }
}
