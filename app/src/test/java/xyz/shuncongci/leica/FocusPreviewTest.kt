package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class FocusPreviewTest {
    private fun depth()=LegendaryMath.Frame(101,21,1,FloatArray(101*21){(it%101)/100f})
    private val point=LegendaryMath.Point(.5f,.5f)
    @Test fun usesExactlyRenderGainAndFocus(){
        val d=depth();val before=d.data.copyOf()
        for(ap in LegendaryMath.apertures){
            val mask=FocusPreviewMath.create(d,point,ap,101,21,Cancel())!!
            assertEquals(LegendaryMath.focus(d,point)!!,mask.focus,0f)
            for(i in d.data.indices)assertEquals(FocusPreviewMath.color((d.data[i]*d.data[i]-mask.focus*mask.focus)*FullFrameOptics.radiusScale(mask.focus,ap,101,21),FullFrameOptics.acceptableRadius(101,21)),mask.argb[i])
        };assertArrayEquals(before,d.data,0f)
    }
    @Test fun symmetricCoCThresholdsNotSubjectSelection(){
        for(v in floatArrayOf(0f,.2f,.5f,.5001f,.9f,1f,5f))assertEquals(FocusPreviewMath.color(v,.5f),FocusPreviewMath.color(-v,.5f))
        assertEquals(FocusPreviewMath.NEAR_ARGB,FocusPreviewMath.color(.5f,.5f))
        assertEquals(FocusPreviewMath.TRANSITION_ARGB,FocusPreviewMath.color(.51f,.5f));assertEquals(0,FocusPreviewMath.color(1f,.5f))
    }
    @Test fun stoppingDownWidensAcceptableCore(){
        fun count(ap:Float)=FocusPreviewMath.create(depth(),point,ap,101,21,Cancel())!!.argb.count{it==FocusPreviewMath.NEAR_ARGB}
        assertTrue(count(5.6f)>count(.95f))
    }
    @Test fun unreliablePointNeverAutoReselects(){
        val edge=LegendaryMath.Frame(101,21,1,FloatArray(101*21){if(it%101<50)0f else 1f})
        assertNull(FocusPreviewMath.create(edge,point,2.8f,101,21,Cancel()))
    }
    @Test fun onlyUiMaskIsSampledNotCachedDepth(){
        val d=depth();val data=d.data
        val mask=FocusPreviewMath.create(d,point,2.8f,3072,4080,Cancel())!!
        assertEquals(1280,maxOf(mask.width,mask.height));assertSame(data,d.data);assertEquals(101,d.w);assertEquals(21,d.h)
    }
    @Test fun cancelStopsPreview(){try{FocusPreviewMath.create(depth(),point,2.8f,101,21,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun samePhotoDepthReusedAcrossFocusApertureAndGenerate(){
        val cache=PhotoDepthCache();var calls=0
        fun get()=cache.get("immutable-import-A",Cancel()){calls++;depth()}
        val d=get();FocusPreviewMath.create(get(),point,2.8f,101,21,Cancel());FocusPreviewMath.create(get(),point,5.6f,101,21,Cancel())
        assertSame(d,get());assertEquals(1,calls)
    }
    @Test fun newPhotoAndCloseEvictPreviousDepth(){
        val cache=PhotoDepthCache();val a=cache.get("A",Cancel()){depth()}
        val b=cache.get("B",Cancel()){depth()};assertNotSame(a,b);assertFalse(cache.contains("A"));assertTrue(cache.contains("B"))
        cache.clear();assertFalse(cache.contains("B"))
    }
    @Test fun failedOrCancelledCalculationNeverPublished(){
        val cache=PhotoDepthCache();val c=Cancel()
        try{cache.get("A",c){c.cancelled=true;depth()};fail()}catch(_:CancellationException){}
        assertFalse(cache.contains("A"))
        try{cache.get("B",Cancel()){error("inference failed")};fail()}catch(_:IllegalStateException){}
        assertFalse(cache.contains("B"));assertSame(cache.get("B",Cancel()){depth()},cache.get("B",Cancel()){error("must reuse")})
    }
    @Test fun cancelEvenOnCacheHit(){
        val cache=PhotoDepthCache();cache.get("A",Cancel()){depth()}
        try{cache.get("A",Cancel().apply{cancelled=true}){error("must not run")};fail()}catch(_:CancellationException){}
    }
    @Test fun previewDoesNotMarkGeneratedOrDestroyPreviousResult(){
        val flow=PhotoFlow();flow.photoLoaded();flow.toggleBlur();flow.picked(point);flow.generated()
        flow.begin(PhotoFlow.Operation.FOCUS_PREVIEW);assertFalse(flow.canGenerate);assertFalse(flow.canSave)
        flow.cancel();assertTrue(flow.cancelling);flow.finish();assertTrue(flow.hasResult);assertTrue(flow.canGenerate);assertTrue(flow.canSave)
    }
    @Test fun invalidFocusClearsMarkerAndRequiresManualRepick(){
        val flow=PhotoFlow();flow.photoLoaded();flow.toggleBlur();flow.picked(point);flow.begin(PhotoFlow.Operation.FOCUS_PREVIEW)
        flow.focusUnreliable();flow.finish();assertNull(flow.focus);assertFalse(flow.canGenerate);assertFalse(flow.picking)
    }
}
