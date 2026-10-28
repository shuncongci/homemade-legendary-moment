package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*
import java.util.concurrent.CancellationException

class ExactSpeedMathTest {
    /** Frozen 67 row/column sliding evaluation, not a mathematically re-associated reference. */
    private fun reference(w:Int,h:Int,top:Int,end:Int,r:Int,asFloat:Boolean,value:(Int)->Double):DoubleArray {
        val y0=max(0,top-r);val y1=min(h,end+r);val rows=y1-y0;val horizontal=DoubleArray(w*rows);val dst=DoubleArray(w*rows)
        for(yy in 0 until rows){var sum=0.0;var left=0;var right=-1
            for(x in 0 until w){val lo=max(0,x-r);val hi=min(w-1,x+r)
                while(right<hi){right++;sum+=value((yy+y0)*w+right)}
                while(left<lo){sum-=value((yy+y0)*w+left);left++}
                horizontal[yy*w+x]=if(asFloat)sum.toFloat().toDouble()else sum
            }
        }
        for(x in 0 until w){var sum=0.0;var lo=0;var hi=-1
            for(yy in 0 until rows){val a=max(0,yy-r);val b=min(rows-1,yy+r)
                while(hi<b){hi++;sum+=horizontal[hi*w+x]};while(lo<a){sum-=horizontal[lo*w+x];lo++}
                dst[yy*w+x]=if(asFloat)sum.toFloat().toDouble()else sum
            }
        };return dst
    }
    private fun boxes(w:Int,h:Int,top:Int,end:Int,r:Int,left:Int,right:Int){
        val s=EdgeBoxSums(w,h,top,end,r,left,right)
        // Deliberately non-integer signed values expose any add/subtract reassociation.
        val v=DoubleArray(w*h){sin(it*.131)*271.8+it%17*.0001}
        val ref=reference(w,h,top,end,r,false){v[it]};val got=s.f64(DoubleArray(s.span*s.rows),Cancel()){v[it]}
        val vf=FloatArray(w*h){v[it].toFloat()};val rf=reference(w,h,top,end,r,true){vf[it].toDouble()};val gf=s.f32(FloatArray(s.span*s.rows),Cancel()){vf[it]}
        for(y in top until end)for(x in left..right){val k=(y-top)*s.span+x-left;val j=(y-s.y0)*w+x
            assertEquals("double $x,$y",ref[j].toRawBits(),got[k].toRawBits());assertEquals("float $x,$y",rf[j].toFloat().toRawBits(),gf[k].toRawBits())
        }
    }
    @Test fun fullStripExact()=boxes(131,97,19,83,15,0,130)
    @Test fun croppedStripExact()=boxes(137,111,32,96,32,37,93)
    @Test fun tinyEdgeExact()=boxes(7,5,0,5,12,1,3)
    @Test fun onePixelExact()=boxes(1,1,0,1,5,0,0)
    @Test fun oneColumnExact()=boxes(1,97,12,64,15,0,0)
    @Test fun oneRowExact()=boxes(129,1,0,1,32,43,107)
    @Test fun lowerImageBorderExact()=boxes(131,99,64,99,64,12,128)
    @Test fun zeroRadiusExact()=boxes(43,19,3,17,0,11,21)
    @Test fun mixedRangeIsExact(){val a=FloatArray(33*8);a[3*33+4]=.1f;a[4*33+27]=.99f;a[2*33+1]=1f;assertEquals(4..27,EdgeBoxSums.mixed(a,33,2,6,Cancel()))}
    @Test fun noMixedRange(){assertNull(EdgeBoxSums.mixed(floatArrayOf(0f,1f,0f,1f),2,0,2,Cancel()))}
    @Test(expected=CancellationException::class) fun boxCancellation(){EdgeBoxSums(16,16,0,16,4,0,15).f64(DoubleArray(256),Cancel().apply{cancelled=true}){1.0}}
    private fun oldBase(p:Planes,i:Int,cap:Float):FloatArray {
        val v=FloatArray(3){HdrMath.linearByte(p.plane(it)[i].toInt()and 255)}
        val ratio=min(1f,cap/max(max(v[0],max(v[1],v[2])),1e-8f))
        return FloatArray(3){val x=LegendaryMath.srgb(v[it]*ratio);(Math.rint((x.coerceIn(0f,1f)*255).toDouble())/255).toFloat()}
    }
    private fun base(cap:Float){val p=Planes(4096,1);for(c in 0..2)for(i in 0 until p.size)p.plane(c)[i]=((i*37+c*73+i/256)%256).toByte();val got=FloatArray(3)
        for(i in 0 until p.size){val old=oldBase(p,i,cap);LegendaryMath.basePixel(p,i,cap,got);for(c in 0..2){assertEquals(old[c].toRawBits(),got[c].toRawBits());assertEquals(old[c].toRawBits(),LegendaryMath.baseChannel(p,i,cap,c).toRawBits())}}
    }
    @Test fun unscaledBaseExact()=base(1f)
    @Test fun clippedBaseExact()=base(.25f)
    @Test fun zeroBaseExact()=base(0f)
    @Test fun partialBaseExact()=base(.726315f)
    @Test fun allByteRoundTripsExact(){val p=Planes(256,1);for(c in 0..2)for(i in 0..255)p.plane(c)[i]=i.toByte();val got=FloatArray(3);for(i in 0..255){LegendaryMath.basePixel(p,i,1f,got);assertArrayEquals(oldBase(p,i,1f),got,0f)}}
}
