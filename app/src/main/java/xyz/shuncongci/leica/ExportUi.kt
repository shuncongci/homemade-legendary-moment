package xyz.shuncongci.leica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.*
import android.os.Build
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One Activity decor, one local coordinate space, one unchanged StudioButton surface.
 * All four rectangle edges and scrim/content derive from an explicit eased shape progress.
 * No PopupWindow, post-layout screen offset patches, translated endpoint or clipped spring. */
object ExportUi {
    private val activeHandles=mutableListOf<Handle>()
    private fun description(f:ExportFormat)=when(f){
        ExportFormat.PNG->"默认 · 保留现有像素与透明，不额外有损压缩"
        ExportFormat.JPEG->"兼容性好 · 有损编码，照片通常更小"
        ExportFormat.WEBP_SMALL->"更省空间 · 有损编码"
        ExportFormat.WEBP_HIGH->"较高质量 · 有损编码"
        ExportFormat.WEBP_LOSSLESS->"不额外损失像素 · 编码较费内存"
    }
    private fun activity(context:Context):ComponentActivity? {
        var c=context
        while(c is ContextWrapper){if(c is ComponentActivity)return c;c=c.baseContext}
        return c as? ComponentActivity
    }
    /** Compose the actual parent matrices/layout/scroll, preserving subpixels and all four corners. */
    internal fun inDecor(view:View,decor:ViewGroup):ExportMotion.Rect {
        var child=view;val steps=mutableListOf<ExportMotion.Step>();val values=FloatArray(9)
        while(child!==decor){
            val parent=child.parent as? View?:error("导出按钮已离开当前窗口")
            child.matrix.getValues(values)
            steps.add(ExportMotion.Step(values[0],values[1],values[2],values[3],values[4],values[5],
                (child.left-parent.scrollX).toFloat(),(child.top-parent.scrollY).toFloat()))
            child=parent
        }
        return ExportMotion.map(ExportMotion.Rect(0f,0f,view.width.toFloat(),view.height.toFloat()),steps)
    }
    class Handle internal constructor(private val anchor:View,private val activity:ComponentActivity,
        private val decor:ViewGroup,private val layer:FrameLayout,private val surface:MorphSurface,
        private val panel:FrameLayout,private val scrim:View,private val reveals:List<View>,private val density:Float,private val preferredHeightDp:Int,private val preferredWidthDp:Int=420,private val below:Boolean=false,private val paneTitle:String="导出成片") {
        private val state=ExportMotion.State()
        private var parentHandle:Handle?=null
        private fun contains(view:View):Boolean {
            var v:View?=view
            while(v!=null){if(v===layer)return true;v=v.parent as? View};return false
        }
        private var animator:ValueAnimator?=null
        private var source:StudioButton.MorphSource?=null
        private var disposed=false
        var onDismiss:()->Unit = {}
        private var lastTarget:ExportMotion.Rect?=null
        private val previousFocus=decor.findFocus()
        private val oldAccessibility=anchor.importantForAccessibility
        private val oldAlpha=anchor.alpha
        var closeCompleted=false;private set
        private val backgroundAccessibility=mutableListOf<Pair<View,Int>>()
        private val back=object:OnBackPressedCallback(true){override fun handleOnBackPressed(){close()}}
        private val detach=object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){}
            override fun onViewDetachedFromWindow(v:View){dismiss()}
        }
        private val layoutWatch=ViewTreeObserver.OnPreDrawListener{
            if(!disposed && source!=null && !syncBounds())dismiss()
            true
        }
        val isShowing get()=!disposed && layer.parent===decor
        internal fun prepare(){
            parentHandle=activeHandles.lastOrNull{it.decor===decor && it.contains(anchor)};activeHandles.add(this)
            anchor.addOnAttachStateChangeListener(detach)
            activity.onBackPressedDispatcher.addCallback(activity,back)
            ViewCompat.setAccessibilityPaneTitle(layer,paneTitle)
            for(i in 0 until decor.childCount){val child=decor.getChildAt(i);if(child!==layer){backgroundAccessibility.add(child to child.importantForAccessibility);child.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}}
            layer.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_BACK){if(event.action==KeyEvent.ACTION_UP)close();true}else false}
            layer.doOnLayout{
                if(!isShowing || !anchor.isAttachedToWindow){dismiss();return@doOnLayout}
                source=if(anchor is StudioButton)anchor.beginSharedMorph()else{
                    anchor.isPressed=false;anchor.jumpDrawablesToCurrentState()
                    val bitmap=Bitmap.createBitmap(anchor.width,anchor.height,Bitmap.Config.ARGB_8888)
                    anchor.draw(Canvas(bitmap));anchor.alpha=0f
                    StudioButton.MorphSource(bitmap,0xff1c1d1f.toInt(),Color.TRANSPARENT,16*density,0f)
                };surface.source=source
                anchor.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if(!syncBounds()){dismiss();return@doOnLayout}
                decor.viewTreeObserver.addOnPreDrawListener(layoutWatch)
                render(0f)
                layer.doOnPreDraw{if(isShowing){layer.requestFocus();animate(state.open(),420L){}}}
            }
        }
        private fun syncBounds():Boolean {
            if(!anchor.isAttachedToWindow || anchor.width<=0 || anchor.height<=0 || layer.width<=0 || layer.height<=0)return false
            val origin=inDecor(layer,decor);val button=inDecor(anchor,decor).offset(-origin.left,-origin.top)
            val inset=ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val left=maxOf(0f,(inset?.left?:0)-origin.left)+10*density
            val top=maxOf(0f,(inset?.top?:0)-origin.top)+10*density
            val right=minOf(layer.width.toFloat(),decor.width-(inset?.right?:0)-origin.left)-10*density
            val bottom=minOf(layer.height.toFloat(),decor.height-(inset?.bottom?:0)-origin.top)-4*density
            if(right-left<32*density || bottom-top<80*density)return false
            val safe=ExportMotion.Rect(left,top,right,bottom)
            val font=anchor.resources.configuration.fontScale.coerceAtLeast(1f)
            val target=WorkspaceMotion.place(safe,button,preferredWidthDp*density,((preferredHeightDp+60*(font-1))*density).coerceAtMost(safe.height),below)
            val moved=surface.origin!=button || surface.target!=target
            surface.origin=button;surface.target=target
            if(target!=lastTarget){
                lastTarget=target
                panel.layoutParams=FrameLayout.LayoutParams(target.width.roundToInt(),target.height.roundToInt()).apply{leftMargin=target.left.roundToInt();topMargin=target.top.roundToInt()}
            }
            if(moved){surface.invalidate();surface.invalidateOutline()};return true
        }
        private fun render(progress:Float){
            surface.progress=progress
            scrim.alpha=.42f*ExportMotion.smooth(progress)
            surface.elevation=12*density*progress
            reveals.forEachIndexed{i,v->val p=ExportMotion.content(progress,i);v.alpha=p;v.translationY=(1-p)*8*density}
        }
        private fun animate(run:ExportMotion.Run,duration:Long,done:()->Unit){
            animator?.cancel()
            if(!ValueAnimator.areAnimatorsEnabled() || run.from==run.to){
                state.accept(run,run.to);render(state.progress)
                if(state.complete(run))done()
                return
            }
            animator=ValueAnimator.ofFloat(run.from,run.to).apply{
                this.duration=duration
                interpolator=TimeInterpolator{run.ease(it)}
                var canceled=false
                addUpdateListener{if(state.accept(run,it.animatedValue as Float))render(state.progress)}
                addListener(object:AnimatorListenerAdapter(){
                    override fun onAnimationCancel(animation:Animator){canceled=true}
                    override fun onAnimationEnd(animation:Animator){if(!canceled && state.complete(run)){render(state.progress);done()}}
                })
                start()
            }
        }
        fun close(after:()->Unit={}){
            if(!isShowing)return
            val run=state.close()?:return
            if(source==null || !syncBounds()){dismiss();return}
            animate(run,(180+100*run.from).toLong()){
                // First restore the real button at exactly the same rect/palette, then remove its ghost.
                closeCompleted=true;dispose();after()
            }
        }
        private fun dispose(){
            if(disposed)return;disposed=true
            // Nested help/details must restore accessibility BEFORE their parent restores the Activity.
            activeHandles.filter{it.parentHandle===this}.toList().asReversed().forEach{it.dismiss()}
            activeHandles.remove(this);state.dismiss();animator?.cancel();animator=null
            back.remove();anchor.removeOnAttachStateChangeListener(detach)
            if(decor.viewTreeObserver.isAlive)decor.viewTreeObserver.removeOnPreDrawListener(layoutWatch)
            if(source!=null){if(anchor is StudioButton)anchor.endSharedMorph()else anchor.alpha=oldAlpha}
            anchor.importantForAccessibility=oldAccessibility
            backgroundAccessibility.forEach{(view,value)->view.importantForAccessibility=value};backgroundAccessibility.clear()
            if(layer.parent===decor)decor.removeView(layer)
            surface.source=null;source?.bitmap?.recycle();source=null
            if(previousFocus?.isAttachedToWindow==true)previousFocus.requestFocus()else if(anchor.isAttachedToWindow)anchor.requestFocus()
            onDismiss()
        }
        /** Lifecycle cancel never publishes/export-calls, including cancellation during close. */
        fun dismiss(){dispose()}
        internal fun geometryForDiagnostic()=surface.currentRect()
    }
    internal class MorphSurface(context:Context):FrameLayout(context){
        var origin=ExportMotion.Rect(0f,0f,1f,1f)
        var target=origin
        var source:StudioButton.MorphSource?=null
        var progress=0f;set(value){field=value;invalidate();invalidateOutline()}
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
        private val path=Path()
        fun currentRect()=ExportMotion.mix(origin,target,progress)
        private fun radius()=(source?.radius?:0f)*(1-progress)+18*resources.displayMetrics.density*progress
        init{
            setWillNotDraw(false);clipChildren=false;clipToPadding=false
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,outline:Outline){
                val r=currentRect();outline.setRoundRect(r.left.roundToInt(),r.top.roundToInt(),r.right.roundToInt(),r.bottom.roundToInt(),radius())
            }}
        }
        override fun onDraw(canvas:Canvas){
            val s=source?:return
            if(progress<=0f)return // endpoint is the exact button bitmap, not a second guessed drawing.
            val r=currentRect();val bounds=RectF(r.left,r.top,r.right,r.bottom)
            paint.color=androidx.core.graphics.ColorUtils.blendARGB(s.fill,0xff1c1d1f.toInt(),progress);paint.alpha=255;canvas.drawRoundRect(bounds,radius(),radius(),paint)
            stroke.color=androidx.core.graphics.ColorUtils.blendARGB(s.stroke,0xff373839.toInt(),progress);stroke.strokeWidth=s.strokeWidth;bounds.inset(s.strokeWidth/2,s.strokeWidth/2)
            canvas.drawRoundRect(bounds,radius(),radius(),stroke)
        }
        override fun onInterceptTouchEvent(event:MotionEvent)=progress<1f || super.onInterceptTouchEvent(event)
        override fun onTouchEvent(event:MotionEvent)=progress<1f || super.onTouchEvent(event)
        override fun dispatchDraw(canvas:Canvas){
            val s=source?:return;val r=currentRect();val count=canvas.save()
            path.reset();path.addRoundRect(RectF(r.left,r.top,r.right,r.bottom),radius(),radius(),Path.Direction.CW)
            canvas.clipPath(path);super.dispatchDraw(canvas);canvas.restoreToCount(count)
            val alpha=ExportMotion.ghost(progress)
            if(alpha>0f){paint.color=Color.WHITE;paint.alpha=(alpha*255).roundToInt()
                canvas.drawBitmap(s.bitmap,null,RectF(origin.left,origin.top,origin.right,origin.bottom),paint)
            }
        }
    }
    fun panel(anchor:View,title:String,content:View,heightDp:Int=540,widthDp:Int=420,below:Boolean=true,footer:View?=null):Handle? {
        if(!anchor.isAttachedToWindow || anchor.width<=0 || anchor.height<=0)return null
        val host=activity(anchor.context)?:return null
        val decor=anchor.rootView as? ViewGroup?:return null
        val d=anchor.resources.displayMetrics.density;fun dp(n:Int)=(n*d).roundToInt()
        val root=FrameLayout(anchor.context).apply{isFocusableInTouchMode=true;isClickable=true;clipChildren=false;clipToPadding=false}
        val scrim=View(anchor.context).apply{setBackgroundColor(Color.BLACK);alpha=0f};root.addView(scrim,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        val surface=MorphSurface(anchor.context);root.addView(surface,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        val panel=FrameLayout(anchor.context).apply{isClickable=true;setPadding(dp(14),dp(6),dp(14),dp(10))};surface.addView(panel,FrameLayout.LayoutParams(1,1))
        val column=LinearLayout(anchor.context).apply{orientation=LinearLayout.VERTICAL};panel.addView(column,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        val heading=LinearLayout(anchor.context).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.addView(TextView(anchor.context).apply{text=title;textSize=18f;setTextColor(0xffeee8dd.toInt());ViewCompat.setAccessibilityHeading(this,true)},LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        val close=StudioButton(anchor.context).apply{text="";contentDescription="收起$title";borderVisible=false;setPadding(dp(12),dp(12),dp(12),dp(12));setCompoundDrawables(WorkspaceGlyph(WorkspaceGlyph.Kind.CLOSE){currentTextColor}.apply{setBounds(0,0,dp(20),dp(20))},null,null,null)}
        heading.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)));column.addView(heading)
        val scroll=ScrollView(anchor.context).apply{isFillViewport=false;clipToPadding=false;addView(content)};column.addView(scroll,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        footer?.let{column.addView(it,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))}
        val handle=Handle(anchor,host,decor,root,surface,panel,scrim,listOf(heading,scroll)+listOfNotNull(footer),d,heightDp,widthDp,below,title)
        close.setOnClickListener{handle.close()};scrim.setOnClickListener{handle.close()}
        decor.addView(root,ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));handle.prepare();return handle
    }
    fun show(anchor:StudioButton,current:ExportFormat,sizeLabel:String,selected:(ExportFormat)->Unit,export:(ExportFormat)->Unit,
             sourceHdr:Boolean=false,hdrUnavailableReason:String?=null,exportHdr:(()->Unit)?=null,
             badge:Boolean?=null,badgeChanged:(Boolean)->Unit={},preserveMetadata:Boolean=false,stateLabel:String?=null):Handle? {
        if(!anchor.isAttachedToWindow || anchor.width<=0 || anchor.height<=0)return null
        val context=anchor.context;val host=activity(context)?:return null
        val decor=anchor.rootView as? ViewGroup?:return null
        val d=context.resources.displayMetrics.density;fun dp(n:Int)=(n*d).roundToInt()
        val ink=0xffeee8dd.toInt()
        val secondaryInk=0xffb5b5ae.toInt()
        val accent=0xffd9d2c4.toInt()
        val root=FrameLayout(context).apply{isFocusableInTouchMode=true;isClickable=true;clipChildren=false;clipToPadding=false}
        val scrim=View(context).apply{setBackgroundColor(Color.BLACK);alpha=0f}
        root.addView(scrim,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        val surface=MorphSurface(context);root.addView(surface,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        val panel=FrameLayout(context).apply{isClickable=true;setPadding(dp(14),dp(14),dp(14),dp(10))}
        surface.addView(panel,FrameLayout.LayoutParams(1,1))
        val column=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL};panel.addView(column,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(context).apply{isFillViewport=false;clipToPadding=false;isVerticalScrollBarEnabled=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;addView(body)}
        column.addView(scroll,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        fun text(value:String,size:Float,color:Int)=TextView(context).apply{text=value;textSize=size;setTextColor(color);setPadding(dp(5),dp(4),dp(5),dp(8))}
        val header=text("导出成片",20f,ink).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)};body.addView(header)
        val state=stateLabel?.takeIf{it.isNotBlank()}?.let{value->text(value,12f,ink).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}}
        state?.let{body.addView(it)}
        val size=text("$sizeLabel · 不放大 · ${if(sourceHdr)"HDR成片直接保存"else "SDR成片直接保存"}",11f,secondaryInk);body.addView(size)
        val hdrNote=text(if(sourceHdr)"当前为 HDR · 仅提供 Ultra HDR JPG。保存已生成成片，不重新调色或虚化。"else "当前为 SDR · 请选择下方导出格式。",11f,secondaryInk);body.addView(hdrNote)
        if(preserveMetadata)hdrNote.append("\n保留可读取的拍摄信息，包括原有位置。部分附加信息只作归档，相册可能不显示；系统已隐藏的信息无法恢复。")
        var choice=if(current.supports(Build.VERSION.SDK_INT))current else ExportFormat.DEFAULT
        var badgeOn=badge==true
        // Same white/black selected toggle as the editor. Hidden where the caller cannot honour it.
        val badgeToggle=badge?.let{SettingsRow(context,"徕卡一瞬相册标识",WorkspaceGlyph.Kind.WATERMARK,true).apply{setChecked(badgeOn);subtitle("只写照片信息，不改画面；开启后仅 JPG")}}
        badgeToggle?.let{body.addView(it,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(2)})}
        val badgeNote=text("只写在照片信息里，不改画面；开启后只能存为 JPG。小米相册能否显示取决于系统版本。",10f,secondaryInk).apply{visibility=if(badge==null)View.GONE else View.VISIBLE};body.addView(badgeNote)
        val group=RadioGroup(context).apply{orientation=RadioGroup.VERTICAL};body.addView(group)
        val options=ExportMenuChoices.available(sourceHdr,Build.VERSION.SDK_INT)
        val cards=options.map{option->
            val format=option.format
            RadioButton(context).apply{
                id=View.generateViewId()
                text=if(format==null)"${option.label}\n保留 HDR · JPEG + 增益图" else "${option.label}\n${format.storageHint}\n${format.qualityRating}"
                textSize=12f;setTextColor(ink);buttonTintList=ColorStateList.valueOf(accent)
                gravity=Gravity.CENTER_VERTICAL;minHeight=dp(if(option.hdr)72 else 88);setPadding(dp(4),dp(9),dp(5),dp(9));isChecked=option.selected(choice)
                contentDescription=if(format==null)"${option.label}，保留 HDR，当前唯一导出格式" else "${option.label}，${format.storageHint}，${format.qualityRating}，${description(format)}"
                group.addView(this,RadioGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
                setOnClickListener{if(!badgeOn && format!=null && choice!=format){choice=format;selected(format)}}
            }
        }
        val note=text(if(sourceHdr)"导出为带增益图的 JPG；实际裁剪与尺寸设置仍生效。裁剪时只处理几何与编码，不重新运行模型。" else "体积估计以 PNG 为参照，实际大小取决于照片。\nPNG 无损保存当前成片，不是备份原片；裁剪和尺寸设置仍然生效。需要保留透明背景时请选择 PNG。SDR 导出为 8 位 sRGB，不会自动缩小图片。",10f,secondaryInk);body.addView(note)
        /** Keep the format list stable; unavailable choices dim without shifting scroll position. */
        fun applyBadge(){
            val effective=ExportMenuChoices.exportFormat(choice,badgeOn)
            // Stable list: unavailable formats dim in place, no accordion expansion or scroll jump.
            options.forEachIndexed{i,o->cards[i].isEnabled=ExportMenuChoices.visible(o,badgeOn);cards[i].alpha=if(cards[i].isEnabled)1f else .38f;cards[i].isChecked=o.selected(effective)}
        }
        badgeToggle?.setOnClickListener{
            badgeOn=!badgeOn;badgeToggle.setChecked(badgeOn)
            badgeChanged(badgeOn);applyBadge()
        }
        applyBadge()
        val footer=WorkspaceActionRow(context).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,0)}
        column.addView(footer,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        val back=StudioButton(context).apply{text="收起"};footer.addView(back,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        val confirm=StudioButton(context,true).apply{text="确认导出"};footer.addView(confirm,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(8)})
        val handle=Handle(anchor,host,decor,root,surface,panel,scrim,listOf(header)+listOfNotNull(state)+listOf(size,hdrNote)+listOfNotNull(badgeToggle)+listOf(badgeNote)+cards+listOf(note,footer),d,(if(sourceHdr)340 else 580)+(if(state!=null)60 else 0))
        back.setOnClickListener{handle.close()};scrim.setOnClickListener{handle.close()}
        confirm.setOnClickListener{confirm.isEnabled=false;val snapshot=choice;val keepHdr=sourceHdr;handle.close{if(keepHdr)checkNotNull(exportHdr).invoke()else export(snapshot)}}
        decor.addView(root,ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        handle.prepare();scroll.scrollTo(0,0)
        return handle
    }
}
