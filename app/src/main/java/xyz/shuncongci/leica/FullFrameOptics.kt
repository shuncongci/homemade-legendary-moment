package xyz.shuncongci.leica

import kotlin.math.*

/** Thin lens in millimetres. Relative inverse depth is not a measured scene distance. */
object FullFrameOptics {
    const val FOCAL_MM=50.0
    const val SENSOR_LONG_MM=36.0
    const val SENSOR_SHORT_MM=24.0
    const val ACCEPTABLE_DIAMETER_MM=.03
    const val VIRTUAL_NEAR_MM=1200.0
    const val VIRTUAL_FAR_MM=30000.0
    fun checkAperture(n:Float){require(n.isFinite() && LegendaryMath.apertures.any{it==n}){"不支持的光圈"}}
    fun inverseDistance(d:Float):Double {
        require(d.isFinite() && d in 0f..1f)
        return 1/VIRTUAL_FAR_MM+d*(1/VIRTUAL_NEAR_MM-1/VIRTUAL_FAR_MM)
    }
    fun virtualDistance(d:Float)=1/inverseDistance(d)
    fun sensorLong(w:Int,h:Int):Double {
        require(w>0 && h>0)
        return min(SENSOR_LONG_MM,SENSOR_SHORT_MM*max(w,h).toDouble()/min(w,h))
    }
    fun diameterMm(objectMm:Double,focusMm:Double,n:Float):Double {
        checkAperture(n);require(objectMm>FOCAL_MM && focusMm>FOCAL_MM && focusMm.isFinite())
        return FOCAL_MM*FOCAL_MM/n/(1-FOCAL_MM/focusMm)*(1/objectMm-1/focusMm)
    }
    data class Limits(val nearMm:Double,val farMm:Double,val hyperfocalMm:Double)
    fun limits(focusMm:Double,n:Float):Limits {
        checkAperture(n);require(focusMm.isFinite() && focusMm>FOCAL_MM)
        val h=FOCAL_MM*FOCAL_MM/(n*ACCEPTABLE_DIAMETER_MM)+FOCAL_MM
        return Limits(focusMm*(h-FOCAL_MM)/(h+focusMm-2*FOCAL_MM),
            if(focusMm>=h)Double.POSITIVE_INFINITY else focusMm*(h-FOCAL_MM)/(h-focusMm),h)
    }
    fun radiusScale(focus:Float,n:Float,w:Int,h:Int):Float {
        checkAperture(n)
        return (FOCAL_MM*FOCAL_MM/n/(1-FOCAL_MM*inverseDistance(focus))*
            (1/VIRTUAL_NEAR_MM-1/VIRTUAL_FAR_MM)*max(w,h)/(2*sensorLong(w,h))).toFloat()
    }
    fun acceptableRadius(w:Int,h:Int)=(ACCEPTABLE_DIAMETER_MM*max(w,h)/(2*sensorLong(w,h))).toFloat()
    fun maxRadius(w:Int,h:Int)=radiusScale(1f,LegendaryMath.apertures.minOrNull()!!,w,h)
    fun coc(depth:LegendaryMath.Frame,focus:Float,n:Float,w:Int,h:Int,cancel:Cancel):LegendaryMath.Frame {
        cancel.check();require(depth.channels==1 && depth.data.all{it.isFinite() && it in 0f..1f})
        val gain=radiusScale(focus,n,w,h)
        val native=LegendaryMath.Frame(depth.w,depth.h,1,FloatArray(depth.size){(depth.data[it]-focus)*gain})
        return LegendaryMath.resize(native,w,h,cancel=cancel).also{cancel.check()}
    }
    internal fun displayPixel(index:Int,displaySize:Int,outputSize:Int)=
        (((index+.5)*outputSize/displaySize).toInt()).coerceIn(0,outputSize-1)
    internal fun sample(f:LegendaryMath.Frame,x:Int,y:Int,w:Int,h:Int):Float {
        if(w==f.w && h==f.h)return f.data[y*f.w+x]
        val sy=(y+.5f)*f.h/h-.5f;val iy=floor(sy).toInt();val fy=sy-iy
        val sx=(x+.5f)*f.w/w-.5f;val ix=floor(sx).toInt();val fx=sx-ix;var sum=0f
        for(dy in 0..1)for(dx in 0..1)sum+=f.data[(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*(if(dy==0)1-fy else fy)*(if(dx==0)1-fx else fx)
        return sum
    }
}
