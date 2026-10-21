package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import kotlin.math.min
import xyz.shuncongci.leica.CompositionGeometry.Crop

/** Photo fragments, not a crop-box overlay. Touch only orbits the display camera. */
class CompositionCropView(context:Context):View(context) {
    private var photo:Bitmap?=null
    private var scene:CompositionTileMotion?=null
    private var analysing=false;private var saving=false;private var active=false
    private var loop:ValueAnimator?=null;private var lastPlay=0L
    private var pointer=-1;private var lastX=0f;private var lastY=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK}
    private val path=Path();private val density=resources.displayMetrics.density
    private class DrawTile(val index:Int,val source:FloatArray){val screen=FloatArray(10);val matrix=Matrix()}
    private var tiles=emptyArray<DrawTile>();private var order=emptyArray<DrawTile>()
    private val depthOrder=Comparator<DrawTile>{a,b->a.screen[8].compareTo(b.screen[8])}
    init{
        isClickable=true;isFocusable=true;setLayerType(LAYER_TYPE_HARDWARE,null)
        contentDescription="构图照片。分析时可拖动三维视角；拖动不修改裁切方案。"
    }
    private fun motionEnabled()=active && isAttachedToWindow && windowVisibility==VISIBLE && ValueAnimator.areAnimatorsEnabled()
    fun setPhoto(bitmap:Bitmap,w:Int,h:Int){
        photo=bitmap
        if(scene?.mesh?.width!=w || scene?.mesh?.height!=h){
            scene=CompositionTileMotion(w,h).also{if(analysing)it.beginAnalysis()}
        }
        rebuild();syncLoop();invalidate()
    }
    private fun rebuild(){
        val s=scene?:return;val b=photo?:return
        tiles=Array(s.mesh.pieces.size){i->
            val box=s.mesh.pieces[i].box
            val l=box.left.toFloat()/s.mesh.width*b.width;val r=box.right.toFloat()/s.mesh.width*b.width
            val t=box.top.toFloat()/s.mesh.height*b.height;val bottom=box.bottom.toFloat()/s.mesh.height*b.height
            DrawTile(i,floatArrayOf(l,t,r,t,r,bottom,l,bottom))
        };order=tiles.copyOf()
    }
    fun beginAnalysis(){analysing=true;saving=false;scene?.beginAnalysis();rebuild();syncLoop();invalidate()}
    fun showProposals(crops:List<Crop>,selected:Int){
        analysing=false;saving=false
        scene?.reveal(crops,selected);rebuild()
        if(!motionEnabled())scene?.settle()
        contentDescription="构图方案预览。切换全程保持二维，裁去的区域飞出，恢复的区域飞回。"
        syncLoop();invalidate()
    }
    fun selectProposal(index:Int){
        if(saving || analysing)return
        scene?.select(index)
        if(!motionEnabled())scene?.settle()
        syncLoop();invalidate()
    }
    fun beginApply(){saving=true;releaseTouch();syncLoop()}
    fun saved(){saving=false;contentDescription="构图已保存；当前为所选方案的缩放预览。";syncLoop();invalidate()}
    fun restoreSelection(){analysing=false;saving=false;scene?.settle();stopLoop();releaseTouch();invalidate()}
    fun setActive(value:Boolean){
        active=value
        if(!value){stopLoop();releaseTouch();if(!analysing)scene?.settle()}
        else syncLoop()
        invalidate()
    }
    private fun stopLoop(){val old=loop;loop=null;old?.cancel();lastPlay=0L}
    private fun syncLoop(){
        val s=scene
        if(s==null || !motionEnabled() || !s.needsFrames){stopLoop();return}
        if(loop!=null)return
        lastPlay=0L
        loop=ValueAnimator.ofFloat(0f,1f).apply{
            duration=10000;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
            addUpdateListener{a->
                if(!motionEnabled()){s.settle();stopLoop();invalidate();return@addUpdateListener}
                val play=a.currentPlayTime
                s.advance((play-lastPlay).coerceAtLeast(0).coerceAtMost(80).toFloat());lastPlay=play
                invalidate()
                if(!s.needsFrames)stopLoop()
            };start()
        }
    }
    fun close(){stopLoop();releaseTouch();photo=null;scene=null;tiles=emptyArray();order=emptyArray()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();syncLoop()}
    override fun onDetachedFromWindow(){stopLoop();releaseTouch();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE)syncLoop()else{stopLoop();releaseTouch()}
    }
    override fun onDraw(canvas:Canvas){
        canvas.drawColor(0xff0d0e0f.toInt())
        val b=photo?:return;val s=scene?:return
        if(b.isRecycled || width<=0 || height<=0)return
        // Reduced motion uses the exact 2D proposal, with no floating tiles or hidden animation queue.
        if(!ValueAnimator.areAnimatorsEnabled() || s.mode==CompositionTileMotion.Mode.READY){drawFlat(canvas,b,s);return}
        for(tile in tiles)s.project(tile.index,width,height,tile.screen)
        order.sortWith(depthOrder)
        for(tile in order){
            val q=tile.screen;val opacity=q[9]
            if(opacity<=.001f)continue
            path.rewind();path.moveTo(q[0],q[1]);for(k in 1..3)path.lineTo(q[k*2],q[k*2+1]);path.close()
            if(!tile.matrix.setPolyToPoly(tile.source,0,q,0,4))continue
            if(s.camera.lift>.001f){
                shadow.alpha=(42*opacity*s.camera.lift).toInt()
                val shadowSave=canvas.save();canvas.translate(2*density*s.camera.lift,4*density*s.camera.lift);canvas.drawPath(path,shadow);canvas.restoreToCount(shadowSave)
            }
            val save=canvas.save();canvas.clipPath(path);canvas.concat(tile.matrix)
            paint.alpha=(255*opacity).toInt().coerceIn(0,255);canvas.drawBitmap(b,0f,0f,paint);canvas.restoreToCount(save)
        }
        paint.alpha=255
    }
    private fun drawFlat(canvas:Canvas,b:Bitmap,s:CompositionTileMotion){
        val crop=s.crop;val w=s.mesh.width;val h=s.mesh.height
        val scale=min(width/(w*crop.w.toFloat()),height/(h*crop.h.toFloat()))*.94f
        val dw=w*crop.w.toFloat()*scale;val dh=h*crop.h.toFloat()*scale
        val l=(width-dw)/2;val t=(height-dh)/2
        val full=RectF(l-(crop.l*w*scale).toFloat(),t-(crop.t*h*scale).toFloat(),l+((1-crop.l)*w*scale).toFloat(),t+((1-crop.t)*h*scale).toFloat())
        val save=canvas.save();canvas.clipRect(l,t,l+dw,t+dh);paint.alpha=255;canvas.drawBitmap(b,null,full,paint);canvas.restoreToCount(save)
    }
    private fun canOrbit()=!saving && motionEnabled() && scene?.canOrbit==true
    private fun releaseTouch(){pointer=-1;parent?.requestDisallowInterceptTouchEvent(false)}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!canOrbit()){releaseTouch();return false}
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{pointer=event.getPointerId(0);lastX=event.x;lastY=event.y;parent?.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{
                val i=event.findPointerIndex(pointer);if(i<0){releaseTouch();return false}
                val x=event.getX(i);val y=event.getY(i)
                scene?.drag((x-lastX)/width.coerceAtLeast(1)*1.7f,-(y-lastY)/height.coerceAtLeast(1)*1.1f)
                lastX=x;lastY=y;invalidate()
            }
            MotionEvent.ACTION_POINTER_UP->{
                if(event.getPointerId(event.actionIndex)==pointer){val next=if(event.actionIndex==0)1 else 0;pointer=event.getPointerId(next);lastX=event.getX(next);lastY=event.getY(next)}
            }
            MotionEvent.ACTION_UP->{releaseTouch();performClick()}
            MotionEvent.ACTION_CANCEL->releaseTouch()
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable=canOrbit()
        if(canOrbit()){
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
        }
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
        if(canOrbit()){
            val delta=when(action){AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id->-.18f;AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id->.18f;else->null}
            if(delta!=null){scene?.drag(delta,0f);invalidate();return true}
        }
        return super.performAccessibilityAction(action,args)
    }
}

class CompositionThumbnail(context:Context,private val bitmap:Bitmap,private val crop:Crop):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(canvas:Canvas){if(bitmap.isRecycled)return;val src=CompositionGeometry.pixels(crop,bitmap.width,bitmap.height);val scale=min(width.toFloat()/src.w,height.toFloat()/src.h);val w=src.w*scale;val h=src.h*scale;canvas.drawBitmap(bitmap,Rect(src.left,src.top,src.right,src.bottom),RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)}
}
