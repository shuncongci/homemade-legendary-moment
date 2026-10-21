package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.*

class DepthStageView(context:Context):View(context) {
    private var photo:Bitmap?=null
    private var map:DepthVisualMath.Tiles?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=resources.displayMetrics.density*.5f}
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xffb7b2a9.toInt();textSize=11*resources.displayMetrics.scaledDensity;typeface=Typeface.MONOSPACE}
    private class Tile(val index:Int,val source:RectF,val foreground:Boolean=false){val quad=FloatArray(8);val matrix=Matrix();var z=0f;val points=floatArrayOf(source.left,source.top,source.right,source.top,source.right,source.bottom,source.left,source.bottom)}
    private var tiles=emptyArray<Tile>();private var order=emptyArray<Tile>()
    private val projected=FloatArray(3);private val path=Path()
    private var loop:ValueAnimator?=null;private var transition:ValueAnimator?=null
    private var time=0f;private var yaw=.38f;private var pitch=-.14f
    private var cols=1;private var rows=1
    private var from=FloatArray(0);private var target=FloatArray(0);private var mix=1f
    private var motion=0f;private var coarse=false;private var completing=false
    private var loading=false;private var active=true;private var lastX=0f;private var lastY=0f;private var downX=0f;private var downY=0f;private var lastTap=0L
    init{isClickable=true;isFocusable=true;setLayerType(LAYER_TYPE_HARDWARE,null);contentDescription="相对深度分层视图。拖动旋转，双击回到侧视角。"}
    private var subject:SubjectDepthMotion.Data?=null
    private var subjectFront:Bitmap?=null;private var subjectBack:Bitmap?=null
    private var subjectShown=false;private var subjectWaiting=false;private var subjectMoving=false
    private var subjectMix=1f;private var subjectTransition:ValueAnimator?=null
    var onSubjectSettled:()->Unit = {}
    private fun releaseLayers(){subjectFront?.recycle();subjectBack?.recycle();subjectFront=null;subjectBack=null}
    private fun makeLayers(){
        releaseLayers();val b=photo?:return;val data=subject?:return
        val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
        subjectFront=Bitmap.createBitmap(SubjectDepthMotion.layerPixels(pixels,b.width,b.height,data.alpha,true),b.width,b.height,Bitmap.Config.ARGB_8888)
        subjectBack=Bitmap.createBitmap(SubjectDepthMotion.layerPixels(pixels,b.width,b.height,data.alpha,false),b.width,b.height,Bitmap.Config.ARGB_8888)
    }
    fun clearSubject(){subjectTransition?.cancel();subjectTransition=null;subjectMoving=false;subjectWaiting=false;subjectShown=false;subject=null;releaseLayers();rebuild();invalidate()}
    fun enableSubject(enabled:Boolean){
        if(!enabled){subjectTransition?.cancel();subjectMoving=false;subjectWaiting=false}
        subjectShown=enabled && subject!=null;subjectMix=1f;rebuild();invalidate()
    }
    fun beginSubject(){subjectWaiting=true;visibility=VISIBLE;alpha=1f;if(!loading)showRelief();invalidate()}
    fun endSubjectWait(){subjectWaiting=false;invalidate()}
    fun displaysSubject(data:SubjectDepthMotion.Data)=subjectShown && subject===data
    fun presentSubject(data:SubjectDepthMotion.Data,animate:Boolean){
        transition?.cancel();loading=false;completing=false;loop?.cancel();loop=null
        subjectTransition?.cancel();subjectWaiting=false
        if(subject!==data){subject=data;makeLayers()}
        subjectShown=true;rebuild();visibility=VISIBLE;alpha=1f;subjectMix=0f
        if(!animate || !active || !ValueAnimator.areAnimatorsEnabled()){subjectMix=1f;subjectMoving=false;invalidate();onSubjectSettled();return}
        subjectMoving=true
        subjectTransition=ValueAnimator.ofFloat(0f,1f).apply{
            duration=SubjectDepthMotion.DURATION_MS;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{subjectMix=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(a:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(a:android.animation.Animator){if(!cancelled){subjectMoving=false;subjectMix=1f;onSubjectSettled()}}
            });start()
        }
    }
    fun setPhoto(bitmap:Bitmap?){
        val sameSize=bitmap!=null && photo?.width==bitmap.width && photo?.height==bitmap.height
        photo=bitmap;if(subject!=null)makeLayers();if(!sameSize)rebuild();invalidate()
    }
    fun setDepth(data:DepthVisualMath.Data?){
        map=data?.tiles
        if(data==null){clearSubject();hide();coarse=false;yaw=.38f;pitch=-.14f;rebuild()}
        else if(!loading){rebuild();snapDepth()}
        invalidate()
    }
    fun setPreview(data:DepthVisualMath.Data){
        if(!loading || completing)return
        coarse=true;morph(data.tiles,1f,600)
    }
    private fun rebuild(){
        val b=photo ?: run{tiles=emptyArray();order=emptyArray();from=FloatArray(0);target=FloatArray(0);return}
        val grid=DepthVisualMath.grid(b.width,b.height,24);cols=grid.first;rows=grid.second
        tiles=Array(cols*rows*(if(subjectShown)2 else 1)){at->val i=at%(cols*rows);val x=i%cols;val y=i/cols;Tile(i,RectF(x*b.width.toFloat()/cols,y*b.height.toFloat()/rows,(x+1)*b.width.toFloat()/cols,(y+1)*b.height.toFloat()/rows),at>=cols*rows)};order=tiles.copyOf()
        from=FloatArray(tiles.size);target=FloatArray(tiles.size);mix=1f
        if(!loading)snapDepth()
    }
    private fun depths(data:DepthVisualMath.Tiles?)=FloatArray(tiles.size){i->((data?.let{ProgressiveDepth.tileDepth(it,i%(cols*rows),cols,rows)} ?: .5f)-.5f)*.95f}
    private fun snapDepth(){target=depths(map);from=target.copyOf();mix=1f;motion=0f}
    fun beginLoading(){
        transition?.cancel();animate().cancel();loading=true;coarse=false;completing=false;map=null;time=0f;motion=0f
        visibility=VISIBLE;alpha=1f;rebuild();updateLoop();invalidate()
    }
    val isLoading get()=loading || subjectWaiting || subjectMoving
    fun endLoading(done:()->Unit={}){hide();coarse=false;done()}
    fun finishLoading(done:()->Unit={}){
        if(!loading){done();return}
        val full=map ?: run{endLoading(done);return}
        completing=true
        morph(full,0f,900){loading=false;completing=false;coarse=false;loop?.cancel();loop=null;done()}
    }
    fun showRelief(){
        transition?.cancel();loading=false;completing=false;loop?.cancel();loop=null;snapDepth()
        val entering=visibility!=VISIBLE;visibility=VISIBLE
        if(entering && active && ValueAnimator.areAnimatorsEnabled()){alpha=0f;animate().alpha(1f).setDuration(300).start()}else alpha=1f
        invalidate()
    }
    fun hide(){subjectTransition?.cancel();subjectTransition=null;subjectMoving=false;subjectWaiting=false;subjectMix=1f;loading=false;completing=false;transition?.cancel();transition=null;loop?.cancel();loop=null;animate().cancel();visibility=GONE;parent?.requestDisallowInterceptTouchEvent(false)}
    fun setActive(value:Boolean){active=value;if(!value){transition?.end();subjectTransition?.end()};updateLoop()}
    fun close(){hide();clearSubject();photo=null;map=null;tiles=emptyArray();order=emptyArray();from=FloatArray(0);target=FloatArray(0)}
    private fun morph(data:DepthVisualMath.Tiles,amplitude:Float,ms:Long,done:()->Unit={}){
        val current=FloatArray(tiles.size){i->ProgressiveDepth.blend(from[i],target[i],mix)}
        val oldMotion=motion
        transition?.cancel();from=current;target=depths(data);mix=0f
        if(!active || !ValueAnimator.areAnimatorsEnabled()){mix=1f;motion=amplitude;invalidate();done();return}
        transition=ValueAnimator.ofFloat(0f,1f).apply{duration=ms;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{mix=it.animatedValue as Float;motion=ProgressiveDepth.blend(oldMotion,amplitude,mix);invalidate()};addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false;override fun onAnimationCancel(a:android.animation.Animator){cancelled=true};override fun onAnimationEnd(a:android.animation.Animator){if(!cancelled)done()}});start()}
    }
    private fun updateLoop(){
        if(!loading || !active || !isAttachedToWindow || visibility!=VISIBLE || !ValueAnimator.areAnimatorsEnabled()){loop?.cancel();loop=null;return}
        if(loop!=null)return
        loop=ValueAnimator.ofFloat(time,time+1f).apply{duration=12000;repeatCount=ValueAnimator.INFINITE;interpolator=android.view.animation.LinearInterpolator();addUpdateListener{time=it.animatedValue as Float;invalidate()};start()}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateLoop()}
    override fun onDetachedFromWindow(){transition?.end();subjectTransition?.end();loop?.cancel();loop=null;super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(v:Int){super.onWindowVisibilityChanged(v);if(v==VISIBLE)updateLoop() else {loop?.cancel();loop=null}}
    override fun onDraw(c:Canvas){
        val b=photo ?: return;if(b.isRecycled)return
        c.drawColor(0xff101112.toInt())
        val unit=min(width/(b.width.toFloat()/max(b.width,b.height)),height/(b.height.toFloat()/max(b.width,b.height)))*.78f
        val aspectX=b.width.toFloat()/max(b.width,b.height);val aspectY=b.height.toFloat()/max(b.width,b.height)
        val angle=yaw;val tilt=pitch
        for(tile in tiles){
            val data=subject?.takeIf{subjectShown}
            val z=if(data==null)ProgressiveDepth.blend(from[tile.index],target[tile.index],mix)+ProgressiveDepth.floatOffset(tile.index,time)*motion
                else if(tile.foreground)SubjectDepthMotion.movedZ(ProgressiveDepth.tileDepth(data.foreground,tile.index,cols,rows),data.focus,subjectMix)
                else SubjectDepthMotion.z(ProgressiveDepth.tileDepth(data.background,tile.index,cols,rows))
            val gap=.007f
            for(k in 0..3){
                val sx=tile.points[k*2]/b.width;val sy=tile.points[k*2+1]/b.height
                val cx=tile.source.centerX()/b.width;val cy=tile.source.centerY()/b.height
                val x=((sx-cx)*(1-gap)+cx-.5f)*aspectX;val y=((sy-cy)*(1-gap)+cy-.5f)*aspectY
                DepthVisualMath.project(x,y,z,angle,tilt,projected)
                tile.quad[2*k]=width/2f+projected[0]*unit;tile.quad[2*k+1]=height/2f+projected[1]*unit
                if(k==0)tile.z=projected[2]
            }
        }
        order.sortBy{it.z}
        for(t in order){
            path.rewind();path.moveTo(t.quad[0],t.quad[1]);for(k in 1..3)path.lineTo(t.quad[k*2],t.quad[k*2+1]);path.close()
            t.matrix.setPolyToPoly(t.points,0,t.quad,0,4)
            val texture=if(subjectShown)(if(t.foreground)subjectFront else subjectBack)?:b else b
            c.save();c.clipPath(path);c.concat(t.matrix);paint.alpha=255;c.drawBitmap(texture,0f,0f,paint);c.restore()
            if(!subjectShown || !t.foreground){line.color=if(loading)0x38e6dac5 else 0x24e6dac5;c.drawPath(path,line)}
        }
        if(subjectShown)subject?.let{data->
            path.rewind()
            for(k in 0..3){val x=(if(k==0 || k==3)-.5f else .5f)*aspectX;val y=(if(k<2)-.5f else .5f)*aspectY
                DepthVisualMath.project(x,y,SubjectDepthMotion.z(data.focus),angle,tilt,projected)
                val px=width/2f+projected[0]*unit;val py=height/2f+projected[1]*unit;if(k==0)path.moveTo(px,py)else path.lineTo(px,py)
            };path.close();line.color=0xc038c8f4.toInt();c.drawPath(path,line)
        }
        val d=resources.displayMetrics.density
        textPaint.color=0xffb7b2a9.toInt();c.drawText(when{subjectMoving->"主体保护 · 正在拉至焦平面";subjectWaiting->"主体识别与细化中 · 可拖动查看";subjectShown->"主体保护 · 蓝框为焦平面";completing->"完整深度 · 正在细化";loading && coarse->"粗深度预览 · 完整计算中";loading->"正在计算粗深度";else->"空间 · 相对深度"},16*d,25*d,textPaint)
        textPaint.textAlign=Paint.Align.RIGHT;c.drawText("拖动旋转 · 双击复位",width-16*d,height-16*d,textPaint);textPaint.textAlign=Paint.Align.LEFT
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y;downX=e.x;downY=e.y;parent?.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{yaw=(yaw+(e.x-lastX)/width.coerceAtLeast(1)*1.8f).coerceIn(-.85f,.85f);pitch=(pitch-(e.y-lastY)/height.coerceAtLeast(1)).coerceIn(-.55f,.55f);lastX=e.x;lastY=e.y;invalidate()}
            MotionEvent.ACTION_UP->{parent?.requestDisallowInterceptTouchEvent(false);if(hypot(e.x-downX,e.y-downY)<12*resources.displayMetrics.density){val now=SystemClock.uptimeMillis();if(now-lastTap<350){yaw=.38f;pitch=-.14f;invalidate()};lastTap=now;performClick()}}
            MotionEvent.ACTION_CANCEL->parent?.requestDisallowInterceptTouchEvent(false)
        };return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
