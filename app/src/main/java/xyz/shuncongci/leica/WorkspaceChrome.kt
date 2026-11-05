package xyz.shuncongci.leica

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import kotlin.math.max

/** Fixed 24-unit vectors, with one semantic key per action and no inherited paint state. */
class WorkspaceGlyph(private val kind:Kind,private val ink:()->Int):Drawable() {
    enum class Kind { MONITOR,PARAMETERS,MORE,IMAGE,GRAY,DEPTH,SPACE,RESULT,COMPARE,CAMERA,EXTERNAL,DEVELOP,EXPORT,FULLSCREEN,COLLAPSE,CLOSE,STOP,FOLDER,BELL,SHIELD,LOCATION,LOCK,OUTPUT,ORIGINAL,WATERMARK,CHEVRON,CHEVRON_DOWN,CHEVRON_UP,BACK,INFO,PALETTE,FILE,OVERLAY,CLOCK,RESTORE,CROP,FLOW,PLAY,PAUSE }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val spec=WorkspaceIcons.spec(kind.name)
    private val outline=PathParser.createPathFromPathData(spec.outline)
    private val solid=PathParser.createPathFromPathData(spec.solid)
    var indicator=false;set(value){if(field!=value){field=value;invalidateSelf()}}
    private var opacity=255
    override fun draw(c:Canvas){
        val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
        paint.color=ink();paint.alpha=opacity;paint.style=Paint.Style.STROKE
        outline?.let{c.drawPath(it,paint)}
        paint.style=Paint.Style.FILL;solid?.let{c.drawPath(it,paint)}
        if(indicator){paint.color=0xff83b99e.toInt();paint.alpha=opacity;c.drawCircle(20f,4f,2f,paint)}
        c.restoreToCount(save)
    }
    override fun getIntrinsicWidth()=bounds.width().takeIf{it>0}?:24
    override fun getIntrinsicHeight()=bounds.height().takeIf{it>0}?:24
    override fun setAlpha(alpha:Int){opacity=alpha.coerceIn(0,255);invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Draws only the already loaded editing preview. No gallery query, decode or bitmap copy. */
class WorkspaceThumbnail:Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF();private val crop=Rect();private val clip=Path()
    var bitmap:Bitmap?=null;set(value){if(field!==value){field=value;invalidateSelf()}}
    private val icon=WorkspaceGlyph(WorkspaceGlyph.Kind.IMAGE){0xffeee8dd.toInt()}
    override fun draw(c:Canvas){
        rect.set(bounds);clip.reset();clip.addRoundRect(rect,12f*rect.width()/56f,12f*rect.height()/56f,Path.Direction.CW)
        val save=c.save();c.clipPath(clip);c.drawColor(0xff292c2e.toInt())
        bitmap?.takeIf{!it.isRecycled}?.let{b->
            val scale=max(rect.width()/b.width,rect.height()/b.height);val w=(rect.width()/scale).toInt().coerceAtLeast(1);val h=(rect.height()/scale).toInt().coerceAtLeast(1)
            crop.set((b.width-w)/2,(b.height-h)/2,(b.width+w)/2,(b.height+h)/2);c.drawBitmap(b,crop,rect,paint)
        }
        val s=(rect.width()*.35f).toInt();icon.setBounds(bounds.right-s-3,bounds.bottom-s-3,bounds.right-3,bounds.bottom-3);icon.draw(c);c.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** A grouped settings row. Binding state cannot dispatch an action or start a service. */
class SettingsRow(context:Context,title:String,kind:WorkspaceGlyph.Kind,private val switching:Boolean=false):LinearLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val caption=TextView(context).apply{text=title;textSize=15f;setTextColor(0xffeee8dd.toInt())}
    val detail=TextView(context).apply{textSize=12f;setTextColor(0xff8f908e.toInt());maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END}
    private var checked=false
    private var granted:Boolean?=null
    private val accessory=object:View(context){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val chevron=WorkspaceGlyph(WorkspaceGlyph.Kind.CHEVRON){0xff777a78.toInt()}
        override fun onDraw(c:Canvas){
            val d=resources.displayMetrics.density
            if(switching){
                val l=(width-42*d)/2;val t=(height-26*d)/2
                paint.color=if(checked)0xffd9d2c4.toInt()else 0xff454847.toInt();c.drawRoundRect(l,t,l+42*d,t+26*d,13*d,13*d,paint)
                paint.color=if(checked)0xff171717.toInt()else 0xff979b97.toInt();c.drawCircle(l+(if(checked)29 else 13)*d,t+13*d,10*d,paint)
            }else if(granted!=null){
                paint.style=Paint.Style.STROKE;paint.strokeWidth=2*d;paint.strokeCap=Paint.Cap.ROUND;paint.color=if(granted==true)0xff83b99e.toInt()else 0xffbc7c71.toInt()
                if(granted==true){val p=Path();p.moveTo(width/2f-5*d,height/2f);p.lineTo(width/2f-d,height/2f+4*d);p.lineTo(width/2f+7*d,height/2f-5*d);c.drawPath(p,paint)}
                else {paint.style=Paint.Style.FILL;c.drawCircle(width/2f,height/2f,3*d,paint)}
                paint.style=Paint.Style.FILL
            }else{chevron.setBounds(width/2-dp(6),height/2-dp(8),width/2+dp(6),height/2+dp(8));chevron.draw(c)}
        }
    }
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(68);setPadding(dp(14),dp(12),dp(8),dp(12));isClickable=true;isFocusable=true
        background=RippleDrawable(ColorStateList.valueOf(0x14ffffff),null,GradientDrawable().apply{setColor(Color.WHITE)})
        val glyph=WorkspaceGlyph(kind){0xffa8aaa4.toInt()}.apply{setBounds(0,0,dp(20),dp(20))}
        addView(ImageView(context).apply{setImageDrawable(glyph);scaleType=ImageView.ScaleType.CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(dp(24),dp(32)).apply{marginEnd=dp(14)})
        addView(LinearLayout(context).apply{orientation=VERTICAL;addView(caption);addView(detail,LayoutParams(-1,-2).apply{topMargin=dp(3)})},LayoutParams(0,-2,1f))
        addView(accessory,LayoutParams(dp(if(switching)52 else 30),dp(36)))
        caption.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;detail.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;accessory.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun subtitle(value:String){if(detail.text.toString()!=value)detail.text=value;detail.visibility=if(value.isEmpty())GONE else VISIBLE;contentDescription="${caption.text}，$value"}
    fun setChecked(value:Boolean){checked=value;accessory.invalidate();ViewCompat.setStateDescription(this,if(value)"已开启"else "已关闭")}
    fun permission(value:Boolean){granted=value;accessory.invalidate();ViewCompat.setStateDescription(this,if(value)"已授权"else "未授权")}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);if(switching){info.className="android.widget.Switch";info.isCheckable=true;info.isChecked=checked}}
    override fun setEnabled(enabled:Boolean){super.setEnabled(enabled);alpha=if(enabled)1f else .42f}
}

/** Parameter action rows wrap as whole controls, rather than clipping large-font captions. */
class WorkspaceActionRow(context:Context):LinearLayout(context) {
    init{gravity=Gravity.CENTER_VERTICAL}
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val d=resources.displayMetrics.density
        val available=((MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight)/d).toInt().coerceAtLeast(0)
        val count=(0 until childCount).count{getChildAt(it).visibility!=GONE}
        val stack=UiPolishPolicy.stackActions(available,resources.configuration.fontScale,count)
        orientation=if(stack)VERTICAL else HORIZONTAL
        var visibleIndex=0
        for(i in 0 until childCount){
            val v=getChildAt(i);if(v.visibility==GONE)continue
            val p=(v.layoutParams as? LayoutParams)?:LayoutParams(-1,-2)
            val w=if(stack)-1 else 0;val weight=if(stack)0f else 1f
            val margin=if(!stack && visibleIndex>0)(8*d).toInt()else 0
            val top=if(stack && visibleIndex>0)(8*d).toInt()else 0
            if(p.width!=w || p.height!= -2 || p.weight!=weight || p.marginStart!=margin || p.topMargin!=top){
                p.width=w;p.height=-2;p.weight=weight;p.marginStart=margin;p.topMargin=top;v.layoutParams=p
            }
            visibleIndex++
        }
        super.onMeasure(widthSpec,heightSpec)
    }
}

/** Bounded popup content scrolls on short windows and with accessibility font sizes. */
class WorkspaceMenuScroll(context:Context):android.widget.ScrollView(context) {
    init{isVerticalScrollBarEnabled=false;isFillViewport=false}
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val cap=(resources.displayMetrics.heightPixels*.65f).toInt()
        val supplied=MeasureSpec.getSize(heightSpec)
        val h=if(MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED)cap else minOf(cap,supplied)
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(h,MeasureSpec.AT_MOST))
    }
}
