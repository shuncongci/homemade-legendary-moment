package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.concurrent.CancellationException

class FullFrameOpticsTest {
    private fun ramp(w:Int=101,h:Int=31)=LegendaryMath.Frame(w,h,1,FloatArray(w*h){(it%w)/(w-1f)})
    @Test fun apertureDiameterIsExactlyInverseF(){
        for(n in LegendaryMath.apertures)assertEquals(FullFrameOptics.diameterMm(7000.0,3000.0,2f)*2,FullFrameOptics.diameterMm(7000.0,3000.0,n)*n,1e-10)
    }
    @Test fun exactThinLensIndependentGeometricConstruction(){
        val f=50.0
        for(n in LegendaryMath.apertures)for(s in listOf(1200.0,3000.0,8000.0,30000.0))for(z in listOf(1200.0,2000.0,8000.0,30000.0)){
            val imageZ=f*z/(z-f);val imageS=f*s/(s-f)
            // Signed convention is near-positive, far-negative (as required by V4 occlusion).
            val diameter=(f/n)*(1-imageS/imageZ)
            assertEquals(diameter,FullFrameOptics.diameterMm(z,s,n),1e-12)
        }
    }
    @Test fun independentStandardHyperfocalValues(){
        assertEquals(29811.9047619,FullFrameOptics.limits(3000.0,2.8f).hyperfocalMm,.002)
        assertEquals(87769.2982456,FullFrameOptics.limits(3000.0,.95f).hyperfocalMm,.002)
    }
    @Test fun focalPlaneIsZeroAndNearFarHaveCorrectSign(){
        for(n in LegendaryMath.apertures){assertEquals(0.0,FullFrameOptics.diameterMm(3000.0,3000.0,n),0.0);assertTrue(FullFrameOptics.diameterMm(2000.0,3000.0,n)>0);assertTrue(FullFrameOptics.diameterMm(7000.0,3000.0,n)<0)}
    }
    @Test fun farFocusPhysicallyWiderForEveryAperture(){
        for(n in LegendaryMath.apertures){var last=0.0;for(s in listOf(1200.0,2000.0,3000.0,5000.0,8000.0)){val l=FullFrameOptics.limits(s,n);assertTrue(l.farMm-l.nearMm>last);last=l.farMm-l.nearMm}}
    }
    @Test fun everyApertureMonotonicallyWidensDof(){
        for(s in listOf(1200.0,3000.0,8000.0)){var last=0.0;for(n in LegendaryMath.apertures){val l=FullFrameOptics.limits(s,n);assertTrue(l.farMm-l.nearMm>last);last=l.farMm-l.nearMm}}
    }
    @Test fun nearFarLimitsMatchSameSensorCircleCriterion(){
        for(n in LegendaryMath.apertures)for(s in listOf(1200.0,3000.0,8000.0)){
            val l=FullFrameOptics.limits(s,n)
            assertEquals(.03,abs(FullFrameOptics.diameterMm(l.nearMm,s,n)),1e-12)
            assertEquals(.03,abs(FullFrameOptics.diameterMm(l.farMm,s,n)),1e-12)
            assertTrue(l.farMm-s>s-l.nearMm)
        }
    }
    @Test fun hyperfocalFarLimitActuallyInfinite(){
        for(n in LegendaryMath.apertures){val h=FullFrameOptics.limits(3000.0,n).hyperfocalMm;val l=FullFrameOptics.limits(h,n);assertTrue(l.farMm.isInfinite());assertEquals(h/2,l.nearMm,1e-7);assertEquals(.03,abs(FullFrameOptics.diameterMm(Double.POSITIVE_INFINITY,h,n)),1e-10)}
    }
    @Test fun virtualInverseDepthAnchorsAreExplicitAndMonotonic(){
        assertEquals(30000.0,FullFrameOptics.virtualDistance(0f),1e-8);assertEquals(1200.0,FullFrameOptics.virtualDistance(1f),1e-8)
        var last=Double.POSITIVE_INFINITY;for(i in 0..1000){val s=FullFrameOptics.virtualDistance(i/1000f);assertTrue(s<last);last=s}
    }
    @Test fun sensorCropAndOutputRadiusScaleAreConsistent(){
        assertEquals(36.0,FullFrameOptics.sensorLong(3000,2000),0.0);assertEquals(32.0,FullFrameOptics.sensorLong(3000,4000),0.0);assertEquals(24.0,FullFrameOptics.sensorLong(1000,1000),0.0)
        assertEquals(2*FullFrameOptics.radiusScale(.5f,2.8f,1536,2048),FullFrameOptics.radiusScale(.5f,2.8f,3072,4096),0f)
        assertEquals(FullFrameOptics.radiusScale(.5f,2.8f,1536,2048),FullFrameOptics.radiusScale(.5f,2.8f,2048,1536),0f)
    }
    @Test fun opticsDoesNotIntroduceDeadZoneOrMutateDepth(){
        val d=ramp();val before=d.data.copyOf();val out=FullFrameOptics.coc(d,.5f,2.8f,d.w,d.h,Cancel())
        assertArrayEquals(before,d.data,0f);assertEquals(0f,out.data[50],0f);assertTrue(out.data[51]>0);assertTrue(out.data[49]<0)
    }
    @Test fun exactPreviewMatchesRendererAtAllOutputScales(){
        val d=ramp(37,29);val p=LegendaryMath.Point(.5f,.5f)
        for(n in LegendaryMath.apertures)for((w,h) in listOf(37 to 29,71 to 57,1301 to 969)){
            val m=FocusPreviewMath.create(d,p,n,w,h,Cancel())!!;val c=FullFrameOptics.coc(d,m.focus,n,w,h,Cancel());val a=FullFrameOptics.acceptableRadius(w,h)
            for(y in 0 until m.height step 7)for(x in 0 until m.width step 11){val xx=FullFrameOptics.displayPixel(x,m.width,w);val yy=FullFrameOptics.displayPixel(y,m.height,h);assertEquals(FocusPreviewMath.color(c.data[yy*w+xx],a),m.argb[y*m.width+x])}
        }
    }
    @Test fun productionRenderPassesOpticalCocToHighlight(){
        val p=Planes(41,29);for(y in 12..16)for(x in 18..22)for(c in 0..2)p.plane(c)[y*41+x]=240.toByte();val d=ramp(41,29);var called=false
        val expected=FullFrameOptics.coc(d,.4f,2.8f,41,29,Cancel())
        LegendaryMath.render(p,d,.4f,2.8f,Cancel(),highlightRenderer={high,coc,_,_->called=true;assertArrayEquals(expected.data,coc.data,0f);high}){im,_->im};assertTrue(called)
    }
    @Test fun largeOpticalRadiusNotClippedToOldGain(){
        val w=2048;val high=LegendaryMath.Frame(w,1,3,FloatArray(w*3){.37f});val coc=LegendaryMath.Frame(w,1,1,FloatArray(w){60f})
        assertTrue(FullFrameOptics.maxRadius(w,1)>60f)
        val result=HighlightScatter.optical(high,coc,.95f,Cancel());assertTrue(result.data.all{abs(it-.37f)<4e-6f})
    }
    @Test fun opticalBoundsRejectInvalidRadius(){
        val high=LegendaryMath.Frame(3,2,3,FloatArray(18){.5f})
        try{HighlightScatter.optical(high,LegendaryMath.Frame(3,2,1,FloatArray(6){55f}),2.8f,Cancel());fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun cancelBeforeAllocatingAndInvalidInputsRejected(){
        try{FullFrameOptics.coc(ramp(),.5f,2.8f,100,100,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}
        for(n in listOf(0f,Float.NaN,8f))try{FullFrameOptics.checkAperture(n);fail()}catch(_:IllegalArgumentException){}
        for(d in listOf(-.1f,1.1f,Float.NaN))try{FullFrameOptics.virtualDistance(d);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun depthOnlyOperationCancellableWithoutAutofocus(){
        val f=PhotoFlow();f.photoLoaded();f.begin(PhotoFlow.Operation.DEPTH);assertNull(f.focus);assertFalse(f.canAdjustOptics);f.cancel();assertTrue(f.cancelling);f.finish();assertNull(f.focus);assertFalse(f.hasResult)
    }
    @Test fun parameterStalenessUsesOnlyOpticalControls(){
        val f=PhotoFlow();f.photoLoaded();f.generated();f.aperture(4f);assertTrue(f.resultStale);assertTrue(f.canSave);f.generated();assertFalse(f.resultStale)
    }
}
