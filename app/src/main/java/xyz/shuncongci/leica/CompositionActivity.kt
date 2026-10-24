package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.os.Bundle
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

/** Deliberately short flow: open, select an alternative, apply and save. */
class CompositionActivity:AppCompatActivity() {
    private val gaic=GaicComposition()
    private val worker=Executors.newSingleThreadExecutor{Thread(it,"composition-worker")}
    private var loaded:CompositionFiles.Loaded?=null
    private var loadingPreview:android.graphics.Bitmap?=null
    private var token:Cancel?=null
    @Volatile private var engine:LeicaEngine?=null
    private var busy=false;private var selected=0;private var prepared:LeicaEngine.Result?=null
    private val savedIndices=mutableSetOf<Int>()
    private var sourceW=1;private var sourceH=1;private var sourceHdr=false
    private var items=emptyList<CompositionAnalysis.Suggestion>()
    private lateinit var sourcePath:String;private lateinit var originalPath:String
    private lateinit var output:BokehOutput;private lateinit var appearance:CompositionAppearance
    private lateinit var preview:CompositionCropView;private lateinit var status:TextView;private lateinit var dimensions:TextView
    private lateinit var proposals:LinearLayout;private lateinit var apply:StudioButton;private lateinit var cancel:StudioButton
    private lateinit var retry:StudioButton;private lateinit var bar:ProgressBar
    private var exportMenu:ExportUi.Handle?=null;private var exportFormat=ExportFormat.DEFAULT
    private val cards=mutableListOf<View>()
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=14f)=TextView(this).apply{text=value;textSize=size;setTextColor(0xffddd5c8.toInt());setLineSpacing(dp(3).toFloat(),1f)}
    private fun LinearLayout.block(v:View){addView(v,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(10)})}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        sourcePath=intent.getStringExtra("source")?:"";originalPath=intent.getStringExtra("original")?:sourcePath
        output=BokehOutput.entries.getOrElse(intent.getIntExtra("output",BokehOutput.SIZE_2048.ordinal)){BokehOutput.SIZE_2048}
        val fx=intent.getFloatExtra("focusX",-1f);val fy=intent.getFloatExtra("focusY",-1f)
        val focus=if(fx in 0f..1f && fy in 0f..1f)LegendaryMath.Point(fx,fy)else null
        appearance=CompositionAppearance(intent.getBooleanExtra("color",false),intent.getBooleanExtra("blur",false),focus,intent.getFloatExtra("aperture",2.8f),LeicaStyle.fromId(intent.getStringExtra("colorStyle")))
        selected=state?.getInt("selected")?:0
        exportFormat=ExportPreferences.load(this)
        if(state?.getString("export-format")==exportFormat.id)state.getIntArray("savedIndices")?.let{savedIndices.addAll(it.toList())}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff151617.toInt())}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(text("智能构图",21f),LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        header.addView(StudioButton(this).apply{text="返回";setOnClickListener{leave()}});root.addView(header)
        preview=CompositionCropView(this)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),0,dp(4),dp(12))}
        dimensions=text("${intent.getStringExtra("label")?:"原图"} · ${output.label}");panel.block(dimensions)
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{isIndeterminate=true};panel.block(bar)
        status=text("正在分析画面…",12f).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};panel.block(status)
        panel.block(text("选择方案 → 应用并另存\n分块时可拖动视角，不改变裁切\nGAIC 纯模型构图，不混入人工规则评分",11f))
        proposals=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};panel.block(proposals)
        apply=StudioButton(this,true).apply{text="导出";setOnClickListener{showExport()}};panel.block(apply)
        cancel=StudioButton(this).apply{text="取消";setOnClickListener{cancelTask()}};panel.block(cancel)
        retry=StudioButton(this).apply{text="重试";setOnClickListener{analyse()}};panel.block(retry)
        root.addView(EditorLayout(this,preview,ScrollView(this).apply{addView(panel)}),LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        ViewCompat.setOnApplyWindowInsetsListener(root){v,i->val inset=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());v.setPadding(dp(12)+inset.left,dp(6)+inset.top,dp(12)+inset.right,dp(6)+inset.bottom);i}
        setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){leave()}})
        analyse()
    }
    private fun cancelTask(){token?.cancelled=true;gaic.cancelRun();engine?.cancelRun();cancel.isEnabled=false;status.text="正在取消…"}
    private fun <T> task(message:String,work:(Cancel)->T,discard:(T)->Unit={},committed:Boolean=false,done:(T)->Unit){
        if(busy)return
        val c=Cancel();token=c;busy=true;status.text=message;refresh()
        worker.execute{
            try {val value=work(c);runOnUiThread{
                if(isDestroyed || token!==c){discard(value);return@runOnUiThread}
                busy=false;token=null
                if(c.cancelled && !committed){discard(value);preview.restoreSelection();status.text="已取消，未发布半成品"}else done(value)
                refresh()
            }}catch(e:Exception){propagateFatalCause(e);runOnUiThread{
                if(isDestroyed || token!==c)return@runOnUiThread
                busy=false;token=null;preview.restoreSelection();status.text=if(e is CancellationException || c.cancelled)"已取消，已有成片不受影响"else "未完成：${e.message?:"请重试"}";refresh()
            }}
        }
    }
    private fun analyse(){
        if(busy)return
        data class Answer(val data:CompositionFiles.Loaded,val w:Int,val h:Int,val suggestions:List<CompositionAnalysis.Suggestion>,val hdr:Boolean,val rendered:LeicaEngine.Result?)
        preview.beginAnalysis()
        task("正在读取照片…",{c->
            val (w,h)=CompositionFiles.dimensions(applicationContext,CompositionFiles.source(applicationContext,originalPath))
            val data=CompositionFiles.load(applicationContext,CompositionFiles.source(applicationContext,sourcePath),c){decoded->
                // Independent UI ownership: failure/cancel may recycle the worker bitmap at any time.
                val display=checkNotNull(decoded.copy(android.graphics.Bitmap.Config.ARGB_8888,false))
                runOnUiThread{
                    if(isDestroyed || isFinishing || token!==c || c.cancelled){display.recycle();return@runOnUiThread}
                    val previous=loadingPreview;loadingPreview=display;preview.setPhoto(display,w,h);previous?.recycle()
                    status.text="正在进行模型构图评分 · 可拖动三维视角"
                }
            }
            try{
                val suggestions=gaic.analyse(applicationContext,data.image,w,h,output,c);c.check()
                val original=CompositionFiles.source(applicationContext,originalPath)
                val hdr=HdrAutoSource.detect(applicationContext,Uri.fromFile(original))
                val rendered=if(appearance.color || appearance.blur){
                    val plans=suggestions.map{CompositionOutput.plan(w,h,it.crop,output)}
                    LeicaEngine(applicationContext,resultDirectory=java.io.File(cacheDir,"composition-prepared")).use{e->
                        engine=e
                        try{e.process(Uri.fromFile(original),appearance.style,c,appearance.blur,appearance.focus,appearance.aperture,output,appearance.color,
                            plans.maxOf{it.workWidth} to plans.maxOf{it.workHeight}){message->runOnUiThread{if(token===c && !isDestroyed)status.text=message}}}finally{engine=null}
                    }
                }else null
                Answer(data,w,h,suggestions,rendered?.hdrReady?:hdr,rendered)
            }catch(e:Exception){data.bitmap.recycle();throw e}
        },{it.data.bitmap.recycle();disposePrepared(it.rendered)}){answer->
            disposePrepared(prepared);prepared=answer.rendered
            sourceHdr=answer.hdr;val previous=loaded;loaded=answer.data;sourceW=answer.w;sourceH=answer.h;items=answer.suggestions
            selected=selected.coerceIn(0,items.lastIndex)
            preview.setPhoto(answer.data.bitmap,sourceW,sourceH)
            loadingPreview?.recycle();loadingPreview=null;previous?.bitmap?.recycle()
            preview.showProposals(items.map{CompositionOutput.plan(sourceW,sourceH,it.crop,output).crop},selected)
            showSuggestions()
            status.text=if(items.size==1)"分析完成 · 没有足够改善依据，保留完整画面"else "分析完成 · 选一个喜欢的构图，再应用另存"
        }
    }
    private fun showSuggestions(){
        proposals.removeAllViews();cards.clear();val data=loaded?:return
        items.forEachIndexed{index,item->
            val card=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));isFocusable=true;isClickable=true;contentDescription=item.title
                setOnClickListener{if(!busy && selected!=index){selected=index;preview.selectProposal(index);status.text=if(index in savedIndices)"此方案已保存"else "已选：${item.title}";refresh()}}
            }
            card.addView(CompositionThumbnail(this,data.bitmap,item.crop),LinearLayout.LayoutParams(dp(86),dp(68)))
            card.addView(text(item.title),LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(12)})
            cards.add(card);proposals.block(card)
            if(ValueAnimator.areAnimatorsEnabled() && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){
                card.alpha=0f;card.translationY=dp(18).toFloat()
                card.animate().alpha(1f).translationY(0f).setStartDelay(index*65L).setDuration(380).start()
            }
        }
    }
    private fun showExport(){
        if(busy || loaded==null || exportMenu?.isShowing==true)return
        val plan=CompositionOutput.plan(sourceW,sourceH,items[selected].crop,output)
        fun choose(format:ExportFormat){if(!busy && !isDestroyed){if(format!=exportFormat)savedIndices.clear();exportFormat=format;ExportPreferences.save(this,format);refresh()}}
        exportMenu=ExportUi.show(apply,exportFormat,"${plan.width}×${plan.height}",selected={choose(it)},export={format->if(!busy && !isDestroyed && !isFinishing){choose(format);applySelected()}},
            sourceHdr=sourceHdr,hdrUnavailableReason=null,
            exportHdr={if(!busy && !isDestroyed && !isFinishing)applySelected(keepHdr=true)})
    }
    private fun applySelected(keepHdr:Boolean=sourceHdr){
        if(busy || loaded==null)return
        val index=selected;val plan=CompositionOutput.plan(sourceW,sourceH,items[index].crop,output);val format=exportFormat
        preview.beginApply()
        task("正在出图 · ${plan.width}×${plan.height}",{c->
            val original=CompositionFiles.source(applicationContext,originalPath)
            val r=prepared
            if(r!=null){
                if(keepHdr)try{LeicaEngine(applicationContext).use{it.saveHdr(r,plan.crop,plan.width to plan.height,c){message->runOnUiThread{if(token===c && !isDestroyed)status.text=message}}}}
                catch(t:HdrUnsupportedException){c.check();sourceHdr=false;CompositionFiles.save(applicationContext,r.file,plan.crop,c,plan.width to plan.height,format)}
                else CompositionFiles.save(applicationContext,r.file,plan.crop,c,plan.width to plan.height,format)
            }else{
                if(keepHdr){
                    try{HdrPhotoExport.saveSource(applicationContext,Uri.fromFile(original),plan.crop,c,plan.width to plan.height)}
                    catch(t:HdrUnsupportedException){c.check();sourceHdr=false;CompositionFiles.save(applicationContext,original,plan.crop,c,plan.width to plan.height,format)}
                }else CompositionFiles.save(applicationContext,original,plan.crop,c,plan.width to plan.height,format)
            }
        },committed=true){location->savedIndices.add(index);preview.saved();status.text="已另存 · ${plan.width}×${plan.height} · ${if(keepHdr && sourceHdr)"Ultra HDR JPEG"else if(keepHdr)"${format.label} · 已转 SDR"else format.label}\n$location";status.contentDescription="已保存到 $location";BlurToast.show(this,"构图已保存")}
    }
    private fun refresh(){
        bar.visibility=if(busy)View.VISIBLE else View.GONE;cancel.visibility=bar.visibility;cancel.isEnabled=token?.cancelled!=true
        retry.visibility=if(!busy && loaded==null)View.VISIBLE else View.GONE
        apply.isEnabled=!busy && loaded!=null;apply.text=if(busy && loaded!=null)"正在出图…"else "导出"
        cards.forEachIndexed{i,v->v.isEnabled=!busy;v.isSelected=i==selected;v.setBackgroundColor(if(i==selected)0xff54342e.toInt()else 0xff242526.toInt())}
        items.getOrNull(selected)?.let{item->
            val plan=CompositionOutput.plan(sourceW,sourceH,item.crop,output)
            dimensions.text="${intent.getStringExtra("label")?:"原图"} · ${plan.width}×${plan.height}"
        }
    }
    private fun leave(){cancelTask();finish()}
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putInt("selected",selected);out.putString("export-format",exportFormat.id);out.putIntArray("savedIndices",savedIndices.toIntArray())}
    override fun onResume(){super.onResume();if(::preview.isInitialized)preview.setActive(true)}
    override fun onPause(){
        exportMenu?.dismiss();exportMenu=null
        if(::preview.isInitialized)preview.setActive(false)
        cards.forEach{it.animate().cancel();it.alpha=1f;it.translationY=0f}
        super.onPause()
    }
    private fun disposePrepared(r:LeicaEngine.Result?){r?:return;r.original.recycle();r.result.recycle();r.file.delete();HdrResultStore.remove(r.file)}
    override fun onDestroy(){
        token?.cancelled=true;gaic.cancelRun();engine?.cancelRun();preview.close();loadingPreview?.recycle();loadingPreview=null;loaded?.bitmap?.recycle();loaded=null
        val release=isFinishing;worker.execute{disposePrepared(prepared);prepared=null;if(release){CompositionFiles.release(applicationContext,sourcePath);if(originalPath!=sourcePath)CompositionFiles.release(applicationContext,originalPath)}}
        worker.shutdown();super.onDestroy()
    }
}
