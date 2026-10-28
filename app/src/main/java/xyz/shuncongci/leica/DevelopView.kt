package xyz.shuncongci.leica

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/** Owned bitmap transfer: receiver must accept or dispose, never retain a borrowed worker image. */
sealed class DevelopEvent {
    data class ColorStart(val blur:Boolean):DevelopEvent()
    data class ColorReady(val bitmap:Bitmap):DevelopEvent()
    data class BokehStart(val focus:LegendaryMath.Point,val tiles:DevelopMath.FocusTiles):DevelopEvent()
    data class BokehPass(val sequence:Int,val bitmap:Bitmap):DevelopEvent()
    data class RegionsStart(val width:Int,val height:Int,val split:Boolean,val bitmap:Bitmap,val focus:LegendaryMath.Point,val tiles:DevelopMath.FocusTiles):DevelopEvent()
    data class LayerProgress(val progress:BokehLayerProgress):DevelopEvent()
    data class RegionReady(val patch:BokehPreview.Patch):DevelopEvent()
    fun dispose(){val b=when(this){is ColorReady->bitmap;is BokehPass->bitmap;is RegionsStart->bitmap;else->null};if(b!=null && !b.isRecycled)b.recycle()}
}

/** Preview-only pigment, gaseous color transport and focus shockwave. Never used by export. */
class DevelopView(context:Context):View(context) {
    val motion=DevelopMotion()
    private val tileReveal=BokehTileReveal()
    private var regions:BokehRegionReveal?=null;private var regionPhoto:Bitmap?=null
    private var layerReveal:BokehLayerReveal?=null
    private val regionPaint=Paint()
    private var passPhoto:Bitmap?=null;private var previousPass:Bitmap?=null
    private var source:Bitmap?=null;private var colored:Bitmap?=null;private var bokehSource:Bitmap?=null;private var finalPhoto:Bitmap?=null
    var onPresentationChanged:(()->Unit)?=null
    private var announcedStage=DevelopMotion.Stage.IDLE
    private var focus=LegendaryMath.Point(.5f,.5f);private var focusTiles:DevelopMath.FocusTiles?=null
    private var loop:ValueAnimator?=null;private val frameClock=DevelopFrameClock();private var active=false
    private var finalSettle:ValueAnimator?=null;private var finalAmount=0f;private var finalFromFlat=false
    private var finalCrop=CompositionGeometry.FULL
    private val completion=DevelopCompletion()
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val grayPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})}
    private val maskPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    private val glow=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN)}
    private val mask=Bitmap.createBitmap(88,88,Bitmap.Config.ARGB_8888);private val alphaPixels=IntArray(88*88)
    private var maskTime=Float.NEGATIVE_INFINITY;private var maskStage=DevelopMotion.Stage.IDLE
    private val rect=RectF();private val path=Path();private val matrix=Matrix();private val projected=FloatArray(3)
    private val quad=FloatArray(8);private val originalQuad=FloatArray(8);private val palette=IntArray(16){Color.WHITE}
    private val sourcePalette=IntArray(16){Color.WHITE}
    private val veilSample=FloatArray(4)
    // Source-over blending preserves the sampled color rather than bleaching overlap to white.
    private val veilPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tileTarget=RectF();private val vaporRect=RectF()
    private val vaporPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN)}
    private val sidePaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseQuad=FloatArray(8)
    private var tileColors=IntArray(0);private var tileDepth=FloatArray(0);private var tileOrder=emptyArray<Int>()
    private val tileComparator=Comparator<Int>{a,b->tileDepth[a].compareTo(tileDepth[b])}
    private val colorFilters=Array(16){PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN)}
    private val oldFilters=Array(16){PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN)}
    private fun fogTexture(ring:Boolean):Bitmap {
        val n=if(ring)256 else 64;val pixels=IntArray(n*n)
        for(y in 0 until n)for(x in 0 until n){val r=hypot((x+.5f-n/2)/(n/2),(y+.5f-n/2)/(n/2));val a=if(ring)exp(-((r-.87f)/.075f).pow(2))*(1-DevelopMath.phase(r,.96f,1f))else (1-r).coerceIn(0f,1f).pow(2);pixels[y*n+x]=((a*255).toInt() shl 24) or 0xffffff}
        return Bitmap.createBitmap(pixels,n,n,Bitmap.Config.ARGB_8888)
    }
    private val vaporTexture=fogTexture(false);private val waveTexture=fogTexture(true)
    private var closed=false
    init{visibility=GONE;isClickable=true;contentDescription="正在生成照片，完成后显示成片。"}
    val presenting get()=motion.visible || EditorPresentation.holdFinal(motion.stage,finalPhoto!=null)
    val hdrContent get()=if(motion.showsBokeh){finalPhoto?.let{HdrPreview.hasGainmap(it)}?:false}else HdrPreview.hasGainmap(source) || HdrPreview.hasGainmap(finalPhoto)
    private fun animated()=active && isAttachedToWindow && windowVisibility==VISIBLE && ValueAnimator.areAnimatorsEnabled()
    fun beginSource(bitmap:Bitmap){
        check(!closed);reset();source=HdrPreview.copy(bitmap);samplePalette(source!!,sourcePalette);sourcePalette.copyInto(palette);for(i in 0..15){oldFilters[i]=PorterDuffColorFilter(sourcePalette[i],PorterDuff.Mode.SRC_IN);colorFilters[i]=oldFilters[i]}
    }
    private fun samplePalette(b:Bitmap,destination:IntArray=palette){for(i in destination.indices){val x=((.12f+.76f*((i*7)%16)/15)*b.width).toInt().coerceIn(0,b.width-1);val y=((.12f+.76f*((i*11)%16)/15)*b.height).toInt().coerceIn(0,b.height-1);destination[i]=b.getPixel(x,y) or (255 shl 24)}}
    private fun startBokehSurface(point:LegendaryMath.Point,t:DevelopMath.FocusTiles){
        focus=point;focusTiles=t;val b=bokehSource?:colored?:checkNotNull(source)
        tileColors=IntArray(t.values.size){i->val color=b.getPixel(((i%t.cols+.5f)/t.cols*b.width).toInt().coerceAtMost(b.width-1),((i/t.cols+.5f)/t.rows*b.height).toInt().coerceAtMost(b.height-1));Color.rgb((Color.red(color)*.72f).toInt(),(Color.green(color)*.72f).toInt(),(Color.blue(color)*.72f).toInt())}
        tileDepth=FloatArray(t.values.size);tileOrder=Array(t.values.size){it};motion.startBokeh()
    }
    fun accept(event:DevelopEvent){
        if(closed || source==null){event.dispose();return}
        val before=motion.stage
        when(event){
            is DevelopEvent.RegionsStart->{
                regions=BokehRegionReveal(event.width,event.height,event.split)
                regionPhoto?.recycle();val (w,h)=regions!!.previewSize
                regionPhoto=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                // This bounded SDR base must not replace/recycle the still-playing color bitmap.
                bokehSource?.recycle();bokehSource=event.bitmap
                layerReveal=BokehLayerReveal(event.width,event.height,event.split)
                require(event.tiles.cols==layerReveal!!.cols && event.tiles.rows==layerReveal!!.rows)
                startBokehSurface(event.focus,event.tiles);motion.bokehPassCompleted()
                contentDescription="虚化进度：每块16格，通常每完成4层点亮1格。"
            }
            is DevelopEvent.LayerProgress->{
                val p=event.progress
                if(layerReveal?.accept(p)==true)contentDescription="基础虚化 ${p.part}/${p.parts} · ${p.done}/${p.layers}层 · ${p.cells}/16格；随后全局高光合成。"
            }
            is DevelopEvent.RegionReady->{
                val state=regions?:return;val p=event.patch
                if(state.accept(p)){
                    layerReveal?.partCompleted(p.index)
                    if(p.rect.w>0 && p.rect.h>0)regionPhoto?.setPixels(p.pixels,0,p.rect.w,p.rect.x,p.rect.y,p.rect.w,p.rect.h)
                    contentDescription="基础虚化完成${state.done}/${state.cores.size}；高光与主体边缘仍将全局合成。"
                }
            }
            is DevelopEvent.ColorStart->motion.startColor(event.blur)
            is DevelopEvent.ColorReady->{colored?.recycle();colored=event.bitmap;samplePalette(event.bitmap);for(i in 0..15)colorFilters[i]=PorterDuffColorFilter(palette[i],PorterDuff.Mode.SRC_IN);motion.colorCompleted()}
            is DevelopEvent.BokehPass->{
                if(focusTiles==null){event.dispose();return}
                if(tileReveal.accept(event.sequence)){
                    previousPass?.recycle();previousPass=passPhoto;passPhoto=event.bitmap;motion.bokehPassCompleted()
                    contentDescription="虚化预览已更新${event.sequence}次，高光仍在计算。"
                }else event.dispose()
            }
            is DevelopEvent.BokehStart->{
                startBokehSurface(event.focus,event.tiles)
            }
        };if(motion.stage!=before)frameClock.reset();sync()
    }
    fun finish(bitmap:Bitmap,rendered:Boolean,crop:CompositionGeometry.Crop=CompositionGeometry.FULL,done:(Boolean)->Unit){
        if(closed){done(false);return};if(source==null){done(true);return}
        check(!completion.pending)
        finalPhoto?.recycle();finalPhoto=HdrPreview.copy(bitmap);finalCrop=crop;finalAmount=0f;finalFromFlat=focusTiles==null;completion.await(done)
        val before=motion.stage;motion.resultCompleted(rendered);if(motion.stage!=before)frameClock.reset();sync()
    }
    fun setActive(value:Boolean){active=value;if(!value){stop();motion.reduceMotion()};sync()}
    private fun stop(){val old=loop;loop=null;old?.cancel();frameClock.reset()}
    private fun needsFrames()=motion.needsFrames || (motion.showsBokeh && (tileReveal.active || layerReveal?.needsFrames==true))
    private fun announceStage(){if(announcedStage!=motion.stage){announcedStage=motion.stage;onPresentationChanged?.invoke()}}
    private fun sync(){
        if(closed)return
        if(!animated()){motion.reduceMotion();tileReveal.settle();regions?.settle();layerReveal?.settle()}
        announceStage()
        visibility=if(presenting)VISIBLE else GONE
        if(motion.stage==DevelopMotion.Stage.DONE){stop();settleFinal();return}
        if(!animated() || !needsFrames()){stop();invalidate();return}
        if(loop==null){
            frameClock.reset()
            val animator=ValueAnimator.ofFloat(0f,1f).apply{
                duration=10000;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
                addUpdateListener{a->
                    if(loop!==a)return@addUpdateListener
                    if(!animated()){stop();motion.reduceMotion();sync();return@addUpdateListener}
                    // Animator play time honors the system's non-zero duration scale; do not discard jank time.
                    val dt=frameClock.tick(a.currentPlayTime)
                    motion.advance(dt);tileReveal.advance(dt);regions?.advance(dt);layerReveal?.advance(dt);announceStage()
                    if(!needsFrames() || motion.stage==DevelopMotion.Stage.DONE)sync()else invalidate()
                }
            }
            loop=animator;animator.start()
        };invalidate()
    }
    private fun settleFinal(){
        if(!animated()){
            val old=finalSettle;finalSettle=null;old?.cancel();finalAmount=1f;invalidate();completion.resolve(true);return
        }
        if(!completion.pending || finalSettle!=null)return
        finalSettle=ValueAnimator.ofFloat(finalAmount,1f).apply{
            duration=220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{finalAmount=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(a:android.animation.Animator){
                if(finalSettle===a){finalSettle=null;finalAmount=1f;invalidate();completion.resolve(true)}
            }})
            start()
        }
    }
    fun reset(){val old=finalSettle;finalSettle=null;old?.cancel();finalAmount=0f;finalFromFlat=false;stop();motion.reset();tileReveal.reset();regions=null;layerReveal=null;regionPhoto?.recycle();regionPhoto=null;passPhoto?.recycle();previousPass?.recycle();passPhoto=null;previousPass=null;source?.recycle();colored?.recycle();bokehSource?.recycle();finalPhoto?.recycle();source=null;colored=null;bokehSource=null;finalPhoto=null;focusTiles=null;tileColors=IntArray(0);tileDepth=FloatArray(0);tileOrder=emptyArray();maskTime=Float.NEGATIVE_INFINITY;visibility=GONE;completion.resolve(false)}
    fun skipPresentation(){reset()}
    fun close(){if(closed)return;closed=true;reset();mask.recycle();vaporTexture.recycle();waveTexture.recycle()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();sync()}
    override fun onDetachedFromWindow(){stop();motion.reduceMotion();val old=finalSettle;finalSettle=null;old?.cancel();if(motion.stage==DevelopMotion.Stage.DONE){finalAmount=1f;completion.resolve(true)};super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(v:Int){super.onWindowVisibilityChanged(v);if(v!=VISIBLE)stop();sync()}
    override fun performClick():Boolean{super.performClick();return true}
    private fun fit(b:Bitmap,scale:Float=1f,offset:Float=0f){
        val s=min(width.toFloat()/b.width,height.toFloat()/b.height)*.94f*scale
        val w=b.width*s;val h=b.height*s;val cx=width*(.5f+offset)
        rect.set(cx-w/2,(height-h)/2,cx+w/2,(height+h)/2)
    }
    private fun masked(canvas:Canvas,b:Bitmap,reveal:Boolean){
        if(maskStage!=motion.stage || abs(motion.seconds-maskTime)>=.026f){
            for(y in 0 until 88)for(x in 0 until 88){
                val a=if(reveal)DevelopMath.revealed((x+.5f)/88,(y+.5f)/88,motion.progress)else DevelopMath.pigment((x+.5f)/88,(y+.5f)/88,motion.seconds)
                alphaPixels[y*88+x]=((a*255).roundToInt().coerceIn(0,255) shl 24) or 0xffffff
            };mask.setPixels(alphaPixels,0,88,0,0,88,88);maskTime=motion.seconds;maskStage=motion.stage
        }
        val save=canvas.saveLayer(rect,null);imagePaint.alpha=255;canvas.drawBitmap(b,null,rect,imagePaint);canvas.drawBitmap(mask,null,rect,maskPaint);canvas.restoreToCount(save)
    }
    override fun onDraw(canvas:Canvas){
        val src=source?:return;if(src.isRecycled || width<=0 || height<=0)return
        canvas.drawColor(0xff0d0e0f.toInt());imagePaint.alpha=255
        if(EditorPresentation.holdFinal(motion.stage,finalPhoto!=null)){
            val final=checkNotNull(finalPhoto)
            val r=EditorPresentation.finalRect(width,height,final.width,final.height,finalCrop,finalAmount)
            val clip=EditorPresentation.finalClip(r,finalCrop,finalAmount)
            val save=canvas.save();canvas.clipRect(clip.left,clip.top,clip.right,clip.bottom)
            rect.set(r.left,r.top,r.right,r.bottom)
            if(finalFromFlat && finalAmount<1f){canvas.drawBitmap(colored?:src,null,rect,imagePaint);imagePaint.alpha=(255*DevelopMath.ease(finalAmount)).roundToInt()}
            canvas.drawBitmap(final,null,rect,imagePaint);imagePaint.alpha=255;canvas.restoreToCount(save);return
        }
        if(!ValueAnimator.areAnimatorsEnabled() && !motion.showsBokeh) {fit(src);canvas.drawBitmap(finalPhoto?:passPhoto?:colored?:src,null,rect,imagePaint);return}
        when(motion.stage){
            DevelopMotion.Stage.COLOR_WAIT->{fit(src);canvas.drawBitmap(src,null,rect,grayPaint);masked(canvas,src,false)}
            DevelopMotion.Stage.COLOR_FINISH->drawColorFinish(canvas,src)
            DevelopMotion.Stage.BOKEH_WAIT,DevelopMotion.Stage.BOKEH_FINISH->drawBokeh(canvas,src)
            else->{fit(src);canvas.drawBitmap(colored?:src,null,rect,imagePaint)}
        }
        imagePaint.alpha=255;glow.shader=null
    }
    private fun vapor(canvas:Canvas,x:Float,y:Float,r:Float,alpha:Float,filter:PorterDuffColorFilter?){
        if(r<=0 || alpha<=0)return
        vaporPaint.colorFilter=filter;vaporPaint.alpha=(255*alpha).toInt().coerceIn(0,255)
        vaporRect.set(x-r,y-r*.8f,x+r,y+r*.8f);canvas.drawBitmap(vaporTexture,null,vaporRect,vaporPaint)
    }
    private fun stroke(canvas:Canvas,color:Int,alpha:Float,width:Float){
        if(alpha<=0 || width<=0)return
        glow.color=color;glow.alpha=(255*alpha).toInt().coerceIn(0,255)
        glow.strokeWidth=width*3.5f;glow.alpha=(75*alpha).toInt().coerceIn(0,255);canvas.drawPath(path,glow)
        glow.strokeWidth=width*1.7f;glow.alpha=(120*alpha).toInt().coerceIn(0,255);canvas.drawPath(path,glow)
        glow.strokeWidth=width;glow.alpha=(235*alpha).toInt().coerceIn(0,255);canvas.drawPath(path,glow)
    }
    private fun drawColorVeil(canvas:Canvas,p:Float){
        if(p<=.36f || p>=.81f)return
        val shortEdge=min(rect.width(),rect.height());val aspect=rect.width()/rect.height()
        for(k in 0..3){
            veilPaint.colorFilter=colorFilters[k*4]
            for(j in 0..23){
                DevelopMath.colorVeilSample(k/3f,j/23f,p,aspect,veilSample)
                val x=rect.left+veilSample[0]*rect.width();val y=rect.top+veilSample[1]*rect.height();val r=shortEdge*veilSample[2]
                veilPaint.alpha=(255*veilSample[3]).roundToInt().coerceIn(0,255)
                vaporRect.set(x-r,y-r*.8f,x+r,y+r*.8f);canvas.drawBitmap(vaporTexture,null,vaporRect,veilPaint)
            }
        }
    }
    private fun drawColorFinish(canvas:Canvas,src:Bitmap){
        val p=motion.progress;fit(src,DevelopMath.colorScale(p),DevelopMath.colorOffset(p))
        canvas.drawBitmap(src,null,rect,grayPaint);imagePaint.alpha=((1-DevelopMath.stripped(p))*255).toInt();canvas.drawBitmap(src,null,rect,imagePaint);imagePaint.alpha=255
        colored?.let{masked(canvas,it,true)}
        // Old pigments unwind along helical ribbons, leaving the actual source behind.
        val strip=DevelopMath.phase(p,.16f,.50f);val stripAlpha=sin(PI*strip).toFloat().coerceAtLeast(0f)
        if(stripAlpha>0)for(k in 0..17){
            val seed=k/18f;val sx=rect.left+rect.width()*(.12f+.76f*((k*7)%18)/17);val sy=rect.top+rect.height()*(.08f+.84f*seed)
            path.rewind();for(j in 0..32){
                DevelopMath.stripPoint(seed,j/32f,p,sx/width,sy/height,projected)
                val x=projected[0]*width;val y=projected[1]*height
                if(j%2==0)vapor(canvas,x,y,width*(.038f+.025f*sin(j*.4f+k+strip*5).pow(2)),stripAlpha*.21f,oldFilters[k%16])
                if(j==0)path.moveTo(x,y)else path.lineTo(x,y)
            };stroke(canvas,sourcePalette[k%16],stripAlpha*.12f,rect.width()*(.002f+.003f*(k%3)))
        }
        drawColorVeil(canvas,p)
    }
    private fun drawBokeh(canvas:Canvas,src:Bitmap){
        val waiting=motion.stage==DevelopMotion.Stage.BOKEH_WAIT;val p=motion.progress
        val tiles=focusTiles?:return
        val base=bokehSource?:colored?:src;val aspect=src.width.toFloat()/src.height
        val lift=if(layerReveal!=null && waiting && !ValueAnimator.areAnimatorsEnabled()).78f else motion.bokehLift
        val scale=min(width/min(1f,aspect),height/min(1f,1/aspect))*(.94f-.15f*lift)
        val target=if(!waiting)finalPhoto else null
        val detail=if(waiting && !ValueAnimator.areAnimatorsEnabled())1f else motion.bokehDetail
        val fade=if(waiting)0f else DevelopMath.ease(p)
        tileTarget.set(0f,0f,base.width.toFloat(),base.height.toFloat())
        // Stable far-to-near ordering and side faces, not black cutout holes.
        for(i in tileOrder){DevelopMath.project((i%tiles.cols+.5f)/tiles.cols,(i/tiles.cols+.5f)/tiles.rows,tiles.values[i],lift,aspect,projected);tileDepth[i]=projected[2]}
        tileOrder.sortWith(tileComparator)
        for(i in tileOrder){
            val x=i%tiles.cols;val y=i/tiles.cols;val f=tiles.values[i]
            val part=layerReveal?.let{if(it.isLit(i))1f else 0f}?:tileReveal.progress(i,tiles.cols,tiles.rows,focus)
            val localLift=lift*(1-.12f*sin(PI*part).toFloat())
            for(k in 0..3){
                val u=(x+(if(k==0 || k==3)0f else 1f))/tiles.cols;val v=(y+(if(k<2)0f else 1f))/tiles.rows
                val cx=(x+.5f)/tiles.cols;val cy=(y+.5f)/tiles.rows
                DevelopMath.project(cx+(u-cx)*(1-.032f*localLift),cy+(v-cy)*(1-.032f*localLift),f,localLift,aspect,projected)
                quad[k*2]=width/2f+projected[0]*scale;quad[k*2+1]=height/2f+projected[1]*scale
                originalQuad[k*2]=u*base.width;originalQuad[k*2+1]=v*base.height
                DevelopMath.project(cx+(u-cx)*(1-.032f*localLift),cy+(v-cy)*(1-.032f*localLift),0f,localLift,aspect,projected)
                baseQuad[k*2]=width/2f+projected[0]*scale;baseQuad[k*2+1]=height/2f+projected[1]*scale
            }
            if(f>.01f && lift>.001f){
                sidePaint.color=tileColors[i];sidePaint.alpha=(255*(lift/.15f).coerceIn(0f,1f)).toInt()
                for(k in 0..3){if(!DevelopMath.visibleSide(baseQuad,quad,k))continue;val next=(k+1)%4;path.rewind();path.moveTo(baseQuad[k*2],baseQuad[k*2+1]);path.lineTo(baseQuad[next*2],baseQuad[next*2+1]);path.lineTo(quad[next*2],quad[next*2+1]);path.lineTo(quad[k*2],quad[k*2+1]);path.close();canvas.drawPath(path,sidePaint)}
            }
            path.rewind();path.moveTo(quad[0],quad[1]);for(k in 1..3)path.lineTo(quad[k*2],quad[k*2+1]);path.close()
            if(!matrix.setPolyToPoly(originalQuad,0,quad,0,4))continue
            val save=canvas.save();canvas.clipPath(path);canvas.concat(matrix);imagePaint.alpha=255;canvas.drawBitmap(previousPass?:base,null,tileTarget,imagePaint)
            val layerState=layerReveal
            if(layerState!=null){
                if(layerState.hasBase(i))regionPhoto?.let{imagePaint.alpha=(255*detail).roundToInt();canvas.drawBitmap(it,null,tileTarget,imagePaint)}
                regionPaint.color=if(layerState.isLit(i))Color.WHITE else Color.BLACK
                regionPaint.alpha=(255*(if(layerState.isLit(i))layerState.glow(i)else layerState.dark(i))*(1-fade)*detail).roundToInt().coerceIn(0,255)
                canvas.drawRect(tileTarget,regionPaint)
            }else passPhoto?.let{imagePaint.alpha=(255*detail*tileReveal.opacity(i,tiles.cols,tiles.rows,focus)).toInt();canvas.drawBitmap(it,null,tileTarget,imagePaint)}
            if(target!=null){imagePaint.alpha=(255*fade).toInt();canvas.drawBitmap(target,null,tileTarget,imagePaint)}
            canvas.restoreToCount(save)
            // A small white pressure ring follows EACH newly assembled display tile, during computation.
            val energy=if(layerReveal!=null)0f else tileReveal.pulse(i,tiles.cols,tiles.rows,focus)
            if(energy>0f){
                val cx=(quad[0]+quad[2]+quad[4]+quad[6])*.25f;val cy=(quad[1]+quad[3]+quad[5]+quad[7])*.25f
                val radius=hypot(quad[4]-quad[0],quad[5]-quad[1])*(.18f+part*.8f)
                vaporPaint.colorFilter=null;vaporPaint.alpha=(90*energy).toInt()
                vaporRect.set(cx-radius,cy-radius*.8f,cx+radius,cy+radius*.8f);canvas.drawBitmap(waveTexture,null,vaporRect,vaporPaint)
            }
        }
    }
}
