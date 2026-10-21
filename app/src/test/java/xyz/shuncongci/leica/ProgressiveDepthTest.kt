package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class ProgressiveDepthTest {
    private fun frame(v:Float)=LegendaryMath.Frame(2,2,1,FloatArray(4){v})
    @Test fun portraitLandscapeAndSquareBudgets(){
        assertEquals(518 to 686,ProgressiveDepth.size(1036,1372))
        assertEquals(686 to 518,ProgressiveDepth.size(1372,1036))
        assertEquals(518 to 518,ProgressiveDepth.size(2048,2048))
        assertEquals(517 to 686,ProgressiveDepth.size(3072,4080)) // 3072 * 686 / 4080 = 516.5176, rounded to nearest pixel.
    }
    @Test fun smallAndExtremeSourcesNotUpscaled(){
        assertEquals(56 to 70,ProgressiveDepth.size(56,70))
        assertEquals(686 to 1,ProgressiveDepth.size(Int.MAX_VALUE,1))
        assertEquals(1 to 686,ProgressiveDepth.size(1,Int.MAX_VALUE))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidSize(){ProgressiveDepth.size(0,9)}
    @Test fun coarseCannotChangeFinalAnalysisSize(){
        val before=NativeDepthMath.analysisSize(3072,4080)
        ProgressiveDepth.size(3072,4080)
        assertEquals(919 to 1220,before);assertEquals(before,NativeDepthMath.analysisSize(3072,4080))
        assertEquals("FP32",NativeDepthMath.PRECISION)
    }
    @Test fun previewPublishedBeforeFullAndOnlyFullReturned(){
        val order=mutableListOf<String>();val coarse=frame(.2f);val full=frame(.8f)
        val actual=ProgressiveDepth.run(Cancel(),{order.add("coarse");coarse},{assertSame(coarse,it);order.add("preview")},{order.add("full");full})
        assertSame(full,actual);assertEquals(listOf("coarse","preview","full"),order)
    }
    @Test fun previewNeverEntersCacheAndCacheReuseSkipsBothPasses(){
        val cache=PhotoDepthCache();val c=Cancel();var runs=0;val full=frame(.8f)
        val result=cache.get("photo",c){ProgressiveDepth.run(c,{runs++;frame(.2f)},{assertFalse(cache.contains("photo"))},{runs++;full})}
        assertSame(full,result);assertSame(full,cache.get("photo",c){error("must reuse")});assertEquals(2,runs)
    }
    @Test fun cancelledPreviewCannotStartFullOrPopulateCache(){
        val cache=PhotoDepthCache();val c=Cancel()
        try{cache.get("photo",c){ProgressiveDepth.run(c,{frame(.2f)},{c.cancelled=true},{error("must not start")})};fail()}catch(_:CancellationException){}
        assertFalse(cache.contains("photo"))
    }
    @Test fun failureInFullDoesNotCacheCoarse(){
        val cache=PhotoDepthCache();val c=Cancel();var shown=false
        try{cache.get("photo",c){ProgressiveDepth.run(c,{frame(.2f)},{shown=true},{throw IllegalStateException("full failed")})};fail()}catch(_:IllegalStateException){}
        assertTrue(shown);assertFalse(cache.contains("photo"))
    }
    @Test fun cancellationAfterFullStillRejectsResult(){
        val c=Cancel()
        try{ProgressiveDepth.run(c,{frame(.2f)},{},{c.cancelled=true;frame(.8f)});fail()}catch(_:CancellationException){}
    }
    @Test fun fixedTileGridSamplesDifferentPreviewAndFinalGrids(){
        val map=DepthVisualMath.Tiles(2,1,floatArrayOf(.2f,.8f))
        for(i in 0..7)assertEquals(if(i%4<2).2f else .8f,ProgressiveDepth.tileDepth(map,i,4,2),0f)
    }
    @Test fun morphEndpointsAndBounds(){
        for(t in listOf(-1f,0f,.2f,.5f,1f,2f))assertTrue(ProgressiveDepth.blend(.2f,.8f,t) in .2f.. .8f)
        assertEquals(.2f,ProgressiveDepth.blend(.2f,.8f,0f),0f)
        assertEquals(.8f,ProgressiveDepth.blend(.2f,.8f,1f),1e-6f)
    }
    @Test fun previewFloatIsSmallContinuousAndBidirectional(){
        val values=(0..575).map{ProgressiveDepth.floatOffset(it,.2f)}
        assertTrue(values.all{abs(it)<=.03501f});assertTrue(values.any{it>0});assertTrue(values.any{it<0})
        for(i in 0..575)assertEquals(ProgressiveDepth.floatOffset(i,0f),ProgressiveDepth.floatOffset(i,1f),1e-6f)
    }
}
