package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.*

class DepthStageView(context:Context):View(context) {
    private var photo:Bitmap?=null
    private var map:DepthVisualMath.Tiles?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=resources.displayMetrics.density*.5f}
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xffb7b2a9.toInt();textSize=11*resources.displayMetrics.scaledDensity;typeface=Typeface.MONOSPACE}
    private class Tile(val index:Int,val source:RectF,val foreground:Boolean=false){val quad=FloatArray(8);val matrix=Matrix();var z=0f;var subjectDepth=.5f;val points=floatArrayOf(source.left,source.top,source.right,source.top,source.right,source.bottom,source.left,source.bottom)}
    private var tiles=emptyArray<Tile>();private var order=emptyArray<Tile>()
    private val projected=FloatArray(3);private val path=Path()
    private val projector=DepthVisualMath.Projector()
    private val depthOrder=Comparator<Tile>{a,b->a.z.compareTo(b.z)}
    private var loop:ValueAnimator?=null;private var transition:ValueAnimator?=null
    private var time=0f
    private val camera=SpaceCamera()
    fun setGyroMode(value:Boolean){cancelGestures();camera.setGyroMode(value);postInvalidateOnAnimation()}
    private var mesh:SpaceMesh?=null
    private var meshZ=FloatArray(0)
    private var atlas:Bitmap?=null
    private val meshPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private var shaderBitmap:Bitmap?=null
    private val fastMesh get()=android.os.Build.VERSION.SDK_INT>=29
    private fun drawMesh(c:Canvas,b:Bitmap,data:SubjectDepthMotion.Data?,unit:Float,cx:Float,cy:Float):Boolean {
        if(!fastMesh || !c.isHardwareAccelerated)return false
        val m=mesh?:return false
        val texture=if(subjectShown)atlas?:return false else b
        for(t in meshZ.indices){val i=t%(cols*rows)
            meshZ[t]=if(data==null)ProgressiveDepth.blend(from[i],target[i],mix)+(if(motion==0f)0f else ProgressiveDepth.floatOffset(i,time)*motion)
                else if(t>=cols*rows)SubjectDepthMotion.movedZ(tiles[t].subjectDepth,data.focus,subjectMix)
                else SubjectDepthMotion.z(tiles[t].subjectDepth)
        }
        m.project(camera.displayYaw,camera.displayPitch,unit,cx,cy,meshZ)
        if(shaderBitmap!==texture){meshPaint.shader=BitmapShader(texture,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shaderBitmap=texture}
        c.drawVertices(Canvas.VertexMode.TRIANGLES,m.vertices.size,m.vertices,0,m.tex,0,null,0,m.indices,0,m.indices.size,meshPaint)
        line.color=if(loading)0x38e6dac5 else 0x24e6dac5
        // Subject alpha must occlude background grid: avoid an always-on-top wire overlay.
        // Gaps and the blue focus-plane outline still show tile separation in subject mode.
        if(!subjectShown)c.drawLines(m.lines,0,m.lineCount,line)
        return true
    }
    var onViewReset:()->Unit={}
    fun setParallax(yaw:Float,pitch:Float){
        val oldYaw=camera.displayYaw;val oldPitch=camera.displayPitch
        camera.setParallax(yaw,pitch)
        if(oldYaw!=camera.displayYaw || oldPitch!=camera.displayPitch)postInvalidateOnAnimation()
    }
    private val touchConfig=ViewConfiguration.get(context)
    private val touch=SpaceTouch(camera,touchConfig.scaledTouchSlop.toFloat(),touchConfig.scaledDoubleTapSlop.toFloat(),ViewConfiguration.getDoubleTapTimeout().toLong()){onViewReset()}
    val zoom get()=camera.zoom
    var onViewportChanged:(Float)->Unit = {}
    fun cancelGestures(){touch.cancel();parent?.requestDisallowInterceptTouchEvent(false)}
    fun resetView(){cancelGestures();camera.reset();onViewReset();onViewportChanged(zoom);invalidate()}
    fun zoomBy(factor:Float){cancelGestures();camera.zoomBy(factor);onViewportChanged(zoom);invalidate()}
    private var cols=1;private var rows=1
    private var from=FloatArray(0);private var target=FloatArray(0);private var mix=1f
    private var motion=0f;private var coarse=false;private var completing=false
    private var loading=false;private var active=true
    init{isClickable=true;isFocusable=true;contentDescription="相对深度空间视图。单指旋转，双指缩放和平移，双击恢复默认视角。"}
    private var subject:SubjectDepthMotion.Data?=null
    private var subjectFront:Bitmap?=null;private var subjectBack:Bitmap?=null
    private var subjectShown=false;private var subjectWaiting=false;private var subjectMoving=false
    private var subjectMix=1f;private var subjectTransition:ValueAnimator?=null
    var onSubjectSettled:()->Unit = {}
    private fun releaseLayers(){
        meshPaint.shader=null;shaderBitmap=null;atlas?.recycle();atlas=null
        subjectFront?.recycle();subjectBack?.recycle();subjectFront=null;subjectBack=null
    }
    private fun makeLayers(){
        releaseLayers();val b=photo?:return;val data=subject?:return
        val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
        if(fastMesh){
            // Same total persistent bytes as the former two layers, one shader/one draw call.
            atlas=Bitmap.createBitmap(b.width,b.height*2,Bitmap.Config.ARGB_8888).also{a->
                a.setPixels(SubjectDepthMotion.layerPixels(pixels,b.width,b.height,data.alpha,false),0,b.width,0,0,b.width,b.height)
                a.setPixels(SubjectDepthMotion.layerPixels(pixels,b.width,b.height,data.alpha,true),0,b.width,0,b.height,b.width,b.height)
            }
        }else{
            subjectFront=Bitmap.createBitmap(SubjectDepthMotion.layerPixels(pixels,b.width,b.height,data.alpha,true),b.width,b.height,Bitmap.Config.ARGB_8888)
            subjectBack=Bitmap.createBitmap(SubjectDepthMotion.layerPixels(pixels,b.width,b.height,data.alpha,false),b.width,b.height,Bitmap.Config.ARGB_8888)
        }
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
        if(data==null){clearSubject();hide();coarse=false;resetView();rebuild()}
        else if(!loading){rebuild();snapDepth()}
        invalidate()
    }
    fun setPreview(data:DepthVisualMath.Data){
        if(!loading || completing)return
        coarse=true;morph(data.tiles,1f,600)
    }
    private fun rebuild(){
        val b=photo ?: run{tiles=emptyArray();order=emptyArray();from=FloatArray(0);target=FloatArray(0);return}
        val grid=DepthVisualMath.spatialGrid(b.width,b.height);cols=grid.first;rows=grid.second
        tiles=Array(cols*rows*(if(subjectShown)2 else 1)){at->val i=at%(cols*rows);val x=i%cols;val y=i/cols;Tile(i,RectF(x*b.width.toFloat()/cols,y*b.height.toFloat()/rows,(x+1)*b.width.toFloat()/cols,(y+1)*b.height.toFloat()/rows),at>=cols*rows)};order=tiles.copyOf()
        // Subject maps change only on rebuild, not on drag/animation frames.
        subject?.takeIf{subjectShown}?.let{data->for(tile in tiles){
            val layer=if(tile.foreground)data.foreground else data.background
            tile.subjectDepth=ProgressiveDepth.tileDepth(layer,tile.index,cols,rows)
        }}
        mesh=if(fastMesh)SpaceMesh(b.width,b.height,cols,rows,if(subjectShown)2 else 1) else null
        meshZ=FloatArray(tiles.size)
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
    fun hide(){cancelGestures();subjectTransition?.cancel();subjectTransition=null;subjectMoving=false;subjectWaiting=false;subjectMix=1f;loading=false;completing=false;transition?.cancel();transition=null;loop?.cancel();loop=null;animate().cancel();visibility=GONE;parent?.requestDisallowInterceptTouchEvent(false)}
    fun setActive(value:Boolean){active=value;if(!value){cancelGestures();transition?.end();subjectTransition?.end()};updateLoop()}
    fun close(){hide();clearSubject();photo=null;map=null;tiles=emptyArray();order=emptyArray();from=FloatArray(0);target=FloatArray(0);mesh=null;meshZ=FloatArray(0);meshPaint.shader=null;shaderBitmap=null}
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
    private var transferring=false
    internal fun rehost(parent:ViewGroup,params:ViewGroup.LayoutParams,index:Int=-1){
        cancelGestures();animate().cancel();alpha=1f;transferring=true
        try{(this.parent as? ViewGroup)?.removeView(this);parent.addView(this,index,params)}finally{transferring=false}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateLoop()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);cancelGestures()}
    override fun onDetachedFromWindow(){cancelGestures();if(!transferring){transition?.end();subjectTransition?.end();loop?.cancel();loop=null};super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(v:Int){super.onWindowVisibilityChanged(v);if(v==VISIBLE)updateLoop() else {loop?.cancel();loop=null}}
    override fun onDraw(c:Canvas){
        val b=photo ?: return;if(b.isRecycled)return
        c.drawColor(0xff101112.toInt())
        val unit=min(width/(b.width.toFloat()/max(b.width,b.height)),height/(b.height.toFloat()/max(b.width,b.height)))*.78f*camera.zoom
        val centerX=width*(.5f+camera.panX);val centerY=height*(.5f+camera.panY)
        val aspectX=b.width.toFloat()/max(b.width,b.height);val aspectY=b.height.toFloat()/max(b.width,b.height)
        projector.setAngles(camera.displayYaw,camera.displayPitch)
        val data=subject?.takeIf{subjectShown}
        if(!drawMesh(c,b,data,unit,centerX,centerY)){
        for(tile in tiles){
            val z=if(data==null)ProgressiveDepth.blend(from[tile.index],target[tile.index],mix)+ProgressiveDepth.floatOffset(tile.index,time)*motion
                else if(tile.foreground)SubjectDepthMotion.movedZ(tile.subjectDepth,data.focus,subjectMix)
                else SubjectDepthMotion.z(tile.subjectDepth)
            val gap=.007f
            for(k in 0..3){
                val sx=tile.points[k*2]/b.width;val sy=tile.points[k*2+1]/b.height
                val cx=tile.source.centerX()/b.width;val cy=tile.source.centerY()/b.height
                val x=((sx-cx)*(1-gap)+cx-.5f)*aspectX;val y=((sy-cy)*(1-gap)+cy-.5f)*aspectY
                projector.project(x,y,z,projected)
                tile.quad[2*k]=centerX+projected[0]*unit;tile.quad[2*k+1]=centerY+projected[1]*unit
                if(k==0)tile.z=projected[2]
            }
        }
        order.sortWith(depthOrder)
        for(t in order){
            path.rewind();path.moveTo(t.quad[0],t.quad[1]);for(k in 1..3)path.lineTo(t.quad[k*2],t.quad[k*2+1]);path.close()
            val sourcePoints=if(subjectShown && atlas!=null && t.foreground)t.points.copyOf().also{for(i in 0..3)it[i*2+1]+=b.height} else t.points
            t.matrix.setPolyToPoly(sourcePoints,0,t.quad,0,4)
            val texture=if(subjectShown)atlas?:(if(t.foreground)subjectFront else subjectBack)?:b else b
            c.save();c.clipPath(path);c.concat(t.matrix);paint.alpha=255;c.drawBitmap(texture,0f,0f,paint);c.restore()
            if(!subjectShown || !t.foreground){line.color=if(loading)0x38e6dac5 else 0x24e6dac5;c.drawPath(path,line)}
        }
        }
        if(subjectShown)subject?.let{data->
            path.rewind()
            for(k in 0..3){val x=(if(k==0 || k==3)-.5f else .5f)*aspectX;val y=(if(k<2)-.5f else .5f)*aspectY
                projector.project(x,y,SubjectDepthMotion.z(data.focus),projected)
                val px=centerX+projected[0]*unit;val py=centerY+projected[1]*unit;if(k==0)path.moveTo(px,py)else path.lineTo(px,py)
            };path.close();line.color=0xc038c8f4.toInt();c.drawPath(path,line)
        }
        val d=resources.displayMetrics.density
        textPaint.color=0xffb7b2a9.toInt();c.drawText(when{subjectMoving->"主体保护 · 正在拉至焦平面";subjectWaiting->"主体识别与细化中 · 可拖动查看";subjectShown->"主体保护 · 蓝框为焦平面";completing->"完整深度 · 正在细化";loading && coarse->"粗深度预览 · 完整计算中";loading->"正在计算粗深度";else->"空间 · 相对深度"},16*d,25*d,textPaint)
        textPaint.textAlign=Paint.Align.RIGHT;c.drawText("双指缩放/平移 · 双击复位",width-16*d,height-16*d,textPaint);textPaint.textAlign=Paint.Align.LEFT
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(photo==null)return false
        fun finger(i:Int)=SpaceTouch.Finger(e.getPointerId(i),e.getX(i),e.getY(i))
        fun fingers(skip:Int=-1)=(0 until e.pointerCount).filter{it!=skip}.map(::finger)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{touch.down(finger(0),e.eventTime);parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_POINTER_DOWN->touch.rebase(fingers())
            MotionEvent.ACTION_POINTER_UP->touch.rebase(fingers(e.actionIndex))
            MotionEvent.ACTION_MOVE->touch.move(fingers(),width,height)
            MotionEvent.ACTION_UP->{if(touch.up(finger(0),e.eventTime))performClick();parent?.requestDisallowInterceptTouchEvent(false)}
            MotionEvent.ACTION_CANCEL->cancelGestures()
        }
        onViewportChanged(zoom);invalidate();return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
