package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** A local affine BACKGROUND model, never a blurred or expanded foreground matte.
 * Mean-only background estimation is biased on ramps and can leave a contour.
 * Require nondegenerate spatial support, low residual, feasible premultiplied RGB
 * and bounded extrapolation. Textures/unknown backgrounds keep the existing fallback.
 * Double strip moments avoid coordinate cancellation at full photo resolution.
 */
internal object GradientSubjectEdges {
    private fun smooth(v:Double):Double {val t=v.coerceIn(0.0,1.0);return t*t*(3-2*t)}
    fun refine(sharp:Planes,rendered:Planes,alpha:Frame,out:Planes,cancel:Cancel):Planes {
        val w=sharp.width;val h=sharp.height
        val r=(3*max(5,ceil(max(w,h)/1220.0*5).toInt())).coerceAtMost(64)
        for(top in 0 until h step 64){
            cancel.check();val end=min(h,top+64);val count=(end-top)*w
            if(!(top*w until end*w).any{alpha.data[it]>0f && alpha.data[it]<1f})continue
            val y0=max(0,top-r);val y1=min(h,end+r);val rows=y1-y0;val n=w*rows
            val horizontal=DoubleArray(n)
            fun known(i:Int)=alpha.data[i]<=0f
            fun box(value:(Int)->Double):DoubleArray {
                for(yy in 0 until rows){cancel.check();var sum=0.0;var left=0;var right=-1
                    for(x in 0 until w){val lo=max(0,x-r);val hi=min(w-1,x+r)
                        while(right<hi){right++;sum+=value((yy+y0)*w+right)}
                        while(left<lo){sum-=value((yy+y0)*w+left);left++}
                        horizontal[yy*w+x]=sum
                    }
                }
                val dst=DoubleArray(n)
                for(x in 0 until w){if(x and 255==0)cancel.check();var sum=0.0;var lo=0;var hi=-1
                    for(yy in 0 until rows){val a=max(0,yy-r);val b=min(rows-1,yy+r)
                        while(hi<b){hi++;sum+=horizontal[hi*w+x]};while(lo<a){sum-=horizontal[lo*w+x];lo++}
                        dst[yy*w+x]=sum
                    }
                };return dst
            }
            val den=box{if(known(it))1.0 else 0.0}
            val sx=box{if(known(it))(it%w).toDouble() else 0.0}
            val sy=box{if(known(it))(it/w).toDouble() else 0.0}
            val xx=box{if(known(it))(it%w).toDouble().pow(2) else 0.0}
            val xy=box{if(known(it))(it%w).toDouble()*(it/w) else 0.0}
            val yy=box{if(known(it))(it/w).toDouble().pow(2) else 0.0}
            val valid=BooleanArray(count){true};val confidence=DoubleArray(count){1.0}
            val sloped=BooleanArray(count);val oldEstimate=DoubleArray(count)
            val proposal=Array(3){ByteArray(count)}
            for(ch in 0..2){
                val s=sharp.plane(ch);val b=rendered.plane(ch)
                for(delta in listOf(false,true)){
                    fun value(i:Int)=if(delta)((b[i].toInt() and 255)-(s[i].toInt() and 255)).toDouble() else (s[i].toInt() and 255).toDouble()
                    val z=box{if(known(it))value(it) else 0.0}
                    val zx=box{if(known(it))value(it)*(it%w) else 0.0}
                    val zy=box{if(known(it))value(it)*(it/w) else 0.0}
                    val zz=box{if(known(it))value(it).pow(2) else 0.0}
                    for(y in top until end){cancel.check();for(x in 0 until w){
                        val i=y*w+x;val j=(y-top)*w+x;val k=(y-y0)*w+x;val a=alpha.data[i].coerceIn(0f,1f).toDouble()
                        if(!valid[j])continue
                        val d=den[k]
                        if(a<=0 || a>=1 || d<8){valid[j]=false;continue}
                        val mx=sx[k]/d;val my=sy[k]/d
                        val cxx=xx[k]-sx[k]*mx;val cyy=yy[k]-sy[k]*my;val cxy=xy[k]-sx[k]*my
                        val det=cxx*cyy-cxy*cxy
                        if(cxx<=1e-6 || cyy<=1e-6 || det<=1e-6*cxx*cyy){valid[j]=false;continue}
                        val mz=z[k]/d;val cxz=zx[k]-sx[k]*mz;val cyz=zy[k]-sy[k]*mz
                        val bx=(cxz*cyy-cyz*cxy)/det;val by=(cyz*cxx-cxz*cxy)/det
                        val dx=x-mx;val dy=y-my
                        val leverage=1/d+(cyy*dx*dx-2*cxy*dx*dy+cxx*dy*dy)/det
                        val predicted=mz+bx*dx+by*dy
                        val sigma=sqrt(max(0.0,zz[k]/d-mz*mz-(bx*cxz+by*cyz)/d))
                        val reliability=smooth((.5-leverage)/.3)*smooth((12-sigma)/4)*smooth(a/.05)
                        if(reliability<=0){valid[j]=false;continue}
                        confidence[j]=min(confidence[j],reliability)
                        if(abs(predicted-mz)>1.0)sloped[j]=true
                        if(!delta){
                            oldEstimate[j]=predicted
                            val premult=(s[i].toInt() and 255)-(1-a)*predicted
                            if(predicted < -3 || predicted >258 || premult < -3 || premult > a*255+3)valid[j]=false
                        }else{
                            val newBg=oldEstimate[j]+predicted
                            if(newBg < -3 || newBg >258){valid[j]=false;continue}
                            val corrected=(s[i].toInt() and 255)+(1-a)*predicted
                            proposal[ch][j]=Math.rint(corrected.coerceIn(0.0,255.0)).toInt().toByte()
                        }
                    }}
                }
            }
            for(y in top until end){cancel.check();for(x in 0 until w){val j=(y-top)*w+x
                if(valid[j] && sloped[j])for(ch in 0..2){val i=y*w+x;val old=out.plane(ch)[i].toInt() and 255;val target=proposal[ch][j].toInt() and 255
                    out.plane(ch)[i]=Math.rint(old+(target-old)*confidence[j]).toInt().coerceIn(0,255).toByte()
                }
            }}
        };cancel.check();return out
    }
}
