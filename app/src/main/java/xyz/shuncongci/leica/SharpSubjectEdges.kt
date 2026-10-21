package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** 2.1.18 render-resolution subject alpha.
 *
 * The matte is computed at the subject analysis size (2048px long edge since 2.1.21, 1220 before). Bilinear enlargement to the render size
 * widens every edge ramp by the scale factor, so the restored subject looked soft/"pasted".
 * Inside that ramp only, each mixed pixel's alpha is re-estimated by projecting the full-
 * resolution photo colour onto the line between local opaque-subject and background means.
 * Low contrast, textured statistics or a poor two-colour fit fall back to the enlarged alpha.
 * Exact 0/1 endpoints never change; same-size alpha is returned untouched. No new detail is
 * invented beyond what the full-resolution photo shows.
 */
object SharpSubjectEdges {
    const val FG=.98f
    const val BG=.02f
    const val MIN_SAMPLES=4
    private fun smooth(v:Float):Float{val t=v.coerceIn(0f,1f);return t*t*(3-2*t)}

    fun renderAlpha(sharp:Planes,alpha:Frame,cancel:Cancel):Frame {
        require(alpha.channels==1)
        if(alpha.w==sharp.width && alpha.h==sharp.height)return alpha
        val up=LegendaryMath.resize(alpha,sharp.width,sharp.height,cancel=cancel)
        val scale=max(sharp.width.toFloat()/alpha.w,sharp.height.toFloat()/alpha.h)
        return if(scale<1.25f)up else tighten(sharp,up,scale,cancel)
    }

    internal fun tighten(sharp:Planes,a:Frame,scale:Float,cancel:Cancel):Frame {
        require(a.channels==1 && a.w==sharp.width && a.h==sharp.height && scale.isFinite() && scale>0f)
        val w=a.w;val h=a.h;val src=a.data;val out=src.copyOf()
        val r=ceil(2*scale+1).toInt().coerceIn(2,24)
        fun px(c:Int,i:Int)=(sharp.plane(c)[i].toInt() and 255).toDouble()
        parallelFor(h){y->if(y and 15==0)cancel.check()
            for(x in 0 until w){val i=y*w+x;val t=src[i];if(!(t>0f && t<1f))continue
                var nf=0;var nb=0
                var f0=0.0;var f1=0.0;var f2=0.0;var fq=0.0;var b0=0.0;var b1=0.0;var b2=0.0;var bq=0.0
                for(yy in max(0,y-r)..min(h-1,y+r)){val row=yy*w
                    for(xx in max(0,x-r)..min(w-1,x+r)){val j=row+xx;val v=src[j]
                        if(v>=FG){val p=px(0,j);val q=px(1,j);val s=px(2,j);nf++;f0+=p;f1+=q;f2+=s;fq+=p*p+q*q+s*s}
                        else if(v<=BG){val p=px(0,j);val q=px(1,j);val s=px(2,j);nb++;b0+=p;b1+=q;b2+=s;bq+=p*p+q*q+s*s}
                    }
                }
                if(nf<MIN_SAMPLES || nb<MIN_SAMPLES)continue
                f0/=nf;f1/=nf;f2/=nf;b0/=nb;b1/=nb;b2/=nb
                val vf=max(0.0,fq/nf-(f0*f0+f1*f1+f2*f2));val vb=max(0.0,bq/nb-(b0*b0+b1*b1+b2*b2))
                val d0=f0-b0;val d1=f1-b1;val d2=f2-b2;val dd=d0*d0+d1*d1+d2*d2
                if(dd<1e-6)continue
                val contrast=smooth(((sqrt(dd)-12)/24).toFloat())
                val texture=smooth(((dd/(vf+vb+1)-4)/12).toFloat())
                if(contrast<=0f || texture<=0f)continue
                val i0=px(0,i)-b0;val i1=px(1,i)-b1;val i2=px(2,i)-b2
                val p=((i0*d0+i1*d1+i2*d2)/dd).coerceIn(0.0,1.0)
                val e0=i0-p*d0;val e1=i1-p*d1;val e2=i2-p*d2
                val fit=smooth((1.5f-((e0*e0+e1*e1+e2*e2)/(.1*dd+vf+vb+16)).toFloat()))
                val confidence=contrast*texture*fit
                if(confidence>0f)out[i]=(t+(p.toFloat()-t)*confidence).coerceIn(0f,1f)
            }
        }
        cancel.check();return Frame(w,h,1,out)
    }
}
