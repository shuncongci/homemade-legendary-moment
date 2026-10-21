package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Frozen pre-2.1.14 exhaustive 8x8 pupil sampler, independent of production shortcuts. */
class PupilKernelExactTest {
    private fun reference(radius:Int,aperture:Float):FloatArray {
        if(radius==0)return floatArrayOf(1f)
        val half=ceil(radius+.5).toInt();val w=half*2+1;val a=FloatArray(w*w)
        val round=when(aperture){.95f->1.0;1.8f->.85;2f->.8;2.8f->.65;4f->.45;else->.25}
        for(y in 0 until w)for(x in 0 until w){var covered=0
            for(yy in 0 until 8)for(xx in 0 until 8){val dx=x+(xx+.5)/8-.5-half;val dy=y+(yy+.5)/8-.5-half
                val theta=atan2(dy,dx);val period=2*PI/9;val sector=((theta+PI/9)%period+period)%period-PI/9
                val polygon=cos(PI/9)/cos(sector);val boundary=radius*(round+(1-round)*polygon)
                if(hypot(dx,dy)<=boundary)covered++
            };a[y*w+x]=covered/64f
        };val sum=a.sum();for(i in a.indices)a[i]/=sum;return a
    }
    private fun fieldReference(r:Int,ap:Float,s:OpticalPupil.Shape):FloatArray {
        val half=r+1;val w=2*half+1;val out=FloatArray(w*w)
        val round=when(ap){.95f->1.0;1.8f->.85;2f->.8;2.8f->.65;4f->.45;else->.25}
        for(y in 0 until w)for(x in 0 until w){var hits=0
            for(yy in 0..7)for(xx in 0..7){val dx=x-half+(xx+.5)/8-.5;val dy=y-half+(yy+.5)/8-.5
                val radial=(dx*s.ux+dy*s.uy)*(1/s.radialRatio-1);val px=dx+radial*s.ux;val py=dy+radial*s.uy
                val period=2*PI/9;val theta=atan2(py,px);val sector=((theta+PI/9)%period+period)%period-PI/9
                if(hypot(px,py)<=r*(round+(1-round)*cos(PI/9)/cos(sector)))hits++
            };out[y*w+x]=hits/64f
        };val sum=out.sumOf{it.toDouble()};for(i in out.indices)out[i]=(out[i]/sum).toFloat();return out
    }
    private fun compare(ap:Float){
        for(r in listOf(0,1,2,5,12,27,54))assertArrayEquals("aperture=$ap radius=$r",reference(r,ap),LegendaryMath.kernel(r,ap).data,0f)
        for(r in listOf(1,5,12,27))for((x,y) in listOf(.75 to .5,1.0 to 1.0)){
            val s=OpticalPupil.shape(x,y,513,385)
            assertArrayEquals("field aperture=$ap radius=$r at $x,$y",fieldReference(r,ap,s),OpticalPupil.kernel(r,ap,s,Cancel()).data,0f)
        }
    }
    @Test fun aperture095Exact(){compare(.95f)}
    @Test fun aperture18Exact(){compare(1.8f)}
    @Test fun aperture20Exact(){compare(2f)}
    @Test fun aperture28Exact(){compare(2.8f)}
    @Test fun aperture40Exact(){compare(4f)}
    @Test fun aperture56Exact(){compare(5.6f)}
}
