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
    class Handle internal constructor(private val anchor:StudioButton,private val activity:ComponentActivity,
        private val decor:ViewGroup,private val layer:FrameLayout,private val surface:MorphSurface,
        private val panel:FrameLayout,private val scrim:View,private val reveals:List<View>,private val density:Float,private val preferredHeightDp:Int) {
        private val state=ExportMotion.State()
        private var animator:ValueAnimator?=null
        private var source:StudioButton.MorphSource?=null
        private var disposed=false
        private var lastTarget:ExportMotion.Rect?=null
        private val previousFocus=decor.findFocus()
        private val oldAccessibility=anchor.importantForAccessibility
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
            anchor.addOnAttachStateChangeListener(detach)
            activity.onBackPressedDispatcher.addCallback(activity,back)
            ViewCompat.setAccessibilityPaneTitle(layer,"导出格式")
            for(i in 0 until decor.childCount){val child=decor.getChildAt(i);if(child!==layer){backgroundAccessibility.add(child to child.importantForAccessibility);child.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}}
            layer.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_BACK){if(event.action==KeyEvent.ACTION_UP)close();true}else false}
            layer.doOnLayout{
                if(!isShowing || !anchor.isAttachedToWindow){dismiss();return@doOnLayout}
                source=anchor.beginSharedMorph();surface.source=source
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
            if(right-left<32*density || bottom-top<120*density || button.bottom-top<120*density)return false
            val safe=ExportMenuGeometry.Box(ceil(left).toInt(),ceil(top).toInt(),floor(right).toInt(),floor(bottom).toInt())
            val a=ExportMenuGeometry.Box(floor(button.left).toInt(),floor(button.top).toInt(),ceil(button.right).toInt(),ceil(button.bottom).toInt())
            val g=ExportMenuGeometry.place(safe,a,(380*density).roundToInt(),(preferredHeightDp*density).roundToInt())
            val target=ExportMotion.Rect(g.box.left.toFloat(),g.box.top.toFloat(),g.box.right.toFloat(),g.box.bottom.toFloat())
            val moved=surface.origin!=button || surface.target!=target
            surface.origin=button;surface.target=target
            if(target!=lastTarget){
                lastTarget=target
                panel.layoutParams=FrameLayout.LayoutParams(g.box.width,g.box.height).apply{leftMargin=g.box.left;topMargin=g.box.top}
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
                dispose();after()
            }
        }
        private fun dispose(){
            if(disposed)return;disposed=true;state.dismiss();animator?.cancel();animator=null
            back.remove();anchor.removeOnAttachStateChangeListener(detach)
            if(decor.viewTreeObserver.isAlive)decor.viewTreeObserver.removeOnPreDrawListener(layoutWatch)
            if(source!=null)anchor.endSharedMorph()
            anchor.importantForAccessibility=oldAccessibility
            backgroundAccessibility.forEach{(view,value)->view.importantForAccessibility=value};backgroundAccessibility.clear()
            if(layer.parent===decor)decor.removeView(layer)
            surface.source=null;source=null
            if(previousFocus?.isAttachedToWindow==true)previousFocus.requestFocus()else if(anchor.isAttachedToWindow)anchor.requestFocus()
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
        init{
            setWillNotDraw(false);clipChildren=false;clipToPadding=false
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,outline:Outline){
                val r=currentRect();outline.setRoundRect(r.left.roundToInt(),r.top.roundToInt(),r.right.roundToInt(),r.bottom.roundToInt(),source?.radius?:0f)
            }}
        }
        override fun onDraw(canvas:Canvas){
            val s=source?:return
            if(progress<=0f)return // endpoint is the exact button bitmap, not a second guessed drawing.
            val r=currentRect();val bounds=RectF(r.left,r.top,r.right,r.bottom)
            paint.color=s.fill;paint.alpha=255;canvas.drawRoundRect(bounds,s.radius,s.radius,paint)
            stroke.color=s.stroke;stroke.strokeWidth=s.strokeWidth;bounds.inset(s.strokeWidth/2,s.strokeWidth/2)
            canvas.drawRoundRect(bounds,s.radius,s.radius,stroke)
        }
        override fun dispatchDraw(canvas:Canvas){
            val s=source?:return;val r=currentRect();val count=canvas.save()
            path.reset();path.addRoundRect(RectF(r.left,r.top,r.right,r.bottom),s.radius,s.radius,Path.Direction.CW)
            canvas.clipPath(path);super.dispatchDraw(canvas);canvas.restoreToCount(count)
            val alpha=ExportMotion.ghost(progress)
            if(alpha>0f){paint.color=Color.WHITE;paint.alpha=(alpha*255).roundToInt()
                canvas.drawBitmap(s.bitmap,null,RectF(origin.left,origin.top,origin.right,origin.bottom),paint)
            }
        }
    }
    fun show(anchor:StudioButton,current:ExportFormat,sizeLabel:String,selected:(ExportFormat)->Unit,export:(ExportFormat)->Unit,
             sourceHdr:Boolean=false,hdrUnavailableReason:String?=null,exportHdr:(()->Unit)?=null,
             badge:Boolean?=null,badgeChanged:(Boolean)->Unit={}):Handle? {
        if(!anchor.isAttachedToWindow || anchor.width<=0 || anchor.height<=0)return null
        val context=anchor.context;val host=activity(context)?:return null
        val decor=host.window.decorView as? ViewGroup?:return null
        val d=context.resources.displayMetrics.density;fun dp(n:Int)=(n*d).roundToInt()
        val ink=if(anchor.morphLightSurface)0xff171717.toInt()else 0xffe2dace.toInt()
        val secondaryInk=if(anchor.morphLightSurface)0xff514d46.toInt()else 0xffb5ada1.toInt()
        val accent=if(anchor.morphLightSurface)0xff282725.toInt()else 0xffc7b99f.toInt()
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
        val size=text("$sizeLabel · 不放大 · ${if(sourceHdr)"HDR成片直接保存"else "SDR成片直接保存"}",11f,secondaryInk);body.addView(size)
        val hdrNote=text(if(sourceHdr)"当前为 HDR · 仅提供 Ultra HDR JPG。保存已生成成片，不重新调色或虚化。"else "当前为 SDR · 请选择下方导出格式。",11f,secondaryInk);body.addView(hdrNote)
        var choice=if(current.supports(Build.VERSION.SDK_INT))current else ExportFormat.DEFAULT
        var badgeOn=badge==true
        // Same white/black selected toggle as the editor. Hidden where the caller cannot honour it.
        val badgeToggle=badge?.let{StudioButton(context).apply{text="徕卡一瞬相册标识";isSelected=badgeOn;ViewCompat.setStateDescription(this,if(badgeOn)"已开启" else "已关闭")}}
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
        val note=text(if(sourceHdr)"导出为带增益图的 JPG；实际裁剪与尺寸设置仍生效。裁剪时只处理几何与编码，不重新运行模型。" else "比例以此PNG导出为基准，是本轮桌面样本的存储体积参考，不是运行内存或手机编码保证值；不同照片可超出范围。PNG本轮优化相对旧版另省约19–20%。\nPNG保留当前可用像素，不是复制未调色原文件；裁剪与已有尺寸仍生效。透明图请选择PNG；大图内存不足也可用分块PNG。不会自动缩图。现有SDR流程为8位sRGB。",10f,secondaryInk);body.addView(note)
        /** Formats the badge cannot carry collapse (fade + height) instead of jumping; the remembered SDR choice is kept. */
        fun applyBadge(animate:Boolean){
            if(animate && ValueAnimator.areAnimatorsEnabled())android.transition.TransitionManager.beginDelayedTransition(body,android.transition.AutoTransition().apply{duration=240})
            val effective=ExportMenuChoices.exportFormat(choice,badgeOn)
            options.forEachIndexed{i,o->cards[i].visibility=if(ExportMenuChoices.visible(o,badgeOn))View.VISIBLE else View.GONE;cards[i].isChecked=o.selected(effective)}
            if(!sourceHdr)note.visibility=if(badgeOn)View.GONE else View.VISIBLE
        }
        badgeToggle?.setOnClickListener{
            badgeOn=!badgeOn;badgeToggle.isSelected=badgeOn;ViewCompat.setStateDescription(badgeToggle,if(badgeOn)"已开启" else "已关闭")
            badgeChanged(badgeOn);applyBadge(true)
        }
        applyBadge(false)
        val footer=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,0)}
        column.addView(footer,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        val back=StudioButton(context).apply{text="收起"};footer.addView(back,LinearLayout.LayoutParams(dp(68),WRAP_CONTENT))
        val confirm=StudioButton(context,true).apply{text="确认导出"};footer.addView(confirm,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(8)})
        val handle=Handle(anchor,host,decor,root,surface,panel,scrim,listOf(header,size,hdrNote)+listOfNotNull(badgeToggle)+listOf(badgeNote)+cards+listOf(note,footer),d,if(sourceHdr)340 else 580)
        back.setOnClickListener{handle.close()};scrim.setOnClickListener{handle.close()}
        confirm.setOnClickListener{confirm.isEnabled=false;val snapshot=choice;val keepHdr=sourceHdr;handle.close{if(keepHdr)checkNotNull(exportHdr).invoke()else export(snapshot)}}
        decor.addView(root,ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        handle.prepare();scroll.scrollTo(0,0)
        return handle
    }
}
