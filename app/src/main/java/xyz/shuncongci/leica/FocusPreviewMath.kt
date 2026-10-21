package xyz.shuncongci.leica
import kotlin.math.abs

object FocusPreviewMath {
    const val NEAR_ARGB: Int = 0x663FDC9C
    const val TRANSITION_ARGB: Int = 0x55FFD166
    data class Overlay(val width:Int,val height:Int,val argb:IntArray,val focus:Float)
    fun color(coc:Float,acceptable:Float):Int {
        require(coc.isFinite() && acceptable>0 && acceptable.isFinite())
        return when { abs(coc)<=acceptable -> NEAR_ARGB; abs(coc)<acceptable*2 -> TRANSITION_ARGB; else -> 0 }
    }
    fun create(depth:LegendaryMath.Frame,point:LegendaryMath.Point,aperture:Float,
               outputWidth:Int,outputHeight:Int,cancel:Cancel):Overlay? {
        cancel.check();FullFrameOptics.checkAperture(aperture)
        val selected=LegendaryMath.focus(depth,point) ?: return null
        val (w,h)=LeicaSizes.previewSize(outputWidth,outputHeight)
        val gain=FullFrameOptics.radiusScale(selected,aperture,outputWidth,outputHeight)
        val field=LegendaryMath.Frame(depth.w,depth.h,1,FloatArray(depth.size){(depth.data[it]-selected)*gain})
        val acceptable=FullFrameOptics.acceptableRadius(outputWidth,outputHeight)
        val pixels=IntArray(w*h)
        for(y in 0 until h){cancel.check();val oy=FullFrameOptics.displayPixel(y,h,outputHeight)
            for(x in 0 until w){val ox=FullFrameOptics.displayPixel(x,w,outputWidth)
                pixels[y*w+x]=color(FullFrameOptics.sample(field,ox,oy,outputWidth,outputHeight),acceptable)
            }
        }
        cancel.check();return Overlay(w,h,pixels,selected)
    }
}

/** Single-worker, single immutable imported-photo cache. Completed normalized frames only.
 * Callers borrow read-only frame data. Focus/aperture/output never form part of this key.
 * No ONNX session, input tensor, source bitmap or old-photo history is retained.
 */
class PhotoDepthCache {
    private var key:String?=null
    private var value:LegendaryMath.Frame?=null
    fun contains(photo:String)=key==photo && value!=null
    fun clear(){key=null;value=null}
    fun get(photo:String,cancel:Cancel,compute:()->LegendaryMath.Frame):LegendaryMath.Frame {
        cancel.check()
        if(key==photo)return checkNotNull(value)
        clear()
        val result=compute()
        require(result.channels==1 && result.data.all{it.isFinite() && it in 0f..1f})
        cancel.check() // Never publish a failed/cancelled partial calculation.
        key=photo;value=result
        return result
    }
}
