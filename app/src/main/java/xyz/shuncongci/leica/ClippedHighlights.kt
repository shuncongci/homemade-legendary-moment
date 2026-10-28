package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** 2.1.18 selective radiance recovery for CLIPPED, defocused point lights only.
 *
 * A sensor-clipped lamp stores at most linear 1.0, while its real radiance is far higher.
 * Spreading that capped surplus over a 3x pupil disc (~9x area) leaves a faint, transparent
 * veil instead of a bright bokeh ball. Here the extracted surplus of clipped sources is
 * scaled so the disc mean approaches TARGET; the gain is derived from the local summed
 * surplus inside the disc footprint, so dense light fields automatically get less gain.
 *
 * Untouched (bit-identical [light]): unclipped pixels, |CoC|<=.75 (in focus / protected
 * subject), pixels with zero extracted surplus, and broad bright surfaces whose extraction
 * already reverted the cap (tone weight 0). Base image pixels are never modified here.
 */
object ClippedHighlights {
    const val CLIP_START=236
    const val CLIP_FULL=254
    const val TARGET=.5f
    const val MAX_GAIN=48f
    private const val CELL=4
    private fun smooth(v:Float):Float{val t=v.coerceIn(0f,1f);return t*t*(3-2*t)}
    private val linearTable=FloatArray(256){v->val x=v/255f;if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)}

    internal fun clipWeight(maxByte:Int)=smooth((maxByte-CLIP_START).toFloat()/(CLIP_FULL-CLIP_START))
    internal fun defocusWeight(coc:Float)=smooth((abs(coc)-.75f)/1.25f)

    /** Per-pixel multiplier (>=1) for the surplus of [high]. Exposed for tests/audits. */
    internal fun gains(colored:Planes,high:Frame,coc:Frame,cancel:Cancel):FloatArray? {
        require(high.channels==3 && coc.channels==1 && high.w==colored.width && high.h==colored.height && coc.w==high.w && coc.h==high.h)
        return gainsPeak(colored,high.w,high.h,coc,cancel){i->max(high.data[i],max(high.data[high.size+i],high.data[2*high.size+i]))}
    }
    /** Same global density equation, from the owned per-pixel RGB peak; no whole RGB float input. */
    internal fun gainsFromPeak(colored:Planes,peak:Frame,coc:Frame,cancel:Cancel):FloatArray? {
        require(peak.channels==1 && peak.w==colored.width && peak.h==colored.height)
        return gainsPeak(colored,peak.w,peak.h,coc,cancel){peak.data[it]}
    }
    private fun gainsPeak(colored:Planes,w:Int,h:Int,coc:Frame,cancel:Cancel,peak:(Int)->Float):FloatArray? {
        require(coc.w==w && coc.h==h && coc.channels==1)
        val n=w*h
        fun maxByte(i:Int)=maxOf(colored.p0[i].toInt() and 255,colored.p1[i].toInt() and 255,colored.p2[i].toInt() and 255)
        var any=false
        for(i in 0 until n){if(i and 65535==0)cancel.check()
            if(maxByte(i)>CLIP_START && abs(coc.data[i])>.75f && (peak(i)>0f)){any=true;break}
        }
        if(!any)return null
        // Coarse summed-area table of the surplus peak (all extracted lights, clipped or not),
        // so neighbouring sources reduce each other's gain. Memory ~ n/16 doubles.
        val gw=(w+CELL-1)/CELL;val gh=(h+CELL-1)/CELL
        val cell=DoubleArray(gw*gh)
        for(y in 0 until h){if(y and 63==0)cancel.check();val row=(y/CELL)*gw
            for(x in 0 until w){val i=y*w+x;val m=peak(i);if(m>0f)cell[row+x/CELL]+=m.toDouble()}
        }
        val sat=DoubleArray((gw+1)*(gh+1))
        for(gy in 0 until gh){var run=0.0;for(gx in 0 until gw){run+=cell[gy*gw+gx];sat[(gy+1)*(gw+1)+gx+1]=sat[gy*(gw+1)+gx+1]+run}}
        fun box(x0:Int,y0:Int,x1:Int,y1:Int):Double {
            val a=(x0/CELL).coerceIn(0,gw-1);val b=(y0/CELL).coerceIn(0,gh-1);val c=(x1/CELL).coerceIn(0,gw-1)+1;val d=(y1/CELL).coerceIn(0,gh-1)+1
            return sat[d*(gw+1)+c]-sat[b*(gw+1)+c]-sat[d*(gw+1)+a]+sat[b*(gw+1)+a]
        }
        val out=FloatArray(n){1f}
        parallelFor(h){y->if(y and 31==0)cancel.check()
            for(x in 0 until w){val i=y*w+x
                val dw=defocusWeight(coc.data[i]);if(dw<=0f)continue
                val mb=maxByte(i);val cw=clipWeight(mb);if(cw<=0f)continue
                val m=peak(i);if(m<=0f)continue
                // Fraction of the clipped linear value that extraction moved into the light layer.
                // Broad surfaces (cap reverted to the peak) have ~0 and stay untouched.
                val tone=smooth((m/linearTable[mb]-.25f)/.5f);if(tone<=0f)continue
                val r=max(1f,abs(coc.data[i])*HighlightScatter.RADIUS_SCALE);val ri=ceil(r).toInt()
                // Square box includes more neighbours than the disc: conservative (never over-boosts).
                val sum=box(x-ri,y-ri,x+ri,y+ri);val mean=(sum/(PI*r*r)).toFloat()
                if(mean<=0f)continue
                val wanted=(TARGET/mean).coerceIn(1f,MAX_GAIN)
                out[i]=1f+(wanted-1f)*cw*dw*tone
            }
        }
        cancel.check();return out
    }

    /** Scales [light] (owned by the caller) in place; [high] is the unboosted surplus used for density. */
    fun recoverInPlace(light:Frame,colored:Planes,high:Frame,coc:Frame,cancel:Cancel):Frame {
        cancel.check()
        require(light.channels==3 && light.w==high.w && light.h==high.h)
        val g=gains(colored,high,coc,cancel)?:return light
        val n=light.size
        for(i in 0 until n){if(i and 65535==0)cancel.check();val k=g[i];if(k==1f)continue
            for(c in 0..2)light.data[c*n+i]*=k
        }
        cancel.check();return light
    }
}
