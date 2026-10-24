package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Wide mixed bands can exceed NaturalSubjectEdges' local background window. A fallback
 * a*I+(1-a)*B' squares an already mixed I=aF+(1-a)B and draws a dark contour.
 * Extend evidence only for locally flat, demonstrably two-colour mixtures. Textured,
 * sloped, translucent/no-opaque-reference and mismatched-alpha cases keep the old result.
 * No matte changes, no full-frame float scratch, no invented foreground detail. */
internal object WideSubjectEdges {
    private val directions=arrayOf(1 to 0,-1 to 0,0 to 1,0 to -1,1 to 1,-1 to 1,1 to -1,-1 to -1)
    private fun smooth(v:Double):Double{val t=v.coerceIn(0.0,1.0);return t*t*(3-2*t)}
    fun refine(source:Planes,rendered:Planes,a:Frame,out:Planes,cancel:Cancel):Planes {
        val w=source.width;val h=source.height
        val local=max(5,ceil(max(w,h)/1220.0*5).toInt()).coerceAtMost(32)
        val limit=(local*16).coerceAtMost(128)
        fun known(i:Int,fg:Boolean)=if(fg)a.data[i]>=1f else a.data[i]<=0f
        fun sample(x:Int,y:Int,fg:Boolean):DoubleArray? {
            val sums=DoubleArray(6);val squares=DoubleArray(6);var count=0
            for(yy in max(0,y-1)..min(h-1,y+1))for(xx in max(0,x-1)..min(w-1,x+1)){
                val i=yy*w+xx;if(!known(i,fg))continue;count++
                for(c in 0..2){val s=(source.plane(c)[i].toInt() and 255).toDouble();val b=(rendered.plane(c)[i].toInt() and 255).toDouble()
                    sums[c]+=s;squares[c]+=s*s;sums[c+3]+=b;squares[c+3]+=b*b}
            }
            if(count<4)return null
            var sigma=0.0
            for(c in sums.indices){sums[c]/=count;if(c<3 || !fg)sigma=max(sigma,sqrt(max(0.0,squares[c]/count-sums[c]*sums[c])))}
            if(sigma>=3.0)return null
            return sums+smooth((3.0-sigma)/1.5)
        }
        for(y in 0 until h){cancel.check();for(x in 0 until w){
            val i=y*w+x;val t=a.data[i].toDouble();if(t<=0 || t>=1)continue
            var fi=-1;var bi=-1;var fd=0;var bd=0
            search@ for(d in 1..limit){for((dx,dy) in directions){val xx=x+dx*d;val yy=y+dy*d;if(xx !in 0 until w || yy !in 0 until h)continue
                val j=yy*w+xx
                if(fi<0 && known(j,true)){fi=j;fd=d}
                if(bi<0 && known(j,false)){bi=j;bd=d}
            };if(fi>=0 && bi>=0)break@search}
            if(fi<0 || bi<0 || fd+bd<=local+1)continue
            val f=sample(fi%w,fi/w,true)?:continue;val b=sample(bi%w,bi/w,false)?:continue
            var error=0.0
            for(c in 0..2){val s=(source.plane(c)[i].toInt() and 255).toDouble();error=max(error,abs(s-(t*f[c]+(1-t)*b[c])))}
            val confidence=f[6]*b[6]*smooth((3.0-error)/1.5)
            if(confidence<=0)continue
            for(c in 0..2){val sourceValue=(source.plane(c)[i].toInt() and 255).toDouble();val prior=(out.plane(c)[i].toInt() and 255).toDouble()
                val target=(sourceValue+(1-t)*(b[c+3]-b[c])).coerceIn(0.0,255.0)
                out.plane(c)[i]=Math.rint(prior+(target-prior)*confidence).toInt().coerceIn(0,255).toByte()
            }
        }}
        return out
    }
}
