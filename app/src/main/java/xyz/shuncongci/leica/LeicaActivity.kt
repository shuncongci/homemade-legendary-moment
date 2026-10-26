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
    private lateinit var compositionInfo:TextView;private lateinit var compositionApply:StudioButton;private lateinit var compositionRetry:StudioButton
    private lateinit var workflowPanel:LinearLayout;private lateinit var logPanel:LinearLayout
    private lateinit var compositionBack:OnBackPressedCallback
    private var photo:Uri?=null;private var current:LeicaEngine.Result?=null;private var sourceHdr=false
    private var developMode=HdrDevelopMode.SDR
    private lateinit var developActions:LinearLayout
    private lateinit var sdrRunButton:StudioButton
    private var resultAppearance:CompositionAppearance?=null
    private lateinit var compositionButton:StudioButton
    private var cancel:Cancel?=null;private var job:Job?=null;private var ticker:Job?=null
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
    private lateinit var diagnostics:LiveDiagnostics;private var diagnosticsDialog:AlertDialog?=null
    private var diagnosticsPanel:DiagnosticsPanel?=null;private var lastFailure:String?=null
    private var lastChrome:List<Any>?=null
    private var lastViewer:Viewer?=null
    // Optional second notification while editing; main thread only, lives with this Activity.
    private var runtimeMonitor:RuntimeMonitor?=null
    private val monitorPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)setEditorMonitor(true)else {
            diagnosticsPanel?.syncNotification()
            AlertDialog.Builder(this).setTitle("通知未开启").setMessage("可在系统设置中允许通知，再显示运行参数；不影响照片处理。")
                .setPositiveButton("去设置"){_,_->openMonitorSettings()}.setNegativeButton("取消",null).show()
        }
    }
    private val photoImports by lazy{PhotoImports(applicationContext)}
    private val documentPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null && !flow.busy)onPicked(uri)}
    private val picker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null && !flow.busy)onPicked(uri)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun surface(color:String,radius:Int=8)=GradientDrawable().apply{setColor(Color.parseColor(color));cornerRadius=dp(radius).toFloat()}
    private fun label(value:String,size:Float=12f,color:Int=0xff9f9a92.toInt())=TextView(this).apply{text=value;textSize=size;setTextColor(color);setLineSpacing(dp(2).toFloat(),1f)}
    private fun button(value:String,primary:Boolean=false)=StudioButton(this,primary).apply{text=value}
    private fun row(vararg children:View)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;children.forEachIndexed{i,v->addView(v,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{if(i>0)marginStart=dp(6)})}}
    private fun LinearLayout.block(view:View,margin:Int=10){addView(view,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(margin)})}
    private fun section(title:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=surface("#1C1D1F",10)
        block(label(title,12f,0xffd6cdbd.toInt()),0)
    }
    private fun transition(){
        if(ValueAnimator.areAnimatorsEnabled() && ::controls.isInitialized && controls.isLaidOut){
            // Restrict platform transitions to controls: never capture/fade an HDR image or reparent the photo.
            TransitionManager.beginDelayedTransition(controls,android.transition.TransitionSet().apply{
                ordering=android.transition.TransitionSet.ORDERING_TOGETHER;duration=240
                interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
                addTransition(android.transition.ChangeBounds());addTransition(android.transition.Fade())
            })
            TransitionManager.beginDelayedTransition(developActions,android.transition.ChangeBounds().apply{duration=240})
        }
    }
    private fun showPicker(){if(flow.busy)return;AlertDialog.Builder(this).setTitle("导入照片").setItems(arrayOf("从文件选择","从相册选择")){_,i->if(i==0)documentPicker.launch(arrayOf("image/*"))else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}.show()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
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
        flow.selectExportFormat(ExportPreferences.load(this))
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff151617.toInt())}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,dp(10))}
        header.addView(LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            addView(label("传奇一瞬",21f,0xffeee8dd.toInt()).apply{letterSpacing=.12f;setTypeface(Typeface.create("serif",Typeface.NORMAL))})
        },LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        header.addView(button("外接").apply{contentDescription="外接模式：仅监听开启后新增的相机照片，自动调色并导出";setOnClickListener{if(!flow.busy)startActivity(android.content.Intent(this@LeicaActivity,ExternalModeActivity::class.java))else AlertDialog.Builder(this@LeicaActivity).setMessage("请先等待或取消当前处理，再打开外接模式。").setPositiveButton("知道了",null).show()}},LinearLayout.LayoutParams(dp(60),WRAP_CONTENT))
        header.addView(button("监测").apply{contentDescription="查看当前机型实时诊断";setOnClickListener{showDiagnostics()}},LinearLayout.LayoutParams(dp(60),WRAP_CONTENT).apply{marginStart=dp(6)})
        parametersButton=button("收起").apply{setOnClickListener{flow.showParameters(!flow.parametersExpanded);refresh()}}
        header.addView(parametersButton,LinearLayout.LayoutParams(dp(60),WRAP_CONTENT).apply{marginStart=dp(6)})
        root.addView(header)
        val photoPane=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val imageArea=FrameLayout(this).apply{background=surface("#0D0E0F",6);clipToOutline=true}
        compare=CompareView(this);imageArea.addView(compare,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        stageView=DepthStageView(this).apply{visibility=View.GONE;onSubjectSettled={if(!isDestroyed && !isFinishing){applyViewer();refresh()}}};imageArea.addView(stageView,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        developView=DevelopView(this);imageArea.addView(developView,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        compositionPreview=CompositionCropView(this).apply{visibility=View.GONE};imageArea.addView(compositionPreview,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        emptyState=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(22),dp(16),dp(22),dp(16));isClickable=true;isFocusable=true;contentDescription="选择照片";setOnClickListener{showPicker()}
            addView(label("选择照片",22f,0xffc4b8a6.toInt()).apply{gravity=Gravity.CENTER})
        };imageArea.addView(emptyState,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        photoPane.addView(imageArea,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        previewTag=label("原图",10f).apply{maxLines=2;setPadding(dp(3),dp(7),dp(3),dp(7))};photoPane.addView(previewTag)
        spaceTools=row(button("全屏").apply{contentDescription="全屏观看空间视图";setOnClickListener{showSpaceFullscreen()}},
            button("缩小").apply{setOnClickListener{stageView.zoomBy(.8f)}},
            button("放大").apply{setOnClickListener{stageView.zoomBy(1.25f)}},
            button("复位").apply{contentDescription="复位空间视角、缩放和平移";setOnClickListener{stageView.resetView()}}).apply{visibility=View.GONE}
        photoPane.addView(spaceTools,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{bottomMargin=dp(6)})
        val tabs=LinearLayout(this).apply{minimumWidth=dp((308*resources.configuration.fontScale.coerceAtLeast(1f)).toInt())}
        modeButtons=Viewer.entries.map{v->button(when(v){Viewer.GRAY->"黑白";Viewer.THERMAL->"深度";else->v.label}).apply{
            contentDescription=v.label;setPadding(dp(4),dp(9),dp(4),dp(9));setOnClickListener{selectViewer(v)}
        }}
        modeButtons.forEachIndexed{i,b->tabs.addView(b,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{if(i>0)marginStart=dp(4)})}
        photoPane.addView(HorizontalScrollView(this).apply{isFillViewport=true;isHorizontalScrollBarEnabled=false;addView(tabs)},LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(2),0,dp(2),dp(8))}
        val scroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;clipToPadding=false;addView(controls)}
        workflowPanel=section("处理流程")
        processingButton=button(workflow.label).apply{setOnClickListener{
            if(!flow.busy)AlertDialog.Builder(this@LeicaActivity).setTitle("处理流程").setSingleChoiceItems(PhotoWorkflow.entries.map{it.label}.toTypedArray(),workflow.ordinal){dialog,index->
                transition();workflow=PhotoWorkflow.entries[index];workflow.processing?.let{flow.selectProcessingMode(it)};stageView.enableSubject(flow.blur && flow.subjectReady);compare.focusPicking=false;dialog.dismiss();refresh()
            }.setNegativeButton("返回",null).show()
        }}
        outputButton=button(outputSize.label).apply{setOnClickListener{
            if(!flow.busy && workflow.allowsOutputSelection)AlertDialog.Builder(this@LeicaActivity).setTitle("出图尺寸").setSingleChoiceItems(BokehOutput.entries.map{it.label}.toTypedArray(),outputSize.ordinal){dialog,index->
                outputSize=BokehOutput.entries[index];getPreferences(MODE_PRIVATE).edit().putInt("bokeh-output",index).apply();flow.outputChanged();dialog.dismiss();refresh();requestFocusPreview()
            }.setNegativeButton("返回",null).show()
        }}
        workflowPanel.block(row(processingButton,outputButton))
        compositionButton=button("智能构图").apply{setOnClickListener{openComposition()}}
        workflowPanel.block(compositionButton);controls.addView(workflowPanel)
        colorControls=section("调色风格")
        styleButtons=LeicaStyle.entries.map{style->button(style.label).apply{
            setOnClickListener{
                if(!flow.busy && !composing && workflow.allowsColorSelection && flow.style!=style){
                    flow.selectStyle(style)
                    getPreferences(MODE_PRIVATE).edit().putString("color-style",style.id).apply()
                    status.text=if(flow.hasResult)"已选择${style.label} · 点击「重新显影」应用；上次成片保留，保存不会偷偷重跑。" else "已选择${style.label} · 点击显影后应用。"
                    refresh()
                }
            }
        }}
        styleButtons.chunked(2).forEachIndexed{i,buttons->colorControls.block(row(*buttons.toTypedArray()),if(i==0)10 else 6)}
        styleHint=label("选择风格后点击显影；不会自动处理或保存。",11f)
        colorControls.block(styleHint,6);controls.block(colorControls,8)
        depthControls=section("景深")
        apertureDial=ApertureDial(this);depthControls.addView(apertureDial,LinearLayout.LayoutParams(MATCH_PARENT,dp(102)))
        val strip=LinearLayout(this)
        apertureButtons=LegendaryMath.apertures.map{value->button("$value").apply{contentDescription="光圈$value，控制景深与焦外虚化";setOnClickListener{if(flow.canAdjustOptics && flow.blur){flow.aperture(value);refresh();requestFocusPreview()}}}}
        apertureButtons.forEachIndexed{i,b->strip.addView(b,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{if(i>0)marginStart=dp(5)})}
        depthControls.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(strip)})
        focusButton=button("点选焦点").apply{setOnClickListener{if(!flow.busy && flow.hasPhoto){flow.chooseFocus();viewer=Viewer.ORIGINAL;applyViewer();status.text=if(flow.picking)"轻点目标内部。双指可放大，单指可平移。" else "已退出选焦。";refresh()}}}
        regionButton=button("景深提示").apply{setOnClickListener{regionVisible=!regionVisible;refresh()}}
        depthControls.block(row(focusButton,regionButton))
        subjectButton=button(UiText.SUBJECT_LABEL).apply{contentDescription="主体保护开关；焦点仍只有一个，保护点可以多个，选好后统一计算";setOnClickListener{
            if(!flow.busy){flow.toggleSubject();subjectWork.invalidate()
                if(!flow.subjectEnabled)stageView.enableSubject(false)
                else if(!flow.subjectReady && flow.hasPhoto && flow.blur){flow.chooseSubjects();viewer=Viewer.ORIGINAL;applyViewer()}
                status.text=if(flow.subjectEnabled){if(flow.subjectReady)"主体保护已开启 · 已有结果可用。" else "轻点要保护的主体，选好后点「统一计算」。"} else "主体保护已关闭 · 已有选点保留。"
                refresh();if(!flow.subjectPicking && flow.subjectReady)requestFocusPreview()
            }
        }}
        depthControls.block(subjectButton)
        subjectSelectButton=button("选择保护点").apply{setOnClickListener{
            if(!flow.busy && flow.hasPhoto && flow.subjectEnabled){flow.chooseSubjects();viewer=Viewer.ORIGINAL;applyViewer();status.text=if(flow.subjectPicking)"连续轻点多个目标；蓝色数字为保护点，金色十字为唯一焦点。选点不触发推理，选好后统一计算。" else "已退出保护点选择；已有选择保留。";refresh();if(!flow.subjectPicking && flow.subjectReady)requestFocusPreview()}
        }}
        subjectComputeButton=button("统一计算",true).apply{setOnClickListener{if(flow.canComputeSubjects)requestFocusPreview(computeSubject=true)}}
        subjectUndoButton=button("移除指定点").apply{setOnClickListener{showSubjectRemoval()}}
        subjectClearButton=button("清空保护点").apply{setOnClickListener{if(!flow.busy && flow.subjectEnabled){flow.clearSubjectPoints();worker.execute{engine.clearSubjectCache()};subjectSelectionChanged()}}}
        subjectControls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;block(row(subjectSelectButton,subjectComputeButton));block(row(subjectUndoButton,subjectClearButton))}
        subjectResolutionButton=button("保护分辨率 · 长边 2048").apply{setOnClickListener{
            if(!flow.busy)AlertDialog.Builder(this@LeicaActivity).setTitle("主体保护分辨率")
                .setSingleChoiceItems(SubjectResolution.entries.map{it.label}.toTypedArray(),flow.subjectResolution.ordinal){dialog,index->
                    val value=SubjectResolution.entries[index]
                    if(value!=flow.subjectResolution){
                        flow.selectSubjectResolution(value)
                        getPreferences(MODE_PRIVATE).edit().putInt("subject-resolution",value.longEdge).apply()
                        worker.execute{engine.clearSubjectCache()};subjectSelectionChanged()
                        status.text="保护分辨率：${value.longEdge}长边。已保留选点，请按新分辨率计算；不改变成片尺寸。"
                    };dialog.dismiss()
                }.setNegativeButton("返回",null).show()
        }}
        subjectControls.block(subjectResolutionButton)
        depthControls.block(subjectControls)
        focusLegend=label("绿：清晰区 · 金：过渡区",11f);depthControls.block(focusLegend)
        controls.block(depthControls,8)
        val log=section("处理记录");logPanel=log
        status=label("导入照片，选择成像方式。深度图可单独查看，不会自动选焦。",12f).apply{setTextIsSelectable(true);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};log.block(status);controls.block(log,8)
        compositionPanel=section("智能构图 · 当前编辑")
        compositionInfo=label("选择方案 → 应用。不会重新调色或虚化，保存仍使用下方按钮。",12f).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};compositionPanel.block(compositionInfo)
        compositionChoices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};compositionPanel.block(compositionChoices)
        compositionApply=button("应用",true).apply{setOnClickListener{applyComposition()}}
        compositionRetry=button("重新分析").apply{setOnClickListener{analyseComposition()}}
        compositionPanel.block(row(compositionApply,button("返回编辑").apply{setOnClickListener{leaveComposition()}}))
        compositionPanel.block(compositionRetry);controls.addView(compositionPanel)
        compositionBack=object:OnBackPressedCallback(false){override fun handleOnBackPressed(){leaveComposition()}}
        onBackPressedDispatcher.addCallback(this,compositionBack)
        editor=EditorLayout(this,photoPane,scroll);root.addView(editor,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        actions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(6),0,dp(4))}
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=7;progressTintList=android.content.res.ColorStateList.valueOf(0xffc4b8a6.toInt())};actions.addView(progress,LinearLayout.LayoutParams(MATCH_PARENT,dp(2)))
        progressLabel=label("导入照片后开始",11f,0xffd6cdbd.toInt()).apply{minLines=2;maxLines=2}
        elapsedLabel=label("",10f).apply{minLines=1;maxLines=1}
        pickButton=button("导入").apply{setOnClickListener{showPicker()}}
        runButton=button("显影",true).apply{contentDescription="按当前参数生成成片";setOnClickListener{generate(if(HdrDisplayPolicy.splitActions(sourceHdr,workflow.onlyComposition))HdrDevelopMode.HDR else developMode)}}
        sdrRunButton=button("SDR显影",true).apply{setOnClickListener{generate(HdrDevelopMode.SDR)}}
        saveButton=button("导出").apply{setOnClickListener{showExport()}}
        developActions=row(pickButton,runButton,sdrRunButton,saveButton)
        actions.block(developActions,6)
        resumeButton=button("恢复上次处理").apply{visibility=View.GONE;setOnClickListener{offerRecovery()}}
        actions.block(resumeButton,6)
        cancelButton=button("取消处理").apply{setOnClickListener{if(cancel!=null && !flow.cancelling){previewUpdates.discard();flow.cancel();cancel?.cancelled=true;engine.cancelRun();gaic.cancelRun();developView.skipPresentation();stageView.endLoading();compare.visibility=View.VISIBLE;progressMessage="正在取消 · 等待当前步骤退出";refresh()}}};
        val actionStatus=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48)}
        actionStatus.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(progressLabel);addView(elapsedLabel)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        actionStatus.addView(cancelButton,LinearLayout.LayoutParams(dp(96),WRAP_CONTENT).apply{marginStart=dp(6)})
        actions.addView(actionStatus,1,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));root.addView(actions)
        compare.onFocusPoint={point->if(!flow.busy){flow.picked(point);subjectWork.invalidate();compare.subjectOverlay=null;stageView.clearSubject();worker.execute{engine.clearSubjectCache()};refresh();requestFocusPreview()}}
        compare.onSubjectPoint={point->if(!flow.busy && flow.subjectPicking && flow.addSubjectPoint(point))subjectSelectionChanged()}
        compare.onSubjectMoved={old,point->if(!flow.busy && flow.subjectEnabled && flow.moveSubjectPoint(old,point))subjectSelectionChanged()}
        diagnostics=LiveDiagnostics(applicationContext,window,{workerThread},{if(flow.busy)progressMessage else status.text.toString()+current?.let{"\n\n上次显影\n${it.reason}\n${it.hardwareSummary}\n${it.stageSummary}"}.orEmpty()},{lastFailure},{diagnosticsPanel?.expanded==true})
        if(MonitorPreferences.editor(this) && ExternalPermissions.notifications(this))startEditorMonitor()
        ViewCompat.setOnApplyWindowInsetsListener(root){v,i->val b=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());v.setPadding(dp(14)+b.left,dp(8)+b.top,dp(14)+b.right,dp(8)+b.bottom);i}
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
                status.text="完整深度已缓存。黑白：白近黑远；伪彩：暖近冷远。空间视图可拖动旋转。"
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
        val split=HdrDisplayPolicy.splitActions(sourceHdr,workflow.onlyComposition)
        sdrRunButton.visibility=if(split)View.VISIBLE else View.GONE
        val visible=if(split)listOf(pickButton,runButton,sdrRunButton,saveButton)else listOf(pickButton,runButton,saveButton)
        val weights=HdrDisplayPolicy.actionWeights(split)
        visible.forEachIndexed{i,b->
            val p=b.layoutParams as LinearLayout.LayoutParams
            val margin=if(i==0)0 else dp(6)
            if(p.weight!=weights[i] || p.marginStart!=margin){p.width=0;p.weight=weights[i];p.marginStart=margin;b.layoutParams=p}
            b.setPadding(dp(if(split && (b===pickButton || b===saveButton))4 else 8),dp(9),dp(if(split && (b===pickButton || b===saveButton))4 else 8),dp(9))
        }
    }
    private fun spaceToolsVisible()=EditorLayoutPolicy.showSpaceTools(viewer==Viewer.RELIEF,visual!=null,flow.parametersExpanded,composing,flow.busy,stageView.isLoading)
    private fun showSpaceFullscreen(){
        if(spaceFullscreen!=null || isDestroyed || isFinishing || !spaceToolsVisible() || stageView.visibility!=View.VISIBLE)return
        spaceFullscreen=SpaceFullscreen(this,stageView){dialog->if(spaceFullscreen===dialog)spaceFullscreen=null}.also{it.show()}
    }
    private fun updateViewerLabels(){
        spaceTools.visibility=if(spaceToolsVisible())View.VISIBLE else View.GONE
        modeButtons.forEachIndexed{i,b->b.isSelected=Viewer.entries[i]==viewer}
        previewTag.text=when{
            composing->"智能构图 · 当前画面 · 选择后应用，不自动保存"
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
        updateHdrWindow()
    }
    private fun refresh(){
        ExternalModeRuntime.editorBusy=flow.busy
        val chrome=listOf<Any>(workflow,composing,flow.subjectEnabled,sourceHdr,interruptedKind.isNotEmpty(),flow.parametersExpanded)
        if(lastChrome!=null && lastChrome!=chrome)transition()
        lastChrome=chrome
        updateDevelopActions()
        pickButton.isEnabled=!flow.busy;runButton.isEnabled=workflow.canStart(flow);sdrRunButton.isEnabled=runButton.isEnabled;saveButton.isEnabled=!flow.busy && flow.hasResult && !composing
        compositionButton.isEnabled=!flow.busy && flow.hasPhoto
        processingButton.isEnabled=!flow.busy;processingButton.text=workflow.label
        colorControls.visibility=if(workflow.allowsColorSelection && !composing)View.VISIBLE else View.GONE
        styleButtons.forEachIndexed{i,b->
            val style=LeicaStyle.entries[i];b.isSelected=flow.style==style;b.isEnabled=!flow.busy && !composing
            b.contentDescription="${style.label} · ${if(b.isSelected)"已选择" else "未选择"} · 选择后点击显影应用"
        }
        styleHint.text=if(flow.resultStale && flow.hasResult)"已选${flow.style.label} · 重新显影后应用" else "已选${flow.style.label} · 点击显影"
        compositionButton.visibility=if(workflow.onlyComposition)View.GONE else View.VISIBLE
        compositionButton.text="智能构图"
        outputButton.visibility=if(workflow.allowsOutputSelection)View.VISIBLE else View.GONE
        outputButton.isEnabled=!flow.busy && workflow.allowsOutputSelection;outputButton.text=outputSize.label
        if(editor.expanded!=flow.parametersExpanded){editor.expanded=flow.parametersExpanded;adaptChrome()}
        parametersButton.text=if(flow.parametersExpanded)"收起" else "参数"
        parametersButton.contentDescription=if(flow.parametersExpanded)"收起参数" else "展开参数"
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
        modeButtons.forEachIndexed{i,b->b.isEnabled=!flow.busy && flow.hasPhoto && (Viewer.entries[i] !in listOf(Viewer.RESULT,Viewer.COMPARE) || flow.hasResult)}
        emptyState.visibility=if(flow.hasPhoto)View.GONE else View.VISIBLE
        val actionLabel=when{workflow.onlyComposition || (HdrDisplayPolicy.reuseForComposition(workflow.composeAfter,current!=null,flow.resultStale,developMode,current?.hdrReady==true))->"选择构图";flow.operation==PhotoFlow.Operation.GENERATE->"显影中";flow.picking->"请点焦点";flow.hasPhoto && flow.blur && flow.focus==null->"先选焦点";flow.blur && flow.subjectEnabled && !flow.subjectReady->"先计算保护";flow.subjectPicking->"请结束选点";workflow.composeAfter->"显影并构图";flow.hasResult->"重新显影";else->"显影"}
        val split=HdrDisplayPolicy.splitActions(sourceHdr,workflow.onlyComposition)
        runButton.text=if(split){if(flow.operation==PhotoFlow.Operation.GENERATE && developMode==HdrDevelopMode.HDR)"HDR显影中"else "HDR显影"}else actionLabel
        sdrRunButton.text=if(flow.operation==PhotoFlow.Operation.GENERATE && developMode==HdrDevelopMode.SDR)"SDR显影中"else "SDR显影"
        runButton.isSelected=split && developMode==HdrDevelopMode.HDR;sdrRunButton.isSelected=developMode==HdrDevelopMode.SDR
        runButton.contentDescription=if(split)"HDR显影 · 保留HDR光能，失败不会转为SDR · $actionLabel"else actionLabel
        sdrRunButton.contentDescription="SDR显影 · 使用SDR基底，不计算HDR重建 · $actionLabel"
        saveButton.text=if(flow.resultStale)"导出上次" else "导出"
        saveButton.contentDescription="导出成片；向上展开格式菜单，当前${if(current?.hdrReady?:sourceHdr)ExportMenuChoices.ULTRA_HDR_LABEL else ExportMenuChoices.exportFormat(flow.exportFormat,ExportPreferences.badge(this)).label}"
        cancelButton.visibility=if(flow.operation in listOf(PhotoFlow.Operation.GENERATE,PhotoFlow.Operation.FOCUS_PREVIEW,PhotoFlow.Operation.DEPTH,PhotoFlow.Operation.COMPOSITION,PhotoFlow.Operation.SAVE))View.VISIBLE else View.INVISIBLE
        cancelButton.isEnabled=!flow.cancelling;cancelButton.text=if(flow.cancelling)"正在取消…" else "取消处理"
        progress.visibility=if(flow.busy)View.VISIBLE else View.INVISIBLE;progressLabel.visibility=View.VISIBLE
        elapsedLabel.visibility=if(completedGenerationMs!=null && !flow.busy)View.VISIBLE else View.INVISIBLE
        completedGenerationMs?.let{elapsedLabel.text="显影用时 ${ProcessingTime.seconds(it)}${if(flow.resultStale)" · 参数待应用" else ""}"}
        workflowPanel.visibility=if(composing)View.GONE else View.VISIBLE
        logPanel.visibility=if(composing)View.GONE else View.VISIBLE
        compositionPanel.visibility=if(composing)View.VISIBLE else View.GONE
        compositionBack.isEnabled=composing
        compositionApply.isEnabled=composing && !flow.busy && compositionItems.isNotEmpty()
        compositionRetry.isEnabled=composing && !flow.busy
        if(composing){pickButton.isEnabled=false;runButton.isEnabled=false;sdrRunButton.isEnabled=false;saveButton.isEnabled=false;modeButtons.forEach{it.isEnabled=false}}
        for(i in 0 until compositionChoices.childCount){val v=compositionChoices.getChildAt(i);v.isEnabled=!flow.busy;v.isSelected=i==compositionSelected;v.setBackgroundColor(if(i==compositionSelected)0xff423d36.toInt()else 0xff242526.toInt())}
        if(composing && compositionItems.isNotEmpty() && !flow.busy)showCompositionSize()
        updateViewerLabels();updateProgress();persistEditor()
    }
    private fun adaptChrome(){
        val cfg=resources.configuration;val compact=EditorLayoutPolicy.compactChrome(cfg.screenHeightDp,cfg.fontScale)
        apertureDial.layoutParams.height=dp((102*cfg.fontScale.coerceAtLeast(1f)).toInt())
        val target=if(compact && editor.expanded)controls else root
        if(actions.parent!==target){(actions.parent as? android.view.ViewGroup)?.removeView(actions);if(target===controls)controls.addView(actions,0,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))else root.addView(actions,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))};editor.requestLayout()
    }
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
        diagnosticsPanel=panel
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(panel)}
        diagnosticsDialog=AlertDialog.Builder(this).setTitle("实时监测").setView(scroll).setPositiveButton("关闭",null).create().also{dialog->
            dialog.setOnDismissListener{diagnostics.stop();diagnosticsPanel=null;diagnosticsDialog=null}
            dialog.show()
            // A fixed viewport makes expanding details and refreshing numbers independent of dialog placement.
            dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94f).toInt(),(resources.displayMetrics.heightPixels*.82f).toInt())
        }
        diagnostics.start(lifecycleScope,::publishDiagnostics)
    }
    private fun openMonitorSettings(){
        MonitorPreferences.saveEditor(this,true)
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
    }
    private fun startEditorMonitor(){
        if(runtimeMonitor!=null || ExternalModeRuntime.status.running || !ExternalPermissions.notifications(this))return
        runtimeMonitor=RuntimeMonitor(this,RuntimeMonitor.EDITOR_ID,"传奇一瞬 · 运行参数",LeicaActivity::class.java){if(flow.busy)EditorPresentation.stage(progressMessage)else "等待编辑"}.takeIf{it.start()}
    }
    private fun stopEditorMonitor(){runtimeMonitor?.stop();runtimeMonitor=null;diagnosticsPanel?.syncNotification()}
    private fun setEditorMonitor(on:Boolean){
        MonitorPreferences.saveEditor(this,on)
        if(on)startEditorMonitor() else stopEditorMonitor()
        diagnosticsPanel?.syncNotification()
    }
    private fun updateProgress(){if(::progressLabel.isInitialized)progressLabel.text=if(flow.busy)"${EditorPresentation.stage(progressMessage)} · ${ProcessingTime.seconds(SystemClock.elapsedRealtime()-phaseStarted)}"else when{!flow.hasPhoto->"导入 → 显影 → 导出";composing->"选择方案，再点应用";flow.resultStale->"参数已改 · 重新显影后导出";flow.hasResult->if(flow.saved)"成片已保存" else "成片已就绪 · 可导出";else->"选择参数，再点显影"}}
    private fun stage(message:String){if(!flow.busy || isDestroyed)return;val key=message.substringBefore(" · ");if(key!=phase){phase=key;phaseStarted=SystemClock.elapsedRealtime()};progressMessage=message;if(composing)compositionInfo.text=message;val n=message.substringBefore('/').toIntOrNull();progress.isIndeterminate=n==null;if(n!=null)progress.progress=n;updateProgress()}
    private fun operation(op:PhotoFlow.Operation,message:String,afterSuccess:()->Unit={},action:suspend()->Unit){
        if(flow.busy)return
        if(op==PhotoFlow.Operation.GENERATE || op==PhotoFlow.Operation.PREVIEW)completedGenerationMs=null
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
                if(!isDestroyed && !isFinishing){if(leaveCompositionWhenIdle){leaveCompositionWhenIdle=false;closeComposition()};if(!stageView.isLoading)applyViewer();refresh();if(op==PhotoFlow.Operation.FOCUS_PREVIEW)refreshCachedSubjects();if(again)startFocusPreview();if(success)afterSuccess()}
            }}
        }
    }
    private fun onPicked(uri:Uri){
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
        AlertDialog.Builder(this).setTitle("移除指定保护点（无需重新推理）")
            .setItems(points.mapIndexed{i,p->"${i+1}号点 · 横向${(p.x*100).toInt()}% / 纵向${(p.y*100).toInt()}%"}.toTypedArray()){_,index->
                if(!flow.busy && flow.subjectTicket()==ticket && flow.removeSubjectPoint(points[index]))subjectSelectionChanged()
            }.setNegativeButton("返回",null).show()
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
        composing=true;applyViewer();refresh();analyseComposition()
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
        compositionItems=emptyList();compositionChoices.removeAllViews();compositionSelected=0
        compositionPreview.beginAnalysis()
        operation(PhotoFlow.Operation.COMPOSITION,"分析当前画面 · GAIC纯模型评分"){
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
                        contentDescription=item.title;setOnClickListener{if(!flow.busy){compositionSelected=index;compositionPreview.selectProposal(index);refresh()}}
                    }
                    card.addView(CompositionThumbnail(this@LeicaActivity,display,item.crop),LinearLayout.LayoutParams(dp(80),dp(60)))
                    card.addView(label(item.title,13f),LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(10)})
                    compositionChoices.block(card,6)
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
        flow.showParameters(if(applied)false else parametersBeforeComposition)
        composing=false;compositionPreview.close();compositionPreview.visibility=View.GONE
        compositionChoices.removeAllViews();compositionItems=emptyList()
        compositionLoaded?.bitmap?.recycle();compositionLoaded=null;compositionLoadingBitmap?.recycle();compositionLoadingBitmap=null
        applyViewer();refresh()
    }
    private fun showExport(){
        if(flow.busy || !flow.hasResult || composing || exportMenu?.isShowing==true)return
        exportMenu=ExportUi.show(saveButton,flow.exportFormat,currentCropPlan().label(),selected={format->
            if(!flow.busy && !isDestroyed){flow.selectExportFormat(format);ExportPreferences.save(this,format);refresh()}
        },export={format->
            if(!flow.busy && !isDestroyed && !isFinishing){flow.selectExportFormat(format);ExportPreferences.save(this,format);save(keepHdr=false)}
        },sourceHdr=current?.hdrReady?:sourceHdr,hdrUnavailableReason=null,exportHdr={
            if(!flow.busy && !isDestroyed && !isFinishing)save(keepHdr=true)
        },badge=ExportPreferences.badge(this),badgeChanged={on->if(!isDestroyed){ExportPreferences.saveBadge(this,on);refresh()}})
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
            val location=withContext(dispatcher){
                if(keepHdr && hdrResult!=null)engine.saveHdr(hdrResult,plan.crop,plan.width to plan.height,token,badge){message->runOnUiThread{if(cancel===token && !isDestroyed)status.text=message}}
                else if(keepHdr)HdrPhotoExport.saveSource(applicationContext,uri,plan.crop,token,plan.width to plan.height,badge=badge)
                else CompositionFiles.saveSource(applicationContext,uri,plan.crop,token,plan.width to plan.height,format,badge)
            }
            flow.saved();status.text="已保存 ${plan.label()} · $label\n$location";BlurToast.show(this@LeicaActivity,"成片已保存")
        }
    }
    private fun editorState():Map<String,String> = flow.snapshot()+mapOf(
        "workflow" to workflow.name,"output" to outputSize.name,"crop" to "${crop.l},${crop.t},${crop.r},${crop.b}",
        "width" to sourceWidth.toString(),"height" to sourceHeight.toString(),"hdrSource" to sourceHdr.toString(),"hdrDevelop" to developMode.name,
        "viewer" to viewer.name,"status" to status.text.toString(),"region" to regionVisible.toString())
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
            .setMessage("只保留这一份最新进度。继续会复用已完成的步骤和保护点；中断中的步骤会重新执行。重新开始会清除本次中间计算，原片和已完成成片保留。")
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
    override fun onPostResume(){super.onPostResume();continueComposition()}
    // Lifecycle reaches RESUMED after onResume returns on Android 14+. Recheck after dispatch;
    // updateHdrWindow still rejects a queued callback if the Activity was paused/destroyed meanwhile.
    override fun onResume(){super.onResume();if(ExternalModeRuntime.status.running)startActivity(android.content.Intent(this,ExternalModeActivity::class.java))else {if(MonitorPreferences.editor(this))startEditorMonitor()};diagnosticsPanel?.syncNotification();window.decorView.post{if(!isDestroyed)updateHdrWindow()};if(::compositionPreview.isInitialized)compositionPreview.setActive(true);if(::developView.isInitialized)developView.setActive(true);if(::stageView.isInitialized)stageView.setActive(true);if(diagnosticsDialog?.isShowing==true)diagnostics.start(lifecycleScope,::publishDiagnostics)}
    override fun onPause(){if(!flow.busy)stopEditorMonitor();if(::editor.isInitialized)editor.settle();HdrPreview.configure(window,false);spaceFullscreen?.dismiss();spaceFullscreen=null;exportMenu?.dismiss();exportMenu=null;if(::compositionPreview.isInitialized)compositionPreview.setActive(false);if(::developView.isInitialized)developView.setActive(false);if(::stageView.isInitialized)stageView.setActive(false);if(::diagnostics.isInitialized)diagnostics.stop();super.onPause()}
    override fun onConfigurationChanged(config:Configuration){exportMenu?.dismiss();exportMenu=null;super.onConfigurationChanged(config);spaceFullscreen?.resize();if(::editor.isInitialized)adaptChrome()}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);engine.memory.trim(level)}
    override fun onLowMemory(){super.onLowMemory();engine.memory.trim(15)}
    override fun onDestroy(){
        spaceFullscreen?.dismiss();spaceFullscreen=null
        previewUpdates.discard();cancel?.cancelled=true;engine.cancelRun();gaic.cancelRun();compositionPreview.close();compositionLoaded?.bitmap?.recycle();compositionLoadingBitmap?.recycle();job?.cancel();ticker?.cancel();diagnostics.stop();diagnosticsDialog?.dismiss();runtimeMonitor?.stop();runtimeMonitor=null;developView.close();stageView.close();clearDepthViews();compare.closeSources();compare.original=null;compare.result=null
        persistEditor()
        worker.execute{try{engine.close()}finally{engine.clearCache();photoImports.close();ExternalModeRuntime.editorBusy=false}};worker.shutdown();super.onDestroy()
    }
}
