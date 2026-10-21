package xyz.shuncongci.leica

import java.util.Locale
import kotlin.math.*

/** Shared, bounded viewport for original/result; independent of source pixel dimensions. */
class PreviewViewport {
    var zoom=1f; private set
    var panX=0f; private set
    var panY=0f; private set
    private var viewW=0f
    private var viewH=0f
    private var fittedW=0f
    private var fittedH=0f
    data class Bounds(val left:Float,val top:Float,val right:Float,val bottom:Float) {
        val w get()=right-left
        val h get()=bottom-top
    }
    fun layout(viewWidth:Float,viewHeight:Float,imageWidth:Int,imageHeight:Int): Bounds {
        viewW=max(0f,viewWidth);viewH=max(0f,viewHeight)
        if(imageWidth<=0 || imageHeight<=0 || viewW==0f || viewH==0f){fittedW=0f;fittedH=0f;return bounds()}
        val fit=min(viewW/imageWidth,viewH/imageHeight)
        fittedW=imageWidth*fit;fittedH=imageHeight*fit;clamp();return bounds()
    }
    private fun clamp(){
        val x=max(0f,(fittedW*zoom-viewW)/2)
        val y=max(0f,(fittedH*zoom-viewH)/2)
        panX=panX.coerceIn(-x,x);panY=panY.coerceIn(-y,y)
    }
    fun bounds():Bounds {
        val w=fittedW*zoom;val h=fittedH*zoom
        return Bounds((viewW-w)/2+panX,(viewH-h)/2+panY,(viewW+w)/2+panX,(viewH+h)/2+panY)
    }
    /** Anchor the same image point while both scaling and translating the pinch midpoint. */
    fun scale(factor:Float,oldFocusX:Float,oldFocusY:Float,focusX:Float,focusY:Float){
        if(!factor.isFinite() || factor<=0f)return
        val old=zoom;zoom=(zoom*factor).coerceIn(1f,32f)
        val ratio=zoom/old
        panX=(panX-oldFocusX+viewW/2)*ratio+focusX-viewW/2
        panY=(panY-oldFocusY+viewH/2)*ratio+focusY-viewH/2
        clamp()
    }
    fun drag(dx:Float,dy:Float){panX+=dx;panY+=dy;clamp()}
    fun reset(){zoom=1f;panX=0f;panY=0f}
}

/** Rebase when a pointer changes: lifting either pinch finger must not create a pan jump. */
class PreviewTouch(private val slop:Float) {
    var activeId=-1;private set
    var moved=false;private set
    var multi=false;private set
    private var startX=0f;private var startY=0f
    private var lastX=0f;private var lastY=0f
    fun begin(id:Int,x:Float,y:Float){activeId=id;startX=x;startY=y;lastX=x;lastY=y;moved=false;multi=false}
    fun multiple(){multi=true;moved=true}
    fun rebase(id:Int,x:Float,y:Float){activeId=id;lastX=x;lastY=y}
    fun move(x:Float,y:Float):Pair<Float,Float>{
        if(hypot(x-startX,y-startY)>slop)moved=true
        val delta=if(moved)(x-lastX) to (y-lastY) else 0f to 0f
        lastX=x;lastY=y;return delta
    }
    fun end(){activeId=-1}
}

/** Each photo has its OWN native-pixel region; 4000x3000 must not be used on a 2048x1536 PNG. */
object PreviewRegions {
    fun visible(w:Int,h:Int,d:PreviewViewport.Bounds,viewW:Int,viewH:Int):NativeImageMath.Box? {
        if(w<=0 || h<=0 || d.w<=0 || d.h<=0)return null
        val left=max(0f,d.left);val top=max(0f,d.top)
        val right=min(viewW.toFloat(),d.right);val bottom=min(viewH.toFloat(),d.bottom)
        if(left>=right || top>=bottom)return null
        return NativeImageMath.Box(
            floor((left-d.left)/d.w*w).toInt().coerceIn(0,w-1),
            floor((top-d.top)/d.h*h).toInt().coerceIn(0,h-1),
            ceil((right-d.left)/d.w*w).toInt().coerceIn(1,w),
            ceil((bottom-d.top)/d.h*h).toInt().coerceIn(1,h))
    }
    fun sample(box:NativeImageMath.Box,sourceWidth:Int,displayWidth:Float):Int {
        var sample=1;val pixelsPerSource=displayWidth/sourceWidth
        while(pixelsPerSource*(sample*2)<=1.2f && sample<16384)sample*=2
        while(box.w.toLong()*box.h/(sample.toLong()*sample)>2_000_000)sample*=2
        return sample
    }
}

object ProcessingTime {
    fun seconds(ms:Long)=String.format(Locale.ROOT,"%.1f s",max(0L,ms)/1000.0)
}
