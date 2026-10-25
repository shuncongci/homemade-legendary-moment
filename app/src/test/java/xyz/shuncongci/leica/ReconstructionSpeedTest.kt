package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Frozen scalar formulas, exact bits; speed is measured separately without asserting wall-clock timing. */
class ReconstructionSpeedTest {
    private fun bits(a:FloatArray,b:FloatArray){assertEquals(a.size,b.size);for(i in a.indices)assertEquals("bits at $i",a[i].toRawBits(),b[i].toRawBits())}
    // Original LegendaryMath.resize from the frozen a947480d... source, including accumulation order.
    private fun reference(f:Frame,w:Int,h:Int,cubic:Boolean,align:Boolean):Frame {
        if(w==f.w && h==f.h)return f
        fun kernel(x0:Float):Float {val x=abs(x0);val a=-.75f;return if(x<=1f)(a+2)*x*x*x-(a+3)*x*x+1 else if(x<2)a*x*x*x-5*a*x*x+8*a*x-4*a else 0f}
        val out=Frame(w,h,f.channels,FloatArray(w*h*f.channels))
        for(c in 0 until f.channels)for(y in 0 until h){val sy=if(align)if(h==1)0f else y*(f.h-1f)/(h-1)else(y+.5f)*f.h/h-.5f;val iy=floor(sy).toInt();val fy=sy-iy
            for(x in 0 until w){val sx=if(align)if(w==1)0f else x*(f.w-1f)/(w-1)else(x+.5f)*f.w/w-.5f;val ix=floor(sx).toInt();val fx=sx-ix;var sum=0f
                if(cubic)for(dy in -1..2)for(dx in -1..2)sum+=f.data[c*f.size+(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*kernel(dy-fy)*kernel(dx-fx)
                else for(dy in 0..1)for(dx in 0..1)sum+=f.data[c*f.size+(iy+dy).coerceIn(0,f.h-1)*f.w+(ix+dx).coerceIn(0,f.w-1)]*(if(dy==0)1-fy else fy)*(if(dx==0)1-fx else fx)
                out.data[c*out.size+y*w+x]=sum
            }
        };return out
    }
    private fun resize(cubic:Boolean,align:Boolean){val r=Random(167)
        for(source in listOf(1 to 1,1 to 17,19 to 1,31 to 23,47 to 59))for(ch in listOf(1,3,4)){
            val f=Frame(source.first,source.second,ch,FloatArray(source.first*source.second*ch){r.nextFloat()*13-3});val before=f.data.copyOf()
            for(target in listOf(1 to 1,1 to 29,27 to 1,17 to 13,67 to 41))bits(reference(f,target.first,target.second,cubic,align).data,LegendaryMath.resize(f,target.first,target.second,cubic,align,Cancel()).data)
            bits(before,f.data)
        }
    }
    @Test fun linearHalfPixelGeometryAllBits()=resize(false,false)
    @Test fun cubicHalfPixelGeometryAllBits()=resize(true,false)
    @Test fun linearAlignedGeometryAllBits()=resize(false,true)
    @Test fun cubicAlignedGeometryAllBits()=resize(true,true)
    @Test fun byteLinearShortcutAll256InputsExact(){for(code in 0..255){val x=(Math.rint(code.toDouble())/255).toFloat();assertEquals(HdrMath.linear(x).toRawBits(),LegendaryMath.linearQuantized(x).toRawBits())}}
    @Test fun continuousValuesAroundEveryByteDoNotQuantize(){for(code in 0..255)for(x in listOf(Math.nextDown(code/255f),Math.nextUp(code/255f))){assertEquals(HdrMath.linear(x).toRawBits(),LegendaryMath.linearQuantized(x).toRawBits())}}
    @Test fun continuousAndSignedZeroLinearRemainExact(){val r=Random(37);for(x in listOf(-0f,0f,-1f,Float.MIN_VALUE,Float.MAX_VALUE,Float.POSITIVE_INFINITY,Float.NaN)+List(10001){r.nextFloat()*3-1})assertEquals(HdrMath.linear(x).toRawBits(),LegendaryMath.linearQuantized(x).toRawBits())}
    private fun source(p3:Boolean,scale:Int){
        val sw=257;val sh=193;val gw=65;val gh=49;val raw=Planes(sw,sh);val gain=Planes(gw,gh);val random=Random(83);for(c in 0..2){random.nextBytes(raw.plane(c));random.nextBytes(gain.plane(c))}
        for(exif in 1..8){val calls=AtomicInteger();val input=HdrReconstruction.Input(sw,sh,gw,gh,{x,y,c->calls.incrementAndGet();raw.plane(c)[y*sw+x].toInt()and 255},{x,y,c->gain.plane(c)[y*gw+x].toInt()and 255},FloatArray(256){HdrMath.linear(it/255f)},floatArrayOf(.25f,.5f,1f),floatArrayOf(4f,8f,16f),floatArrayOf(.5f,1f,2f),floatArrayOf(.015625f,0f,.003f),floatArrayOf(.015625f,.002f,0f),p3)
            val ow=if(exif>=5)sh else sw;val oh=if(exif>=5)sw else sh
            val w=if(scale==0)ow else if(scale==1)ow-1 else ow/5;val h=if(scale==0)oh else if(scale==1)oh-1 else oh/5
            val ref=BaselineHdrReconstruction.render(input,exif,w,h,Cancel());calls.set(0)
            val colored=Planes(w,h);for(c in 0..2)random.nextBytes(colored.plane(c));val expected=BaselineHdrMath.color(ref.first,ref.second,colored,Cancel())
            val actual=HdrReconstruction.render(input,exif,w,h,colored,true,Cancel());bits(expected.data,actual.hdr.data);bits(ref.first.data,actual.sdr!!.data)
            if(scale==1)assertTrue("native radiance should be reused instead of four reconstructions: ${calls.get()}",calls.get()<=3L*ow*(oh+12))
        }
    }
    @Test fun nearNativeSrgbSamplesAndAllExifExact()=source(false,1)
    @Test fun nearNativeP3SamplesAndAllExifExact()=source(true,1)
    @Test fun unchangedNativeNoCacheBitsExact()=source(true,0)
    @Test fun largeAreaRatioFallbackBitsExact()=source(true,2)
    @Test fun resizeStillHonorsCancellation(){try{LegendaryMath.resize(Frame(7,9,3,FloatArray(189)),31,23,cancel=Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
}
