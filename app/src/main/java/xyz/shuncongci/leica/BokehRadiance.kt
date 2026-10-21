package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Natural source radiance by default. Explicit creative gain remains available to callers. */
object BokehRadiance {
    const val DEFAULT_GAIN=1f
    fun gain(coc:Float,amount:Float=DEFAULT_GAIN):Float {
        require(coc.isFinite() && amount.isFinite() && amount>=1f)
        val t=((abs(coc)-.75f)/1.25f).coerceIn(0f,1f)
        return 1f+(amount-1f)*t*t*(3-2*t)
    }
    fun boost(high:Frame,coc:Frame,cancel:Cancel,amount:Float=DEFAULT_GAIN):Frame {
        require(high.channels==3 && coc.channels==1 && high.w==coc.w && high.h==coc.h)
        val out=Frame(high.w,high.h,3,FloatArray(high.data.size))
        for(i in 0 until high.size){if(i%high.w==0)cancel.check();val g=gain(coc.data[i],amount)
            for(ch in 0..2){val at=ch*high.size+i;require(high.data[at].isFinite() && high.data[at]>=0f);out.data[at]=high.data[at]*g}
        };cancel.check();return out
    }
    private val linearTable=FloatArray(256){v->val x=v/255f;if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)}
    private fun linear(v:Int):Float=linearTable[v]
    private fun byte(v:Float):Byte {val x=if(v<=.0031308f)12.92f*max(0f,v) else 1.055f*max(0f,v).pow(1/2.4f)-.055f;return Math.rint((x.coerceIn(0f,1f)*255).toDouble()).toInt().toByte()}
    /** Restore the sharp subject, then restore ONLY foreground light lost to that restoration.
     * Foreground is premultiplied PSF radiance from positive CoC layers, not a binary cutout.
     * No mask dilation/erosion and no second scatter. Without foreground keep the
     * same subject-edge composite, including its exact opaque/background endpoints. */
    fun protect(sharp:Planes,rendered:Planes,alpha:Frame,foreground:Frame,cancel:Cancel,sourceHigh:Frame?=null,sourceCoc:Frame?=null):Planes {
        require((sourceHigh==null)==(sourceCoc==null))
        if(sourceHigh!=null)require(sourceHigh.channels==3 && sourceHigh.w==sharp.width && sourceHigh.h==sharp.height && sourceCoc!!.channels==1 && sourceCoc.w==sharp.width && sourceCoc.h==sharp.height)
        require(sharp.width==rendered.width && sharp.height==rendered.height)
        require(foreground.w==sharp.width && foreground.h==sharp.height && foreground.channels==3)
        val out=SubjectProtectionMath.composite(sharp,rendered,alpha,cancel)
        val a=LegendaryMath.resize(alpha,sharp.width,sharp.height,cancel=cancel);val n=sharp.size
        // Reference is unscattered prepared source radiance, before it spreads.
        // Sharp restoration already retains the corresponding local reflection.
        // Only an incoming SURPLUS can be missing after restoration; this also
        // conservatively avoids adding a creative source gain to itself.
        fun missing(at:Int,i:Int):Float {
            val incoming=foreground.data[at];require(incoming.isFinite() && incoming>=0f)
            val local=if(sourceHigh!=null && sourceCoc!!.data[i]>0f)sourceHigh.data[at] else 0f
            require(local.isFinite() && local>=0f)
            return max(0f,incoming-local)
        }
        for(i in 0 until n){if(i%sharp.width==0)cancel.check();val weight=a.data[i].coerceIn(0f,1f);if(weight==0f)continue
            var scale=1f
            for(ch in 0..2){val light=missing(ch*n+i,i)*weight;require(light.isFinite() && light>=0f)
                if(light>0f)scale=min(scale,(1f-linear(out.plane(ch)[i].toInt() and 255))/light)
            }
            for(ch in 0..2){val light=missing(ch*n+i,i)*weight;if(light>0f)out.plane(ch)[i]=byte(linear(out.plane(ch)[i].toInt() and 255)+light*scale.coerceIn(0f,1f))}
        };cancel.check();return out
    }
}
