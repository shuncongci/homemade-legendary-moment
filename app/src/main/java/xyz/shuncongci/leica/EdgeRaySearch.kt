package xyz.shuncongci.leica

import kotlin.math.*

/** Exact eight-ray nearest-known search for wide matte edges. Same distance-first,
 * direction-second tie break as WideSubjectEdges 67. Dynamic ray recurrence avoids
 * rewalking up to 128 pixels for every receiver. Scratch is a 32-row core + four rows,
 * not eight full-photo distance maps; no matte values or sample positions are changed. */
internal object EdgeRaySearch {
    private val dx=intArrayOf(1,-1,0,0,1,-1,1,-1)
    private val dy=intArrayOf(0,0,1,-1,1,1,-1,-1)
    data class Result(val left:Int,val span:Int,val fg:IntArray,val bg:IntArray,val fd:IntArray,val bd:IntArray)
    fun find(a:FloatArray,w:Int,h:Int,top:Int,end:Int,left:Int,right:Int,limit:Int,cancel:Cancel):Result {
        require(w>0 && h>0 && a.size==w*h && top>=0 && end in top+1..h && left>=0 && right in left until w && limit in 1..128)
        val span=right-left+1;val n=span*(end-top);val inf=limit+1
        val out=Result(left,span,IntArray(n){-1},IntArray(n){-1},IntArray(n){inf},IntArray(n){inf})
        val x0=max(0,left-limit);val x1=min(w-1,right+limit);val sw=x1-x0+1
        for(dir in dx.indices){
            val sx=dx[dir];val sy=dy[dir];val y0=if(sy<0)max(0,top-limit)else top;val y1=if(sy>0)min(h,end+limit)else end
            var pf=IntArray(sw){inf};var pb=IntArray(sw){inf};var cf=IntArray(sw);var cb=IntArray(sw)
            val ys=if(sy>0)y1-1 downTo y0 else y0 until y1
            val xs=if(sy==0 && sx>0)sw-1 downTo 0 else 0 until sw
            for(y in ys){cancel.check()
                for(k in xs){val x=x0+k;val nx=k+sx;val neighbor=nx in 0 until sw
                    val f=if(a[y*w+x]>=1f)0 else if(neighbor)min(inf,1+(if(sy==0)cf[nx]else pf[nx]))else inf
                    val b=if(a[y*w+x]<=0f)0 else if(neighbor)min(inf,1+(if(sy==0)cb[nx]else pb[nx]))else inf
                    cf[k]=f;cb[k]=b
                    if(y in top until end && x in left..right){val t=a[y*w+x]
                        if(t>0f && t<1f){val j=(y-top)*span+x-left
                            if(f<out.fd[j]){out.fd[j]=f;out.fg[j]=(y+sy*f)*w+x+sx*f}
                            if(b<out.bd[j]){out.bd[j]=b;out.bg[j]=(y+sy*b)*w+x+sx*b}
                        }
                    }
                }
                if(sy!=0){val tf=pf;pf=cf;cf=tf;val tb=pb;pb=cb;cb=tb}
            }
        };return out
    }
}
