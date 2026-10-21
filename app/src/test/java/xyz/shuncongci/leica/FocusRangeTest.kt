package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/** Historical 1.4 regression only; custom ranges are absent from the shipping app. */
class FocusRangeTest {
    private fun depth(w:Int=101,h:Int=21)=LegendaryMath.Frame(w,h,1,FloatArray(w*h){(it%w)/(w-1f)})
    private fun map(d:LegendaryMath.Frame,steps:Int,ap:Float=2.8f,w:Int=d.w,h:Int=d.h)=FocusRangeMath.coc(d,.5f,ap,w,h,steps,Cancel())
    @Test fun tenThousandStepsAndExactInputRoundTrip(){
        for(i in 0..10000){assertEquals(i,FocusRangeMath.parsePercent(FocusRangeMath.percent(i)));if(i>0)assertTrue(FocusRangeMath.halfWidth(i)>FocusRangeMath.halfWidth(i-1))}
        assertEquals(1,FocusRangeMath.parsePercent("0.01"));assertEquals(1234,FocusRangeMath.parsePercent("12.34%"));assertEquals(50,FocusRangeMath.parsePercent(".5"))
    }
    @Test fun invalidPrecisionAndNonfiniteInputNeverSilentlyRounded(){
        for(s in listOf("", "NaN", "Infinity", "-1", "100.01", "1.234", "1e2", "1e999999999", "12,5"))assertNull(s,FocusRangeMath.parsePercent(s))
        for(n in listOf(-1,10001))try{FocusRangeMath.halfWidth(n);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun zeroWidthBitExactLegacyCoCAtEveryOutputScale(){
        val d=depth(37,29)
        for(ap in LegendaryMath.apertures)for((w,h) in listOf(37 to 29,17 to 13,64 to 51,1 to 1)){
            val native=LegendaryMath.Frame(d.w,d.h,1,FloatArray(d.size){(d.data[it]-.5f)*LegendaryMath.gain(ap)})
            val before=LegendaryMath.resize(native,w,h,cancel=Cancel())
            assertArrayEquals(before.data,map(d,0,ap,w,h).data,0f)
        }
    }
    @Test fun symmetricResidualAndNoDepthMutation(){
        val d=depth();val before=d.data.copyOf();val out=map(d,1000)
        for(i in d.data.indices){val delta=d.data[i]-.5f;assertEquals(sign(delta)*max(0f,abs(delta)-.1f)*15.12f,out.data[i],1e-6f)}
        assertArrayEquals(before,d.data,0f)
    }
    @Test fun wideningIsMonotonicForEveryPixel(){
        val d=depth();var previous=map(d,0)
        for(n in listOf(1,50,100,500,1000,5000,10000)){
            val next=map(d,n);for(i in next.data.indices)assertTrue(abs(next.data[i])<=abs(previous.data[i])+1e-6f);previous=next
        }
    }
    @Test fun apertureOnlyChangesOutsideCore(){
        val d=depth();val a=map(d,1000,.95f);val b=map(d,1000,5.6f)
        for(i in a.data.indices){assertEquals(a.data[i]==0f,b.data[i]==0f)
            if(a.data[i]!=0f)assertEquals(a.data[i]/LegendaryMath.gain(.95f),b.data[i]/LegendaryMath.gain(5.6f),1e-7f)
        }
    }
    @Test fun shiftedFocusClipsIntervalNotAutoRecenters(){
        assertEquals(0f to .25f,FocusRangeMath.interval(.05f,2000));assertEquals(.75f to 1f,FocusRangeMath.interval(.95f,2000))
        assertEquals(0f to 1f,FocusRangeMath.interval(.9f,10000))
    }
    @Test fun tinyStepContinuousAtCoreBoundary(){
        val d=LegendaryMath.Frame(5,1,1,floatArrayOf(.39999f,.4f,.5f,.6f,.60001f))
        val a=map(d,1000);assertTrue(abs(a.data[0])<.0002f);assertTrue(abs(a.data[4])<.0002f);assertEquals(0f,a.data[2],0f)
        val b=map(d,1001);for(v in b.data)assertEquals(0f,v,0f)
    }
    @Test fun fullRangeSkipsBothBlurAndHighlightAndPreservesBytes(){
        val p=Planes(31,23);val random=java.util.Random(9);for(c in 0..2)random.nextBytes(p.plane(c))
        var calls=0
        val out=LegacyRangeRenderer.render(p,depth(31,23),.05f,.95f,Cancel(),focusRangeSteps=10000,highlightRenderer={_,_,_,_->error("highlight must not run")}){im,_->calls++;im}
        assertEquals(0,calls);assertSame(p,out)
    }
    @Test fun rangeAppliedToSharedBaseAndHighlightCoC(){
        val p=Planes(31,23);for(c in 0..2)java.util.Arrays.fill(p.plane(c),240.toByte())
        val d=depth(31,23);val expected=map(d,1500);var highlightCalls=0;var inferCalls=0
        LegacyRangeRenderer.render(p,d,.5f,2.8f,Cancel(),focusRangeSteps=1500,highlightRenderer={high,coc,_,_->
            highlightCalls++;assertArrayEquals(expected.data,coc.data,0f);high
        }){im,df->inferCalls++;assertTrue(df.data.any{it==0f});im}
        assertEquals(1,highlightCalls);assertTrue(inferCalls>0)
    }
    @Test fun defaultFullRendererUnchangedWhenRangeZero(){
        val p=Planes(31,23);for(c in 0..2)java.util.Random(c.toLong()).nextBytes(p.plane(c));val d=depth(31,23)
        val legacy=LegendaryMath.renderCoc(p,map(d,0),2.8f,Cancel()){im,_->im}
        // Range=0 compares the same current pupil AND adaptive extraction on both paths.
        val actual=LegacyRangeRenderer.render(p,d,.5f,2.8f,Cancel(),highlightRenderer=HighlightScatter::optical,highlightSplitter=LegendaryMath::split,highlightBoost=BokehRadiance.DEFAULT_GAIN){im,_->im}
        for(c in 0..2)assertArrayEquals(legacy.plane(c),actual.plane(c))
    }
    @Test fun previewSamplesExactOutputPixelCoCNotIndependentScale(){
        val random=java.util.Random(4);val d=LegendaryMath.Frame(37,29,1,FloatArray(37*29){random.nextFloat()})
        for(n in listOf(0,1,500,10000))for((w,h) in listOf(37 to 29,23 to 19,83 to 53,1 to 1)){
            val actual=map(d,n,2.8f,w,h);val field=FocusRangeMath.field(d,.5f,2.8f,n,Cancel())
            for(y in 0 until h)for(x in 0 until w)assertEquals(actual.data[y*w+x],FocusRangeMath.sample(field,x,y,w,h),0f)
        }
    }
    @Test fun displayedMaskMatchesSavedOutputGridAndAllApertures(){
        val d=depth(31,23);val point=LegendaryMath.Point(.5f,.5f)
        for(ap in LegendaryMath.apertures){
            val m=LegacyRangePreview.create(d,point,ap,1301,969,Cancel(),550)!!
            val coc=FocusRangeMath.coc(d,m.focus,ap,1301,969,550,Cancel())
            for(y in 0 until m.height step 11)for(x in 0 until m.width step 13){
                val ox=FocusRangeMath.displayPixel(x,m.width,1301);val oy=FocusRangeMath.displayPixel(y,m.height,969)
                assertEquals(LegacyRangePreview.color(coc.data[oy*1301+ox]),m.argb[y*m.width+x])
            }
        }
    }
    @Test fun greenCoreIsIndependentOfFAndWidensWithSlider(){
        val d=depth();val p=LegendaryMath.Point(.5f,.5f)
        val masks=LegendaryMath.apertures.map{LegacyRangePreview.create(d,p,it,101,21,Cancel(),550)!!.argb.map{c->c==LegacyRangePreview.NEAR_ARGB}}
        masks.forEach{assertEquals(masks.first(),it)}
        val wider=LegacyRangePreview.create(d,p,2.8f,101,21,Cancel(),1500)!!.argb
        assertTrue(wider.count{it==LegacyRangePreview.NEAR_ARGB}>masks.first().count{it})
    }
    @Test fun cancelAndInvalidFrameRejected(){
        try{FocusRangeMath.coc(depth(),.5f,2.8f,101,21,100,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}
        val d=depth();d.data[10]=Float.NaN
        try{map(d,100);fail()}catch(_:IllegalArgumentException){}
        try{map(depth(),-1);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun rangeAndApertureDoNotRecomputeDepth(){
        val cache=PhotoDepthCache();var calls=0
        for(n in listOf(0,100,200,9999,0)){
            val d=cache.get("photo-A",Cancel()){calls++;depth()}
            LegacyRangePreview.create(d,LegendaryMath.Point(.5f,.5f),2.8f,101,21,Cancel(),n)
        };assertEquals(1,calls)
    }
    @Test fun sliderBurstsPublishOnlyLatestTicket(){
        val q=FocusPreviewUpdates();val first=q.request();repeat(1000){q.request()}
        assertFalse(q.publish(first));assertTrue(q.pending);assertTrue(q.publish(q.ticket));assertFalse(q.pending)
    }
    @Test fun lateRequestDuringFinishRemainsPending(){
        val q=FocusPreviewUpdates();val t=q.request();assertTrue(q.publish(t));q.request();assertTrue(q.pending);assertFalse(q.publish(t))
    }
    @Test fun cancelOrNewPhotoInvalidatesEvenLastInFlightPreview(){
        val q=FocusPreviewUpdates();val t=q.request();q.discard();assertFalse(q.publish(t));assertFalse(q.pending)
        val next=q.request();assertTrue(q.publish(next));assertFalse(q.pending)
    }
    @Test fun opticsAdjustableDuringPreviewButNotGenerateSaveOrCancel(){
        val f=LegacyRangeFlow();f.photoLoaded();f.toggleBlur();f.picked(LegendaryMath.Point(.5f,.5f))
        f.begin(LegacyRangeFlow.Operation.FOCUS_PREVIEW);f.focusRange(125);f.aperture(4f);assertEquals(125,f.focusRangeSteps)
        f.cancel();assertFalse(f.canAdjustOptics);try{f.focusRange(1);fail()}catch(_:IllegalStateException){};f.finish()
        for(op in listOf(LegacyRangeFlow.Operation.GENERATE,LegacyRangeFlow.Operation.SAVE,LegacyRangeFlow.Operation.PREVIEW)){
            f.begin(op);assertFalse(f.canAdjustOptics);try{f.aperture(2f);fail()}catch(_:IllegalStateException){};f.finish()
        }
    }
    @Test fun changedParametersKeepOldResultSaveableButClearlyStale(){
        val f=LegacyRangeFlow();f.photoLoaded();f.generated();f.focusRange(100);assertTrue(f.resultStale);assertTrue(f.canSave)
        f.generated();assertFalse(f.resultStale);f.aperture(4f);assertTrue(f.resultStale)
        f.photoLoaded();assertEquals(0,f.focusRangeSteps);assertFalse(f.resultStale);assertFalse(f.hasResult);assertNull(f.focus)
    }
}
