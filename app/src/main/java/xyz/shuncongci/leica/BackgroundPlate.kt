package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** 2.1.18 clean background plate for subject-protected bokeh.
 *
 * Before 2.1.18 ARNet and the multiscale blur saw the protected subject itself, so subject
 * colour was smeared into the background just outside the subject; restoring the sharp
 * subject on top then left a soft halo ("pasted on" look). Here the subject region of the
 * ARNet input and of its CoC is replaced by a pull-push fill of known background
 * (alpha<=0 evidence). The sharp subject is still restored from the original photo later.
 *
 * Pixels with alpha<=0 are bit-identical. No fill when there is no hole or no evidence.
 * This is a smooth background estimate, not semantic inpainting of hidden content.
 */
object BackgroundPlate {
    const val EVIDENCE_ALPHA=.02f
    internal fun evidence(a:Float):Float = if(a<=0f)1f else if(a>=EVIDENCE_ALPHA)0f else 1f-a/EVIDENCE_ALPHA

    private fun up(f:FloatArray,pw:Int,ph:Int,x:Int,y:Int):Float {
        val sx=((x+.5f)*.5f-.5f).coerceIn(0f,(pw-1).toFloat());val sy=((y+.5f)*.5f-.5f).coerceIn(0f,(ph-1).toFloat())
        val x0=sx.toInt();val y0=sy.toInt();val x1=min(pw-1,x0+1);val y1=min(ph-1,y0+1);val fx=sx-x0;val fy=sy-y0
        return (f[y0*pw+x0]*(1-fx)+f[y0*pw+x1]*fx)*(1-fy)+(f[y1*pw+x0]*(1-fx)+f[y1*pw+x1]*fx)*fy
    }

    /** Fills every channel of every frame in place. Returns false when nothing changed. */
    fun fillInPlace(alpha:Frame,cancel:Cancel,vararg frames:Frame):Boolean {
        cancel.check()
        require(alpha.channels==1 && frames.all{it.w==alpha.w && it.h==alpha.h})
        val w=alpha.w;val h=alpha.h;val n=w*h
        val wt=FloatArray(n){i->if(i and 65535==0)cancel.check();val a=alpha.data[i];require(a.isFinite());evidence(a.coerceIn(0f,1f))}
        var hole=false;var known=false
        for(v in wt){if(v<1f)hole=true;if(v>0f)known=true;if(hole && known)break}
        if(!hole || !known || n==1)return false
        val ws=arrayListOf(w);val hs=arrayListOf(h)
        while(ws.last()>1 || hs.last()>1){ws.add((ws.last()+1)/2);hs.add((hs.last()+1)/2)}
        val levels=ws.size
        // Pull: clamped child weights summed into the parent.
        val weight=arrayOfNulls<FloatArray>(levels);weight[0]=wt
        for(l in 1 until levels){val pw=ws[l-1];val ph=hs[l-1];val cw=ws[l];val prev=weight[l-1]!!;val cur=FloatArray(cw*hs[l])
            for(y in 0 until ph){if(y and 63==0)cancel.check();val row=(y/2)*cw;for(x in 0 until pw)cur[row+x/2]+=min(1f,prev[y*pw+x])}
            weight[l]=cur
        }
        for(frame in frames)for(c in 0 until frame.channels){
            val off=c*n;val value=arrayOfNulls<FloatArray>(levels)
            for(l in 1 until levels){val pw=ws[l-1];val ph=hs[l-1];val cw=ws[l];val prevW=weight[l-1]!!;val cur=FloatArray(cw*hs[l])
                val prevV=value[l-1]
                for(y in 0 until ph){if(y and 63==0)cancel.check();val row=(y/2)*cw
                    for(x in 0 until pw){val j=y*pw+x;val k=min(1f,prevW[j]);if(k<=0f)continue
                        cur[row+x/2]+=k*(prevV?.get(j)?:frame.data[off+j])
                    }
                }
                val sw=weight[l]!!;for(j in cur.indices)if(sw[j]>0f)cur[j]/=sw[j]
                value[l]=cur
            }
            // Push: coarse estimate fills where the finer level lacks evidence.
            for(l in levels-2 downTo 1){val cw=ws[l];val ch=hs[l];val pw=ws[l+1];val ph=hs[l+1];val v=value[l]!!;val parent=value[l+1]!!;val k=weight[l]!!
                for(y in 0 until ch){if(y and 63==0)cancel.check();for(x in 0 until cw){val j=y*cw+x;val t=min(1f,k[j]);if(t<1f)v[j]=t*v[j]+(1-t)*up(parent,pw,ph,x,y)}}
            }
            val first=value[1]!!;val pw=ws[1];val ph=hs[1]
            parallelFor(h){y->if(y and 31==0)cancel.check()
                for(x in 0 until w){val i=y*w+x;val t=wt[i];if(t>=1f)continue
                    frame.data[off+i]=t*frame.data[off+i]+(1-t)*up(first,pw,ph,x,y)
                }
            }
        }
        cancel.check();return true
    }
}
