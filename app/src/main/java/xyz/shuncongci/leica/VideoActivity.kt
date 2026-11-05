package xyz.shuncongci.leica

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Video uses the same workspace chrome/morphs as photos; the task and original-file
 * ownership remain separate. No media-library queries or automatic exports. */
class VideoActivity:AppCompatActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val panels by lazy{ExternalPanels(this)}
    private var token:Cancel?=null
    private val gate=VideoTaskGate()
    private var source:File?=null
    private var inventory:VideoTracks.Inventory?=null
    private var selectedTrack:Int?=null
    private var selected=VideoColor.DEFAULT
    private var result:VideoEngine.Result?=null
    private var pendingExport:VideoEngine.Result?=null
    private val imported=HashSet<Uri>()
    private var busy=false
    private var cancelling=false
    private var closing=false
    private var exporting=false
    private var externalUi=false
    private var lastProgress=0L
    private var showingResult=false
    private var playingFile:File?=null
    private var prepared=false
    private var playbackGlyph:WorkspaceGlyph.Kind?=null
    private var seeking=false
    private var record="尚未导入视频。"
    private var lastError=""
    private lateinit var status:StudioButton
    private lateinit var preview:VideoView
    private lateinit var previewLabel:TextView
    private lateinit var emptyState:LinearLayout
    private lateinit var importButton:StudioButton
    private lateinit var renderButton:StudioButton
    private lateinit var exportButton:StudioButton
    private lateinit var titleButton:StudioButton
    private lateinit var parametersButton:StudioButton
    private lateinit var trackButton:StudioButton
    private lateinit var sourceButton:StudioButton
    private lateinit var resultButton:StudioButton
    private lateinit var playButton:StudioButton
    private lateinit var seek:SeekBar
    private lateinit var time:TextView
    private lateinit var editor:EditorLayout
    private lateinit var formatLabel:TextView
    private lateinit var progress:ProgressBar
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->externalUi=false;if(uri!=null && !busy)importVideo(uri)else update()}
    private val saver=registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")){uri->
        externalUi=false;val done=pendingExport;pendingExport=null
        if(uri!=null && done!=null && !busy)export(uri,done)else update()
    }
    private fun dp(v:Int)=(resources.displayMetrics.density*v+.5f).toInt()
    private fun surface(color:Int=0xff1c1d1f.toInt(),radius:Int=18)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Float=13f)=TextView(this).apply{text=value;textSize=size;setTextColor(0xffc4c0b8.toInt());setLineSpacing(dp(2).toFloat(),1f)}
    private fun button(value:String,action:()->Unit)=StudioButton(this).apply{text=value;setOnClickListener{action()}}
    private fun glyph(b:StudioButton,kind:WorkspaceGlyph.Kind,top:Boolean=false){
        if(b is VideoIconButton){b.setGlyph(kind);return}
        val g=WorkspaceGlyph(kind){b.currentTextColor}.apply{setBounds(0,0,dp(22),dp(22))}
        if(top)b.setCompoundDrawables(null,g,null,null)else b.setCompoundDrawables(g,null,null,null)
        b.compoundDrawablePadding=dp(5)
    }
    private fun icon(kind:WorkspaceGlyph.Kind,label:String,action:()->Unit)=VideoIconButton(this).apply{
        text="";setOnClickListener{action()}
        borderVisible=false;contentDescription=label;setPadding(dp(13),dp(12),dp(13),dp(12));glyph(this,kind)
    }
    private fun LinearLayout.block(v:View,margin:Int=10){addView(v,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(margin)})}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        selected=VideoColor.restore(state?.getString("video.color"))
        val cfg=resources.configuration
        val chrome=ReferenceWorkspacePolicy.chrome(cfg.screenHeightDp,cfg.fontScale)
        val compact=chrome.compact
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff151617.toInt());clipChildren=false;clipToPadding=false}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,dp(if(compact)6 else 10))}
        titleButton=button(selected.label){chooseColor()}.apply{borderVisible=false;quietSelection=true;textSize=21f;letterSpacing=.02f;maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(dp(4),dp(4),dp(4),dp(4));typeface=Typeface.create("serif",Typeface.NORMAL)}
        titleButton.textSize=if(compact)18f else 21f
        header.addView(titleButton,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        val monitor=icon(WorkspaceGlyph.Kind.MONITOR,"视频处理记录与错误详情"){}
        monitor.setOnClickListener{showRecord(monitor)}
        header.addView(monitor,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(6)})
        parametersButton=icon(WorkspaceGlyph.Kind.PARAMETERS,"展开视频参数"){setParameters(!editor.expanded)}
        header.addView(parametersButton,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(6)})
        val more=icon(WorkspaceGlyph.Kind.MORE,"更多与返回照片工作台"){}
        more.setOnClickListener{panels.options(more,"视频模式",listOf("操作说明","处理记录与错误详情","返回照片工作台")){i->when(i){0->showHelp(more);1->showRecord(more);else->leave(more)}}}
        header.addView(more,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=dp(6)});root.addView(header)
        val pane=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val stage=FrameLayout(this).apply{background=surface(0xff0d0e0f.toInt());clipToOutline=true}
        preview=VideoView(this);stage.addView(preview,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT,Gravity.CENTER))
        emptyState=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(22),dp(16),dp(22),dp(16));isClickable=true;isFocusable=true;contentDescription="选择视频";setOnClickListener{showImport(importButton)}
            addView(text("选择视频",22f).apply{setTextColor(0xffc4b8a6.toInt());gravity=Gravity.CENTER})
            addView(text("主动导入 · 原尺寸逐帧调色",12f).apply{gravity=Gravity.CENTER;setPadding(0,dp(8),0,0)})
        };stage.addView(emptyState,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        previewLabel=text("视频模式",11f).apply{maxLines=2;setPadding(dp(10),dp(6),dp(10),dp(6));background=surface(0xb3151617.toInt(),10)}
        stage.addView(previewLabel,FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT,Gravity.TOP or Gravity.START).apply{topMargin=dp(12);marginStart=dp(12)})
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{isIndeterminate=true;indeterminateTintList=ColorStateList.valueOf(0xffc4b8a6.toInt());visibility=View.GONE}
        stage.addView(progress,FrameLayout.LayoutParams(MATCH_PARENT,dp(3),Gravity.BOTTOM).apply{marginStart=dp(18);marginEnd=dp(18)})
        pane.addView(stage,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        val playback=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        playButton=icon(WorkspaceGlyph.Kind.PLAY,"播放视频预览"){if(prepared && !busy && !externalUi){if(preview.isPlaying)preview.pause()else preview.start();syncPlayer()}}
        playback.addView(playButton,LinearLayout.LayoutParams(dp(64),dp(48)))
        seek=SeekBar(this).apply{max=1000;progressTintList=ColorStateList.valueOf(0xffc4b8a6.toInt());thumbTintList=ColorStateList.valueOf(0xffc4b8a6.toInt());contentDescription="视频预览位置"}
        playback.addView(seek,LinearLayout.LayoutParams(0,dp(48),1f))
        time=text("00:00 / 00:00",11f).apply{maxLines=1};playback.addView(time,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));pane.addView(playback)
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(v:SeekBar){seeking=true}
            override fun onStopTrackingTouch(v:SeekBar){if(prepared && !busy)preview.seekTo((preview.duration.toLong()*v.progress/1000).toInt());seeking=false}
            override fun onProgressChanged(v:SeekBar,p:Int,user:Boolean){}
        })
        val tabs=LinearLayout(this)
        sourceButton=button("原片"){showPreview(false)}.apply{quietSelection=true;cornerRadiusDp=14f;glyph(this,WorkspaceGlyph.Kind.IMAGE,true)}
        resultButton=button("成片"){showPreview(true)}.apply{quietSelection=true;cornerRadiusDp=14f;glyph(this,WorkspaceGlyph.Kind.RESULT,true)}
        tabs.addView(sourceButton,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));tabs.addView(resultButton,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(6)})
        if(UiPolishPolicy.tabsIconOnly(cfg.screenHeightDp,cfg.fontScale)){
            listOf(sourceButton,resultButton).forEachIndexed{i,b->b.text="";b.contentDescription=if(i==0)"查看原片预览"else "查看已有完整成片";b.setPadding(dp(8),dp(8),dp(8),dp(8))}
        }
        if(compact){
            time.visibility=View.GONE
            playback.addView(tabs,0,LinearLayout.LayoutParams(dp(104),dp(48)))
            playButton.layoutParams.width=dp(54)
        }else pane.block(tabs,2)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),0,dp(14),dp(14))}
        controls.block(text("处理流程",15f));controls.block(button("视频模式 · 返回照片工作台"){}.apply{setOnClickListener{leave(this)}})
        controls.block(text("视频画面轨道",15f));trackButton=button("导入后识别"){chooseTrack(trackButton)};controls.block(trackButton)
        formatLabel=text("尚未导入。只读取你选择的文件，不扫描媒体库。",12f);formatLabel.setTextIsSelectable(true);controls.block(formatLabel)
        controls.block(text("输出规格",15f));controls.block(text("杜比视界profile 8的HDR10/PQ或HLG兼容基础层可调色输出普通HEVC，不含杜比视界；profile 5、增强层或未知结构不强行导出。最多8线程同帧调色；保留所选画面原尺寸、帧时序和原生位深。调色需要重编码，不是码流无损；HDR设备不支持时停止，不转SDR。",13f))
        controls.block(text("风格从左上角选择。切换只影响下次调色，不改变已有成片。",12f));controls.block(button("操作说明"){}.apply{setOnClickListener{showHelp(this)}})
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=surface();clipToOutline=true}
        val heading=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(2),dp(4),dp(2))}
        heading.addView(text("视频参数",15f),LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));heading.addView(icon(WorkspaceGlyph.Kind.CHEVRON_DOWN,"收起视频参数"){setParameters(false)},LinearLayout.LayoutParams(dp(48),dp(48)));shell.addView(heading)
        shell.addView(View(this).apply{setBackgroundColor(0xff303233.toInt())},LinearLayout.LayoutParams(MATCH_PARENT,dp(1)))
        shell.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(controls)},LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        editor=EditorLayout(this,pane,shell).apply{anchor=parametersButton;expanded=false}
        val landscape=compact && cfg.screenWidthDp>=600
        val work=LinearLayout(this).apply{orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL}
        val dockArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL}
        root.addView(work,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        work.addView(editor,if(landscape)LinearLayout.LayoutParams(0,MATCH_PARENT,1f)else LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        work.addView(dockArea,if(landscape)LinearLayout.LayoutParams(dp(236),MATCH_PARENT).apply{marginStart=dp(10)}else LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        status=button("选择视频后开始调色 · 记录 ›"){showRecord(status)}.apply{gravity=Gravity.START or Gravity.CENTER_VERTICAL;maxLines=2;ellipsize=TextUtils.TruncateAt.END;textSize=12f;contentDescription="视频状态与处理记录"}
        dockArea.block(status,if(compact)2 else 8)
        importButton=icon(WorkspaceGlyph.Kind.FILE,"导入视频"){showImport(importButton)}
        exportButton=icon(WorkspaceGlyph.Kind.EXPORT,"导出已有完整成片"){showExport()}
        renderButton=button("调色"){if(busy)cancelTask()else if(selectedTrack==null && (inventory?.processingVideos?.size?:0)>1)chooseTrack(renderButton)else process()}.apply{borderVisible=false;cornerRadiusDp=25f;textSize=14f;maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        val capsule=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5));background=surface(0xff1c1d1f.toInt(),32).apply{setStroke(dp(1),0xff373839.toInt())};addView(renderButton,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))}
        fun edge(b:StudioButton,label:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;addView(b,LinearLayout.LayoutParams(dp(54),dp(54)));addView(text(label,10f).apply{gravity=Gravity.CENTER;visibility=if(compact)View.GONE else View.VISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO})}
        val actions=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(2));addView(edge(importButton,"导入"),LinearLayout.LayoutParams(dp(60),WRAP_CONTENT));addView(capsule,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(14);marginEnd=dp(14)});addView(edge(exportButton,"导出"),LinearLayout.LayoutParams(dp(60),WRAP_CONTENT))};dockArea.addView(actions)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,i->val b=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());v.setPadding(dp(14)+b.left,dp(8)+b.top,dp(14)+b.right,dp(8)+b.bottom);i}
        preview.setOnPreparedListener{player->
            if(isDestroyed || isFinishing)return@setOnPreparedListener
            prepared=true
            // Preparing alone may leave a paused VideoView black. Request the frame at
            // the start explicitly, without briefly starting playback (or its audio).
            // API 26+ closest-frame seeking also avoids choosing a later sync frame.
            try{player.seekTo(0L,MediaPlayer.SEEK_CLOSEST)}catch(e:RuntimeException){
                record+="\n首帧定位失败：${e.javaClass.simpleName}（仅影响预览，不影响调色和完整成片导出）。"
                setStatus("首帧暂未显示；可尝试播放，完整成片仍可导出 · 记录 ›")
            }
            syncPlayer()
        }
        preview.setOnCompletionListener{syncPlayer()}
        preview.setOnErrorListener{_,what,extra->prepared=false;setStatus("预览不可播放；完整成片仍可导出 · 记录 ›");record+="\n预览播放器错误 $what/$extra（不等于处理文件损坏）。";syncPlayer();true}
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(editor.expanded)setParameters(false)else leave(importButton)}})
        renderButton.addOnLayoutChangeListener{_,l,_,r,_,ol,_,or,_->if(r-l!=or-ol)renderButton.post{if(!isDestroyed)update()}}
        setContentView(root);update()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("video.color",selected.name)
        super.onSaveInstanceState(outState)
    }
    private fun setParameters(value:Boolean){editor.expanded=value;parametersButton.isSelected=value;parametersButton.contentDescription=if(value)"收起视频参数"else "展开视频参数"}
    private fun chooseColor(){if(busy || externalUi)return;panels.options(titleButton,"调色风格",VideoColor.entries.map{it.label},selected.ordinal){i->if(!busy){selected=VideoColor.entries[i];setStatus(if(inventory?.processingBlockReason!=null)"已选择${selected.label}；当前杜比输入不能安全转HEVC"else "已选择${selected.label}；点击调色生成新成片");update()}}}
    private fun showImport(anchor:View){if(busy || externalUi)return;panels.actions(anchor,"导入视频","只复制本次选择的文件到私有缓存，不扫描媒体库、不修改原视频。导入后先核对画面轨道。",listOf(ExternalPanels.Action("选择视频文件"){if(!busy){externalUi=true;update();picker.launch(arrayOf("video/*"))}}))}
    private fun showHelp(anchor:View){panels.message(anchor,"视频模式使用说明","导入视频 → 确认画面轨道 → 左上角选择徕卡一瞬或徕卡N瞬 → 调色 → 查看成片 → 导出。Android 10及以上默认保存到相册 Movies/传奇一瞬，不用选择位置；只有“另存为文件”需要选位置。\n\n多条独立视频画面需要明确选择，导出只包含所选画面与音轨，不合并其他画面。杜比profile 8兼容HDR基础层可转普通HEVC，不保留杜比动态元数据和杜比显示效果；profile 5、增强层或相互依赖轨道不能强制改标签导出。\n\nHDR需要Android 13及兼容的10bit P010编解码器；不支持时停止，不降位深或转SDR。调色会重编码。\n\n可选元数据失败会记录具体问题，完整成片仍可导出；编解码、权限、回读失败不会假报成功。\n\n离开页面会停止处理，中断后需重做；私有缓存不是长期备份。原片容器多轨预览由系统选择，未必是本次调色轨。元数据可能包含位置，分享前请确认。")}
    private fun showRecord(anchor:View){panels.message(anchor,"处理记录与错误详情",status.text.toString()+"\n\n"+record+if(lastError.isBlank())""else "\n\n最近一次错误（后续成功不会清除）\n$lastError")}
    private fun setStatus(value:String){status.text=value;status.contentDescription="$value，点击查看完整处理记录"}
    private fun chooseTrack(anchor:View){
        if(busy || externalUi)return;val info=inventory?:return;val file=source?:return
        info.processingBlockReason?.let{panels.message(anchor,"杜比视界处理说明",it);return}
        if(info.videos.isEmpty()){panels.message(anchor,"未识别到视频画面",info.summary);return}
        panels.options(anchor,"选择视频画面轨道",info.processingVideos.map{it.label},info.processingVideos.indexOfFirst{it.index==selectedTrack}){i->
            if(busy || source!==file)return@options
            try{val choice=info.choose(info.processingVideos[i].index)
                fun accept(){if(!busy && source===file){selectedTrack=choice.index;setStatus("已选择轨道 ${choice.index+1}；点击调色开始");update()}}
                if(info.processingVideos.size>1)panels.actions(anchor,"仅处理所选画面","${choice.label}\n\n本次成片只导出这条独立视频画面和原音轨，不合并其他视频轨。其他轨道仍保留在原文件中；原文件不会修改。",listOf(ExternalPanels.Action("使用这条画面",::accept)))else accept()
            }catch(e:Exception){showFailure(e);showRecord(anchor)}
        }
    }
    private fun showPreview(done:Boolean){
        if(busy || externalUi)return;val file=if(done)result?.file else source;file?:return
        showingResult=done;previewLabel.text=if(done)"${result!!.color.label} · 成片${result!!.source.dolby?.let{" · ${it.outputLabel}"}.orEmpty()}"else if((inventory?.videos?.size?:0)>1)"原片容器预览 · 系统默认轨"else "原片 · 视频"
        if(playingFile!=file){preview.stopPlayback();prepared=false;playingFile=file;preview.setVideoPath(file.absolutePath)}
        emptyState.visibility=View.GONE;update()
    }
    private fun syncPlayer(){
        if(!::playButton.isInitialized)return
        playButton.isEnabled=prepared && !busy && !externalUi;seek.isEnabled=playButton.isEnabled
        val playing=prepared && preview.isPlaying
        val kind=if(playing)WorkspaceGlyph.Kind.PAUSE else WorkspaceGlyph.Kind.PLAY
        if(playbackGlyph!=kind){glyph(playButton,kind);playButton.compoundDrawablePadding=0;playbackGlyph=kind}
        playButton.contentDescription=if(playing)"暂停视频预览"else "播放视频预览"
        if(prepared){val duration=preview.duration.coerceAtLeast(0);val pos=preview.currentPosition.coerceAtLeast(0);if(!seeking)seek.progress=if(duration>0)(pos.toLong()*1000/duration).toInt()else 0
            fun clock(ms:Int)="%02d:%02d".format(ms/60000,ms/1000%60)
            time.text="${clock(pos)} / ${clock(duration)}";seek.contentDescription="视频预览位置，${time.text}"}
    }
    private val playerTick=object:Runnable{override fun run(){if(!isDestroyed){syncPlayer();handler.postDelayed(this,300)}}}
    private fun update(){
        if(!::importButton.isInitialized)return
        val idle=!busy && !externalUi;importButton.isEnabled=idle;titleButton.isEnabled=idle;trackButton.isEnabled=idle && inventory!=null
        val blocked=inventory?.processingBlockReason!=null
        renderButton.isEnabled=if(busy)!cancelling else idle && source!=null && !blocked
        val action=if(cancelling)"取消中…"else if(busy)"取消处理"else if(blocked)"当前输入不能安全转HEVC"else if(selectedTrack==null && (inventory?.processingVideos?.size?:0)>1)"选择画面"else "调色"
        val cfg=resources.configuration
        val dockWidth=if(renderButton.width>0)((renderButton.parent.parent as View).width/resources.displayMetrics.density).toInt()else cfg.screenWidthDp
        val dock=UiPolishPolicy.dock(dockWidth,cfg.fontScale,ReferenceWorkspacePolicy.chrome(cfg.screenHeightDp,cfg.fontScale).compact)
        renderButton.contentDescription=action;renderButton.text=if(dock.iconOnly)""else action
        if(dock.iconOnly)glyph(renderButton,if(busy)WorkspaceGlyph.Kind.STOP else WorkspaceGlyph.Kind.DEVELOP)else renderButton.setCompoundDrawables(null,null,null,null)
        renderButton.setPadding(dp(6),dp(6),dp(6),dp(6))
        ((renderButton.parent as View).layoutParams as LinearLayout.LayoutParams).also{it.marginStart=dp(dock.margin);it.marginEnd=dp(dock.margin);(renderButton.parent as View).layoutParams=it}
        exportButton.isEnabled=idle && result!=null;exportButton.contentDescription=result?.let{"导出已有${it.color.label}完整成片"}?:"尚无完整成片可导出"
        titleButton.text=selected.label;sourceButton.isEnabled=idle && source!=null;resultButton.isEnabled=idle && result!=null
        sourceButton.isSelected=!showingResult && source!=null;resultButton.isSelected=showingResult && result!=null
        trackButton.text=if(blocked)"杜比视界 · 查看说明"else inventory?.videos?.firstOrNull{it.index==selectedTrack}?.label?:if(source==null)"导入后识别"else "选择画面轨道"
        formatLabel.text=inventory?.summary?:"尚未导入。只读取你选择的文件，不扫描媒体库。"
        progress.visibility=if(busy)View.VISIBLE else View.GONE;syncPlayer()
    }
    private fun begin(message:String):Cancel {
        panels.dismiss();setParameters(false);preview.pause();busy=true;cancelling=false;lastProgress=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setStatus(message)
        return Cancel().also{token=it;gate.begin(it);update()}
    }
    private fun complete(c:Cancel,error:Exception?=null,published:Boolean=false,action:()->Unit={}){runOnUiThread{
        if(isDestroyed)return@runOnUiThread
        val end=gate.finish(c,!published && c.cancelled,error!=null)
        if(end==VideoTaskGate.End.STALE)return@runOnUiThread
        busy=false;cancelling=false;exporting=false;token=null;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when(end){
            VideoTaskGate.End.SUCCESS->action()
            VideoTaskGate.End.CANCELLED->showFailure(CancellationException())
            VideoTaskGate.End.FAILED->showFailure(requireNotNull(error))
            VideoTaskGate.End.STALE->Unit
        }
        update();if(closing)finish()
    }}
    private fun showFailure(e:Exception){
        if(e is CancellationException)setStatus("已取消；原片与上次完整成片保留")else{
            lastError=("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n"+e.stackTraceToString()).take(12000)
            setStatus("${e.message.orEmpty().ifBlank{e.javaClass.simpleName}} · 详情 ›")
        }
    }
    private fun failure(c:Cancel,e:Exception){complete(c,error=e)}
    private fun importVideo(uri:Uri){val c=begin("正在读取并识别视频轨道…");worker.execute{try{
        val dir=File(cacheDir,"video-${UUID.randomUUID()}");val file=VideoEngine.import(this,uri,dir,c);val info=VideoEngine.catalog(file,c);c.check()
        var auto:Int?=null;var issue:Exception?=null
        if(info.processingBlockReason==null && info.processingVideos.size<=1)try{auto=info.choose().index}catch(e:IllegalArgumentException){issue=e}
        complete(c){source=file;inventory=info;selectedTrack=auto;imported+=uri;record="本次导入\n${info.summary}\n原文件未修改。";showingResult=false
            if(info.processingBlockReason!=null)setStatus("杜比视界可按系统能力预览；当前输入不能安全转HEVC · 原因见记录")else if(issue!=null)showFailure(issue!!)else if(info.conversionNotice!=null)setStatus("杜比兼容基础层已识别；调色后输出普通HEVC，不含杜比视界")else setStatus(if(auto==null)"检测到 ${info.videos.size} 条视频轨，请先选择画面"else "视频已就绪；选择风格后点击调色")
            showPreview(false)
        }
    }catch(e:Exception){failure(c,e)}}}
    private fun process(){val file=source?:return;val look=selected;val track=selectedTrack;val c=begin("核对所选画面、精度与原始时序…");val notes=VideoMetadata();worker.execute{try{
        val s=VideoEngine.inspect(file,notes,c,track);val dir=File(file.parentFile,"render-${UUID.randomUUID()}");check(dir.mkdirs())
        val done=VideoEngine.process(this,s,look,dir,notes,c){message->val now=SystemClock.elapsedRealtime();if(now-lastProgress>250){lastProgress=now;runOnUiThread{if(!isDestroyed && token===c && !cancelling)setStatus(message)}}}
        complete(c){result=done;record="${s.w}×${s.h} · ${s.color.label} · ${done.frames}帧\n本次处理：${"%.1f".format(done.elapsedMs/1000.0)}秒\n原片未修改；音轨样本与时序已回读。\n"+notes.records.joinToString("\n\n");setStatus(notes.summary(false));showPreview(true)}
    }catch(e:Exception){runOnUiThread{if(!isDestroyed)record=notes.records.joinToString("\n\n").ifBlank{inventory?.summary.orEmpty()}};failure(c,e)}}}
    private fun cancelTask(){if(!busy || cancelling)return;cancelling=true;token?.cancelled=true;setStatus("正在取消，等待当前步骤安全结束…");update()}
    private fun leave(anchor:View){if(externalUi)return;if(!busy){finish();return};panels.actions(anchor,"停止当前任务？","当前任务需要重新处理；原片不会修改。已生成的缓存不是长期备份，请及时导出。",listOf(ExternalPanels.Action("停止并返回"){closing=true;cancelTask()}),"继续处理")}
    private fun showExport(){val done=result?:return;if(busy || externalUi)return
        val actions=ArrayList<ExternalPanels.Action>()
        if(Build.VERSION.SDK_INT>=29)actions+=ExternalPanels.Action("保存到相册"){if(!busy && !externalUi)exportGallery(done)}
        actions+=ExternalPanels.Action("另存为文件"){if(!busy && !externalUi){pendingExport=done;externalUi=true;update();saver.launch("${done.color.label}-${System.currentTimeMillis()}.mp4")}}
        panels.actions(exportButton,"导出完整成片","${done.color.label} · ${done.source.w}×${done.source.h} · ${done.frames}帧\n${done.source.dolby?.outputLabel?.plus("\n").orEmpty()}${done.metadata.summary(false)}\n\n${if(done.source.file!=source)"这份成片来自上一次导入，不是当前原片。\n\n"else ""}只导出这份已完成文件，不覆盖原片。${if(Build.VERSION.SDK_INT>=29)"默认保存到相册 Movies/传奇一瞬，不需要选择位置；另存为文件时才选择位置。"else "此系统使用另存为文件选择新位置。"}位置等拍摄信息可能保留。",actions)
    }
    private fun exportGallery(done:VideoEngine.Result){val c=begin("正在保存完整视频到相册…");exporting=true
        worker.execute{try{
            val path=VideoGalleryExport.save(this,done.file,c)
            complete(c,published=true){setStatus(done.metadata.summary(true));record+="\n已保存到相册：$path\n写入同步、SHA-256回读及发布状态核对通过。"}
        }catch(e:Exception){runOnUiThread{if(!isDestroyed && token===c && e.suppressed.isNotEmpty())record+="\n本次相册导出清理异常，请检查Movies/传奇一瞬；完整缓存保留。\n"+e.suppressed.joinToString("\n"){it.message.orEmpty()}};failure(c,e)}}
    }
    private fun hash(file:File,c:Cancel):ByteArray=file.inputStream().use{input->val h=MessageDigest.getInstance("SHA-256");val b=ByteArray(256*1024);while(true){c.check();val n=input.read(b);if(n<0)break;h.update(b,0,n)};h.digest()}
    private fun export(uri:Uri,done:VideoEngine.Result){if(uri in imported){setStatus("不能覆盖已导入的原视频，请选择新文件。");update();return};val c=begin("正在导出完整成片…");exporting=true
        worker.execute{try{
            val expected=hash(done.file,c)
            contentResolver.openFileDescriptor(uri,"w").use{fd->requireNotNull(fd){"无法创建导出文件"};FileOutputStream(fd.fileDescriptor).use{out->done.file.inputStream().use{input->val b=ByteArray(256*1024);while(true){c.check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}};out.fd.sync()}}
            val actual=contentResolver.openInputStream(uri).use{input->requireNotNull(input){"无法回读导出文件"};val h=MessageDigest.getInstance("SHA-256");val b=ByteArray(256*1024);while(true){c.check();val n=input.read(b);if(n<0)break;h.update(b,0,n)};h.digest()}
            check(expected.contentEquals(actual)){"导出回读与完整成片不一致"};c.check();complete(c,published=true){setStatus(done.metadata.summary(true));record+="\n本次导出已同步并通过SHA-256回读。"}
        }catch(e:Exception){try{if(!DocumentsContract.deleteDocument(contentResolver,uri))error("提供器未确认清理")}catch(cleanup:Exception){runOnUiThread{if(!isDestroyed)record+="\n未能移除本次导出的不完整文件：${cleanup.javaClass.simpleName}。请在所选位置检查；完整缓存仍保留。"}};failure(c,e)}}
    }
    override fun onResume(){super.onResume();handler.removeCallbacks(playerTick);handler.post(playerTick)}
    override fun onPause(){panels.dismiss();editor.settle();preview.pause();handler.removeCallbacks(playerTick);super.onPause()}
    override fun onStop(){super.onStop();if(busy && !exporting){cancelTask()}}
    override fun onDestroy(){token?.cancelled=true;gate.abandon();panels.dismiss();handler.removeCallbacks(playerTick);preview.stopPlayback();worker.shutdown();super.onDestroy()}
}
