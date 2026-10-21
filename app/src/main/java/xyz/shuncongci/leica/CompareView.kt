package xyz.shuncongci.leica

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration

/** Shared viewport; single-finger pan after pinch, divider drag only from its handle. */
class CompareView(context: Context) : View(context) {
    enum class Mode { SLIDER, RESULT, ORIGINAL }
    var original: Bitmap?=null
        set(v) { if(field!==v && field!==result)field?.recycle();field=v;invalidate() }
    var result: Bitmap?=null
        set(v) { if(field!==v && field!==original)field?.recycle();field=v;invalidate() }
    var crop=CompositionGeometry.FULL
        set(v){if(field!=v){field=v;resetViewport()}}
    var mode=Mode.SLIDER
        set(v) { if(field!=v)resetViewport();field=v;invalidate() }
    var focusPicking=false
        set(v) { field=v;invalidate() }
    var subjectPicking=false
        set(v){field=v;invalidate()}
    var subjectPoints:List<LegendaryMath.Point> = emptyList()
        set(v){field=v.toList();invalidate()}
    private val picking get()=focusPicking || subjectPicking
    var focusPoint: LegendaryMath.Point?=null
        set(v) { field=v;invalidate() }
    /** Owned UI-only bitmap, drawn with the original's exact viewport, never exported. */
    var focusOverlay:Bitmap?=null
        set(v) { if(field!==v)field?.recycle();field=v;invalidate() }
    var subjectOverlay:Bitmap?=null
        set(v){if(field!==v)field?.recycle();field=v;invalidate()}
    var showSubject=false
        set(v){field=v;invalidate()}
    var inspection:Bitmap?=null
        set(v){field=v;invalidate()}
    var showFocusRegion=true
        set(v) { field=v;invalidate() }
    var onFocusPoint: ((LegendaryMath.Point)->Unit)?=null
    var onSubjectPoint: ((LegendaryMath.Point)->Unit)?=null
    var onSubjectMoved: ((LegendaryMath.Point,LegendaryMath.Point)->Unit)?=null
    private val pointDrag=PointDrag()
    var pointsEditable=false
        set(v){field=v;if(!v)pointDrag.cancel()}
    private val nativeTiles=NativeCompareTiles(context){invalidate()}
    fun setSources(original: android.net.Uri?,result: android.net.Uri?){nativeTiles.setSources(original,result)}
    fun closeSources(){nativeTiles.close();focusOverlay=null;subjectOverlay=null}
    private var split=.5f
    private val viewport=PreviewViewport()
    private val touch=PreviewTouch(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var draggingDivider=false
    private var scaleFocusX=0f
    private var scaleFocusY=0f
    private val bmpPaint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;strokeWidth=3f*resources.displayMetrics.density }
    private val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=13f*resources.displayMetrics.scaledDensity;setShadowLayer(4f,0f,1f,Color.BLACK) }
    private val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.parseColor("#E6C57A");strokeWidth=3f*resources.displayMetrics.density;style=Paint.Style.STROKE }
    private val subjectMarker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xff38c8f4.toInt();style=Paint.Style.FILL;setShadowLayer(3f,0f,1f,Color.BLACK)}
    private val subjectNumber=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xff10222b.toInt();textAlign=Paint.Align.CENTER;textSize=11f*resources.displayMetrics.scaledDensity;typeface=Typeface.DEFAULT_BOLD}
    private val dst=RectF();private val cropDst=RectF()
    private val scaler=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector:ScaleGestureDetector):Boolean {
            scaleFocusX=detector.focusX;scaleFocusY=detector.focusY
            touch.multiple();draggingDivider=false;return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val base=original ?: result ?: return false
            fit(base)
            viewport.scale(detector.scaleFactor,scaleFocusX,scaleFocusY,detector.focusX,detector.focusY)
            scaleFocusX=detector.focusX;scaleFocusY=detector.focusY
            touch.multiple();invalidate();return true
        }
    }).apply{isQuickScaleEnabled=false}
    init { contentDescription="原图与成片对比，双指缩放，拖动平移；拖动分隔线滑动对比；轻点原照片设置焦点" }
    fun resetViewport() { viewport.reset();split=.5f;invalidate() }
    private fun fit(b: Bitmap) {
        val bw=if(nativeTiles.width>0)nativeTiles.width else b.width
        val bh=if(nativeTiles.height>0)nativeTiles.height else b.height
        val c=if(picking || inspection!=null || mode==Mode.ORIGINAL)CompositionGeometry.FULL else crop
        val d=viewport.layout(width.toFloat(),height.toFloat(),kotlin.math.max(1,kotlin.math.round(bw*c.w).toInt()),kotlin.math.max(1,kotlin.math.round(bh*c.h).toInt()))
        cropDst.set(d.left,d.top,d.right,d.bottom)
        val fw=cropDst.width()/c.w.toFloat();val fh=cropDst.height()/c.h.toFloat()
        val l=d.left-c.l.toFloat()*fw;val t=d.top-c.t.toFloat()*fh
        dst.set(l,t,l+fw,t+fh)
    }
    // Keep the divider on screen while the photo pans/zooms.
    private fun dividerX()=width*split
    override fun onDraw(canvas: Canvas) {
        val o=original;val r=result;val base=o ?: r ?: return
        fit(base)
        inspection?.let{canvas.drawBitmap(it,null,dst,bmpPaint);return}
        val cropSave=canvas.save();canvas.clipRect(cropDst)
        nativeTiles.request(dst,width,height)
        if(picking || r==null || mode==Mode.ORIGINAL){canvas.drawBitmap(o ?: base,null,dst,bmpPaint);nativeTiles.draw(canvas,dst,false,bmpPaint)}
        else if(o==null || mode==Mode.RESULT){canvas.drawBitmap(r,null,dst,bmpPaint);nativeTiles.draw(canvas,dst,true,bmpPaint)}
        else {
            val x=dividerX()
            canvas.save();canvas.clipRect(0f,0f,x,height.toFloat());canvas.drawBitmap(o,null,dst,bmpPaint);nativeTiles.draw(canvas,dst,false,bmpPaint);canvas.restore()
            canvas.save();canvas.clipRect(x,0f,width.toFloat(),height.toFloat());canvas.drawBitmap(r,null,dst,bmpPaint);nativeTiles.draw(canvas,dst,true,bmpPaint);canvas.restore()
            canvas.drawLine(x,0f,x,height.toFloat(),linePaint)
            canvas.drawCircle(x,height/2f,12f*resources.displayMetrics.density,linePaint)
            canvas.drawText("原图",8f,24f*resources.displayMetrics.density,labelPaint)
            canvas.drawText("成片",width-labelPaint.measureText("成片")-8f,24f*resources.displayMetrics.density,labelPaint)
        }
        if(showFocusRegion && (picking || mode==Mode.ORIGINAL || r==null))
            focusOverlay?.let{canvas.drawBitmap(it,null,dst,bmpPaint)}
        if(showSubject && (picking || mode==Mode.ORIGINAL || r==null))
            subjectOverlay?.let{canvas.drawBitmap(it,null,dst,bmpPaint)}
        if(picking || mode==Mode.ORIGINAL || r==null)focusPoint?.let {original->
            val it=pointDrag.display(original)
            val x=dst.left+dst.width()*it.x;val y=dst.top+dst.height()*it.y;val radius=12*resources.displayMetrics.density
            canvas.drawCircle(x,y,radius,marker);canvas.drawLine(x-radius*1.5f,y,x+radius*1.5f,y,marker);canvas.drawLine(x,y-radius*1.5f,x,y+radius*1.5f,marker)
        }
        if(picking || mode==Mode.ORIGINAL || r==null)subjectPoints.forEachIndexed{at,original->
            val p=pointDrag.display(original,at)
            val x=dst.left+dst.width()*p.x;val y=dst.top+dst.height()*p.y
            canvas.drawCircle(x,y,11*resources.displayMetrics.density,subjectMarker)
            canvas.drawText((at+1).toString(),x,y-(subjectNumber.ascent()+subjectNumber.descent())/2,subjectNumber)
        }
        pointDrag.preview?.let{p->
            val x=dst.left+dst.width()*p.x;val y=dst.top+dst.height()*p.y
            canvas.drawCircle(x,y,22*resources.displayMetrics.density,marker)
            canvas.drawText("松手调整",(x-labelPaint.measureText("松手调整")/2).coerceIn(0f,(width-labelPaint.measureText("松手调整")).coerceAtLeast(0f)),(y-36*resources.displayMetrics.density).coerceAtLeast(24*resources.displayMetrics.density),labelPaint)
        }
        canvas.restoreToCount(cropSave)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val base=original ?: result ?: return false
        fit(base);scaler.onTouchEvent(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch.begin(e.getPointerId(0),e.x,e.y)
                pointDrag.cancel()
                if(pointsEditable && inspection==null && (picking || result==null || mode==Mode.ORIGINAL)){
                    val subjects=subjectPoints.mapIndexed{i,p->PointDrag.Handle(p,i)};val focus=focusPoint?.let{listOf(PointDrag.Handle(it))}?:emptyList()
                    val handles=if(subjectPicking)subjects+focus else focus+subjects
                    pointDrag.begin(handles,e.x,e.y,dst.left,dst.top,dst.width(),dst.height(),24*resources.displayMetrics.density,ViewConfiguration.get(context).scaledTouchSlop.toFloat())
                }
                draggingDivider=!pointDrag.active && !picking && result!=null && mode==Mode.SLIDER &&
                    (viewport.zoom<=1.001f || kotlin.math.abs(e.x-dividerX())<=24*resources.displayMetrics.density)
                parent?.requestDisallowInterceptTouchEvent(true);return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { pointDrag.cancel();invalidate();touch.multiple();draggingDivider=false;return true }
            MotionEvent.ACTION_POINTER_UP -> {
                touch.multiple();draggingDivider=false
                // Rebase even when the lifted finger was not the active one.
                val remaining=(0 until e.pointerCount).firstOrNull{it!=e.actionIndex}
                if(remaining!=null)touch.rebase(e.getPointerId(remaining),e.getX(remaining),e.getY(remaining))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val i=e.findPointerIndex(touch.activeId)
                if(i<0){touch.rebase(e.getPointerId(0),e.x,e.y);return true}
                val x=e.getX(i);val y=e.getY(i)
                if(!scaler.isInProgress && e.pointerCount==1) {
                    val (dx,dy)=touch.move(x,y)
                    if(pointDrag.active && !touch.multi)pointDrag.move(x,y)
                    else if(draggingDivider && !touch.multi)split=(x/width.coerceAtLeast(1)).coerceIn(0f,1f)
                    else viewport.drag(dx,dy)
                } else touch.rebase(e.getPointerId(i),x,y)
                invalidate();return true
            }
            MotionEvent.ACTION_UP -> {
                fit(base)
                if(pointDrag.active && !touch.multi){
                    val grabbed=pointDrag.handle;pointDrag.move(e.x,e.y);val change=pointDrag.finish()
                    if(change!=null && pointsEditable){if(change.handle.subjectIndex<0)onFocusPoint?.invoke(change.point) else onSubjectMoved?.invoke(change.handle.point,change.point)}
                    else if(!touch.moved && grabbed!=null && pointsEditable){
                        if(subjectPicking && grabbed.subjectIndex<0)onSubjectPoint?.invoke(grabbed.point)
                        else if(focusPicking && grabbed.subjectIndex>=0)onFocusPoint?.invoke(grabbed.point)
                    }
                    performClick();invalidate()
                }else if(picking && pointsEditable && !touch.moved && !touch.multi && dst.contains(e.x,e.y)) {
                    val p=LegendaryMath.Point(((e.x-dst.left)/dst.width()).coerceIn(0f,1f),((e.y-dst.top)/dst.height()).coerceIn(0f,1f))
                    if(subjectPicking)onSubjectPoint?.invoke(p) else {focusPoint=p;onFocusPoint?.invoke(p)};performClick()
                }
                touch.end();draggingDivider=false;parent?.requestDisallowInterceptTouchEvent(false);return true
            }
            MotionEvent.ACTION_CANCEL -> { pointDrag.cancel();invalidate();touch.end();draggingDivider=false;parent?.requestDisallowInterceptTouchEvent(false);return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
}
