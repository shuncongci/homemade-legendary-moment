package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.*

class HighlightScatterTest {
    private fun reflect(x0:Int,n:Int):Int {if(n==1)return 0;var x=x0;while(x<0 || x>=n)x=if(x<0)-x else 2*n-2-x;return x}
    private fun filter(f:Frame,radius:Int,ap:Float):Frame {
        val k=LegendaryMath.kernel(radius,ap);val out=FloatArray(f.data.size)
        for(c in 0 until f.channels)for(y in 0 until f.h)for(x in 0 until f.w){var sum=0.0
            for(ky in 0 until k.w)for(kx in 0 until k.w)sum+=f.data[c*f.size+reflect(y+ky-k.half,f.h)*f.w+reflect(x+kx-k.half,f.w)]*k.data[ky*k.w+kx].toDouble()
            out[c*f.size+y*f.w+x]=sum.toFloat()
        };return Frame(f.w,f.h,f.channels,out)
    }
    private fun maxDiff(a:FloatArray,b:FloatArray)=a.indices.maxOf{abs(a[it]-b[it])}
    @Test fun independentOpenCvFixturesAllSelectedApertures(){
        for(index in 0..2){
            val bytes=javaClass.classLoader!!.getResourceAsStream("highlight-v4/v4-$index.bin")!!.use{it.readBytes()}
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val w=b.int;val h=b.int;val ap=b.float
            val high=Frame(w,h,3,FloatArray(w*h*3){b.float});val coc=Frame(w,h,1,FloatArray(w*h){b.float});val expected=FloatArray(w*h*3){b.float}
            assertFalse(b.hasRemaining());val actual=HighlightScatter.scatter(high,coc,ap,Cancel())
            val error=maxDiff(expected,actual.data);println("V4 independent OpenCV f/$ap max linear error=$error");assertTrue(error<3e-5f)
        }
    }
    @Test fun constantRadianceAcrossDepthDoesNotDimOrBoost(){
        val random=java.util.Random(22);val high=Frame(35,23,3,FloatArray(35*23*3){.37f})
        val coc=Frame(35,23,1,FloatArray(35*23){random.nextFloat()*18-9})
        for(ap in LegendaryMath.apertures)assertTrue(maxDiff(high.data,HighlightScatter.scatter(high,coc,ap,Cancel()).data)<2e-6f)
    }
    @Test fun fractionalFlatPlaneIsExactPsfInterpolationNoGhostCore(){
        val random=java.util.Random(13);val high=Frame(29,23,3,FloatArray(29*23*3){random.nextFloat()*.75f})
        for(value in floatArrayOf(-3.7f,-.3f,.3f,4.8f)){
            val lo=floor(value).toInt();val f=value-lo
            val a=filter(high,abs(lo),2.8f);val b=filter(high,abs(lo+1),2.8f)
            val expected=FloatArray(high.data.size){a.data[it]*(1-f)+b.data[it]*f}
            val actual=HighlightScatter.scatter(high,Frame(high.w,high.h,1,FloatArray(high.size){value}),2.8f,Cancel())
            assertTrue("value=$value",maxDiff(expected,actual.data)<3e-6f)
        }
    }
    @Test fun zeroCocIdentityAndInputsNotMutated(){
        val random=java.util.Random(9);val high=Frame(17,19,3,FloatArray(17*19*3){random.nextFloat()})
        val original=high.data.copyOf();val coc=Frame(17,19,1,FloatArray(17*19))
        val out=HighlightScatter.scatter(high,coc,2.8f,Cancel());assertArrayEquals(original,high.data,0f);assertArrayEquals(original,out.data,0f);assertTrue(coc.data.all{it==0f})
    }
    @Test fun isolatedPointStillSpreadsAndConservesPlaneEnergy(){
        val high=Frame(81,81,3,FloatArray(81*81*3));for(c in 0..2)high.data[c*high.size+40*81+40]=.7f
        val out=HighlightScatter.scatter(high,Frame(81,81,1,FloatArray(81*81){-9f}),2.8f,Cancel())
        assertEquals(2.1,out.data.sumOf{it.toDouble()},2e-5);assertTrue(out.data.take(high.size).count{it>1e-7f}>100)
        assertTrue(maxDiff(out.data,filter(high,9,2.8f).data)<1e-6f)
    }
    private fun filament(value:Float,width:Int=1):Float {
        val w=65;val n=w*w;val high=Frame(w,w,3,FloatArray(n*3){.5f});val coc=Frame(w,w,1,FloatArray(n){-10f})
        val x0=32-width/2
        for(y in 0 until w)for(x in x0 until x0+width){val i=y*w+x;coc.data[i]=value;for(c in 0..2)high.data[c*n+i]=0f}
        return HighlightScatter.scatter(high,coc,2.8f,Cancel()).data[32*w+32]
    }
    @Test fun distantBackgroundNoLongerFloodsNearFocusFilament(){
        assertEquals(0f,filament(0f),1e-7f)
        // Old V1 was 0.1890576 at ±0.51; no photo-specific mask involved.
        assertTrue(filament(.51f)<.025f);assertTrue(filament(-.51f)<.025f)
    }
    @Test fun noHalfBinJumpAndNoNewIntegerBinJump(){
        for(width in intArrayOf(1,5))for(boundary in floatArrayOf(-1f,-.5f,0f,.5f,1f))
            assertTrue(abs(filament(boundary-.0001f,width)-filament(boundary+.0001f,width))<.001f)
    }
    @Test fun defocusedForegroundRemainsSoftNotHardProtected(){assertTrue(filament(8f)>filament(.51f)*5)}
    @Test fun visibilityIsAsymmetricAndNotSemantic(){
        assertEquals(1f,HighlightScatter.visibility(-9f,-9),0f)
        assertEquals(1f,HighlightScatter.visibility(-9f,2),0f)
        assertEquals(.05f,HighlightScatter.visibility(.5f,-10),1e-6f)
        assertEquals(1f,HighlightScatter.visibility(12f,-10),0f)
    }
    @Test fun tentWeightsAlwaysPartitionUnity(){for(i in -5400..5400){val c=i/100f;val lo=floor(c).toInt();assertEquals(1f,HighlightScatter.layerWeight(c,lo)+HighlightScatter.layerWeight(c,lo+1),1e-6f)}}
    @Test fun cancelsBeforeAllocationAndDuringLayers(){
        val high=Frame(3,4,3,FloatArray(36){.5f});val coc=Frame(3,4,1,FloatArray(12){1f})
        try{HighlightScatter.scatter(high,coc,2.8f,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}
        val n=512*512;val c=Cancel();val errors=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread=Thread{try{HighlightScatter.scatter(Frame(512,512,3,FloatArray(n*3){.5f}),Frame(512,512,1,FloatArray(n){(it%512)/511f*100-50}),.95f,c)}catch(t:Throwable){errors.set(t)}}
        thread.start();Thread.sleep(20);c.cancelled=true;thread.join(15000);assertFalse(thread.isAlive)
        var cause=errors.get();while(cause?.cause!=null)cause=cause.cause;assertTrue(cause is CancellationException)
    }
    @Test fun nonfiniteInvalidSizeAndRadiusRejected(){
        val high=Frame(3,4,3,FloatArray(36){.5f})
        for(v in floatArrayOf(Float.NaN,Float.POSITIVE_INFINITY,54.1f))try{HighlightScatter.scatter(high,Frame(3,4,1,FloatArray(12){v}),2.8f,Cancel());fail()}catch(_:IllegalArgumentException){}
        try{HighlightScatter.scatter(high,Frame(4,3,1,FloatArray(12)),2.8f,Cancel());fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun tinyDimensionsAndAllBlackRemainFinite(){
        for((w,h) in listOf(1 to 1,1 to 9,9 to 1)){
            val n=w*h;val random=java.util.Random(17);val coc=Frame(w,h,1,FloatArray(n){random.nextFloat()*100-50})
            val out=HighlightScatter.scatter(Frame(w,h,3,FloatArray(n*3)),coc,.95f,Cancel());assertTrue(out.data.all{it==0f})
            val bright=HighlightScatter.scatter(Frame(w,h,3,FloatArray(n*3){.7f}),coc,.95f,Cancel());assertTrue(bright.data.all{it.isFinite() && abs(it-.7f)<3e-6f})
        }
    }
    @Test fun rendererDefaultsToFieldOpticalWithV4CoverageNotLegacy(){
        val p=Planes(41,29);for(i in 0 until p.size)for(c in 0..2)p.plane(c)[i]=(if(i%41 in 15..18 && i/41 in 12..16)245 else 30).toByte()
        val coc=Frame(p.width,p.height,1,FloatArray(p.size){if(it%41 in 19..21).51f else -10f})
        val actual=LegendaryMath.renderCoc(p,coc,2.8f,Cancel()){a,_->a}
        // 2.1.18: production = clipped-light recovery, field optical + V4 coverage, then soft rims and highlight shoulder.
        val expected=LegendaryMath.renderCoc(p,coc,2.8f,Cancel(),highlightRenderer={h,c,ap,t->SoftBokehEdges.soften(HighlightScatter.optical(h,c,ap,t),c,t)},highlightShoulder=true,clippedRecovery=true){a,_->a}
        val legacy=LegendaryMath.renderCoc(p,coc,2.8f,Cancel(),highlightRenderer=LegacyHighlightScatter::scatter){a,_->a}
        assertArrayEquals(expected.p0,actual.p0);assertFalse(actual.p0.contentEquals(legacy.p0))
    }
}
