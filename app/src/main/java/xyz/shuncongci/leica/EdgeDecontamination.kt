package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Subject-edge decontamination (mixed alpha band only), 2.1.20.
 *
 * A mixed pixel of the sharp photo is I = aF + (1-a)B_sharp + r. Natural/Gradient recomposition
 * keeps I and only swaps the background mean, so r (sharp background detail, phone-sharpening
 * halo) stayed whole and showed as a crisp rim against the blur (2.1.18 mirror gap).
 * 2.1.19 removed r with per-pixel switches (overshoot -> full replacement by the flat local mean
 * aF^+(1-a)B^, hard affine/mean switch): neighbouring band pixels were either flattened or kept,
 * which read as a separate, rough blur layer.
 *
 * One continuous rule instead (approximate foreground estimation, F = F^ + a*r):
 *     I' = I - (1-a^2) * rel * r
 * The residual left in the band fades as a^2 toward the background side, where the sharp
 * background shows; the subject side keeps its texture. F^ and B^ are kernel weighted (no sample
 * count switches); rel is a product of smooth evidence, spatial conditioning, background texture
 * and residual-size terms, so the correction varies continuously along the edge.
 * Exact 0/1 endpoints never change; the alpha is not modified; no erosion/dilation.
 * Not a semantic matting model.
 */
object EdgeDecontamination {
    const val FG=.98f
    const val BG=.02f
    private fun smooth(v:Double):Double{val t=v.coerceIn(0.0,1.0);return t*t*(3-2*t)}
    fun radius(w:Int,h:Int)=ceil(3*max(1.0,max(w,h)/1220.0)+2).toInt().coerceIn(4,24)

    fun clean(sharp:Planes,alpha:Frame,cancel:Cancel):Planes {
        require(alpha.channels==1 && alpha.w==sharp.width && alpha.h==sharp.height)
        cancel.check()
        val w=sharp.width;val h=sharp.height;val a=alpha.data
        if(a.none{it>0f && it<1f})return sharp
        val out=Planes(w,h,sharp.p0.copyOf(),sharp.p1.copyOf(),sharp.p2.copyOf())
        val r=radius(w,h);val step=if(r<=12)1 else 2;val rr=(r+1.0)*(r+1.0)
        fun px(c:Int,i:Int)=(sharp.plane(c)[i].toInt() and 255).toDouble()
        parallelFor(h){y->if(y and 15==0)cancel.check()
            val f=DoubleArray(3);val z=DoubleArray(3);val zx=DoubleArray(3);val zy=DoubleArray(3);val zz=DoubleArray(3)
            val b=DoubleArray(3);val e=DoubleArray(3)
            for(x in 0 until w){val i=y*w+x;val t=a[i];if(!(t>0f && t<1f))continue
                var wf=0.0;var wb=0.0;var sx=0.0;var sy=0.0;var sxx=0.0;var sxy=0.0;var syy=0.0
                for(c in 0..2){f[c]=0.0;z[c]=0.0;zx[c]=0.0;zy[c]=0.0;zz[c]=0.0}
                var dy=-r
                while(dy<=r){val yy=y+dy;if(yy in 0 until h){var dx=-r
                    while(dx<=r){val xx=x+dx;val d2=(dx*dx+dy*dy).toDouble()
                        if(xx in 0 until w && d2<rr){val j=yy*w+xx;val v=a[j];val k=1-d2/rr
                            if(v>=FG){wf+=k;for(c in 0..2)f[c]+=k*px(c,j)}
                            else if(v<=BG){val fx=dx.toDouble();val fy=dy.toDouble();wb+=k
                                sx+=k*fx;sy+=k*fy;sxx+=k*fx*fx;sxy+=k*fx*fy;syy+=k*fy*fy
                                for(c in 0..2){val p=px(c,j);z[c]+=k*p;zx[c]+=k*p*fx;zy[c]+=k*p*fy;zz[c]+=k*p*p}
                            }
                        };dx+=step}
                };dy+=step}
                // Kernel weight, not a sample count: evidence grows smoothly as the window slides.
                val evidence=smooth(wf/3)*smooth(wb/6);if(evidence<=0)continue
                val mx=sx/wb;val my=sy/wb
                val cxx=sxx-sx*mx;val cyy=syy-sy*my;val cxy=sxy-sx*my;val det=cxx*cyy-cxy*cxy
                // A single known row/column cannot tell a slope from an offset: fade out, don't guess.
                // Depends on matte geometry only (not pixel noise), so it varies smoothly along the edge.
                // A thin known sliver cannot separate a slope from an offset; a looser gate here was
                // measured to bias sloped backgrounds (ReflectionEdgeTest MAE 1.9 > 1), so it stays at 1px^2.
                val spread=smooth(min(cxx,cyy)/wb);if(spread<=0)continue
                val affine=det>1e-9*cxx*cyy
                val leverage=if(affine)1/wb+(cyy*mx*mx-2*cxy*mx*my+cxx*my*my)/det else Double.POSITIVE_INFINITY
                // Smooth blend from the affine prediction to the mean as extrapolation gets long.
                val lambda=if(affine)smooth((.6-leverage)/.3) else 0.0
                var sigma=0.0
                for(c in 0..2){val mz=z[c]/wb;val total=max(0.0,zz[c]/wb-mz*mz)
                    var predicted=mz;var fitVar=total
                    if(affine){val cxz=zx[c]-sx*mz;val cyz=zy[c]-sy*mz
                        val bx=(cxz*cyy-cyz*cxy)/det;val by=(cyz*cxx-cxz*cxy)/det
                        predicted=mz-bx*mx-by*my;fitVar=max(0.0,total-(bx*cxz+by*cyz)/wb)}
                    b[c]=mz+lambda*(predicted-mz)
                    sigma=max(sigma,sqrt(lambda*fitVar+(1-lambda)*total))
                }
                val texture=smooth((48-sigma)/24);if(texture<=0)continue
                val tt=t.toDouble();var en=0.0
                for(c in 0..2){e[c]=px(c,i)-tt*f[c]/wf-(1-tt)*b[c];en+=e[c]*e[c]}
                // A residual far larger than a halo means the local model does not explain the pixel.
                val fit=smooth((64-sqrt(en))/32)
                val k=(1-tt*tt)*evidence*spread*texture*fit;if(k<=0)continue
                for(c in 0..2)out.plane(c)[i]=Math.rint((px(c,i)-k*e[c]).coerceIn(0.0,255.0)).toInt().toByte()
            }
        }
        cancel.check();return out
    }
}
