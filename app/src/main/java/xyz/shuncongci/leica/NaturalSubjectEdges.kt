package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Background-delta recomposition for already mixed matte edges.
 * I = aF + (1-a)B must not be multiplied by a a second time. Estimate only
 * nearby KNOWN background, then I' = I + (1-a)(B'-B). No erosion, dilation,
 * alpha thresholding, or new model inference. If evidence is missing or the
 * inferred premultiplied F is impossible, retain the conservative composite.
 * Work in the matte model's sRGB domain; this is not an HDR relighting model.
 * Bounded row strips avoid adding several full-resolution float images.
 */
internal object NaturalSubjectEdges {
    fun refine(sharp:Planes,rendered:Planes,alpha:Frame,out:Planes,cancel:Cancel):Planes {
        val w=sharp.width;val h=sharp.height
        val r=max(5,ceil(max(w,h)/1220.0*5).toInt()).coerceAtMost(32)
        for(top in 0 until h step 96){
            cancel.check();val end=min(h,top+96)
            var needed=false
            loop@ for(y in top until end)for(x in 0 until w){val a=alpha.data[y*w+x];if(a>0f && a<1f){needed=true;break@loop}}
            if(!needed)continue
            val y0=max(0,top-r);val y1=min(h,end+r);val rows=y1-y0;val n=w*rows
            val horizontal=FloatArray(n);val den=FloatArray(n);val bg=FloatArray(n);val delta=FloatArray(n)
            val valid=BooleanArray((end-top)*w){true}
            val reliability=FloatArray((end-top)*w){1f}
            val proposal=Array(3){ByteArray((end-top)*w)}
            fun box(dst:FloatArray,value:(Int)->Float){
                for(yy in 0 until rows){cancel.check();val row=yy*w;var sum=0.0;var left=0;var right=-1
                    for(x in 0 until w){val lo=max(0,x-r);val hi=min(w-1,x+r)
                        while(right<hi){right++;sum+=value((yy+y0)*w+right)}
                        while(left<lo){sum-=value((yy+y0)*w+left);left++}
                        horizontal[row+x]=sum.toFloat()
                    }
                }
                for(x in 0 until w){if(x and 255==0)cancel.check();var sum=0.0;var lo=0;var hi=-1
                    for(yy in 0 until rows){val a=max(0,yy-r);val b=min(rows-1,yy+r)
                        while(hi<b){hi++;sum+=horizontal[hi*w+x]};while(lo<a){sum-=horizontal[lo*w+x];lo++}
                        dst[yy*w+x]=sum.toFloat()
                    }
                }
            }
            fun known(i:Int)=alpha.data[i]<=0f
            box(den){i->if(known(i))1f else 0f}
            for(ch in 0..2){
                val s=sharp.plane(ch);val b=rendered.plane(ch)
                box(bg){i->if(known(i))(s[i].toInt() and 255).toFloat() else 0f}
                box(delta){i->if(known(i)){val v=(s[i].toInt() and 255).toFloat();v*v} else 0f}
                for(y in top until end)for(x in 0 until w){val k=(y-y0)*w+x;val j=(y-top)*w+x
                    if(den[k]>0f){val mean=bg[k]/den[k];val sigma=sqrt(max(0f,delta[k]/den[k]-mean*mean))
                        val t=((32f-sigma)/20f).coerceIn(0f,1f);reliability[j]=min(reliability[j],t*t*(3-2*t))
                    }
                }
                box(delta){i->if(known(i))((b[i].toInt() and 255)-(s[i].toInt() and 255)).toFloat() else 0f}
                for(y in top until end){cancel.check();for(x in 0 until w){
                    val i=y*w+x;val k=(y-y0)*w+x;val j=(y-top)*w+x;val a=alpha.data[i].coerceIn(0f,1f)
                    if(a<=0f || a>=1f || den[k]<1f){valid[j]=false;continue}
                    val source=(s[i].toInt() and 255).toFloat();val oldBg=bg[k]/den[k]
                    val premult=source-(1-a)*oldBg
                    // Shared RGB validity is applied after all channels; never tint
                    // an edge by accepting an impossible estimate in just one channel.
                    if(premult < -3f || premult > a*255f+3f){valid[j]=false;continue}
                    val evidence=(den[k]/4f).coerceIn(0f,1f)
                    val t=(a/.05f).coerceIn(0f,1f);val confidence=evidence*t*t*(3-2*t)
                    reliability[j]=min(reliability[j],confidence)
                    val corrected=(source+(1-a)*delta[k]/den[k]).coerceIn(0f,255f)
                    proposal[ch][j]=Math.rint(corrected.toDouble()).toInt().coerceIn(0,255).toByte()
                }}
            }
            for(y in top until end){cancel.check();for(x in 0 until w){val j=(y-top)*w+x;if(valid[j])for(ch in 0..2){
                val i=y*w+x;val old=out.plane(ch)[i].toInt() and 255;val proposed=proposal[ch][j].toInt() and 255
                out.plane(ch)[i]=Math.rint((old+(proposed-old)*reliability[j]).toDouble()).toInt().coerceIn(0,255).toByte()
            }}}
        }
        cancel.check();return out
    }
}
