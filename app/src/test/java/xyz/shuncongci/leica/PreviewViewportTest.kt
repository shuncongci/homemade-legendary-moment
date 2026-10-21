package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class PreviewViewportTest {
    @Test fun fitCentersPortraitAndLandscape(){
        val v=PreviewViewport()
        assertEquals(PreviewViewport.Bounds(200f,0f,600f,600f),v.layout(800f,600f,400,600))
        assertEquals(PreviewViewport.Bounds(0f,0f,800f,600f),v.layout(800f,600f,4000,3000))
    }
    @Test fun draggingFitCannotLosePhoto(){
        val v=PreviewViewport();v.layout(800f,600f,4000,3000);v.drag(10000f,-10000f)
        assertEquals(0f,v.panX,0f);assertEquals(0f,v.panY,0f)
    }
    @Test fun pinchAnchorsImagePoint(){
        val v=PreviewViewport();val before=v.layout(800f,600f,4000,3000)
        val x=300f;val y=250f;val u=(x-before.left)/before.w;val t=(y-before.top)/before.h
        v.scale(2f,x,y,x,y);val after=v.bounds()
        assertEquals(u,(x-after.left)/after.w,1e-6f);assertEquals(t,(y-after.top)/after.h,1e-6f)
    }
    @Test fun twoFingerTranslationPansWithoutExtraScale(){
        val v=PreviewViewport();v.layout(800f,600f,4000,3000);v.scale(2f,400f,300f,400f,300f)
        v.scale(1f,400f,300f,420f,315f)
        assertEquals(20f,v.panX,0f);assertEquals(15f,v.panY,0f)
    }
    @Test fun panningClampsToImageEdges(){
        val v=PreviewViewport();v.layout(800f,600f,4000,3000);v.scale(2f,400f,300f,400f,300f)
        v.drag(10000f,-10000f)
        assertEquals(400f,v.panX,0f);assertEquals(-300f,v.panY,0f)
        val b=v.bounds();assertEquals(0f,b.left,0f);assertEquals(600f,b.bottom,0f)
    }
    @Test fun zoomingBackToFitResetsPan(){
        val v=PreviewViewport();v.layout(800f,600f,4000,3000);v.scale(3f,400f,300f,400f,300f);v.drag(30f,40f)
        v.scale(.1f,400f,300f,400f,300f)
        assertEquals(1f,v.zoom,0f);assertEquals(0f,v.panX,0f);assertEquals(0f,v.panY,0f)
    }
    @Test fun liftedActiveFingerRebasesWithoutJump(){
        val t=PreviewTouch(8f);t.begin(3,10f,20f);t.multiple();t.rebase(7,400f,500f)
        assertEquals(0f to 0f,t.move(400f,500f));assertEquals(5f to -2f,t.move(405f,498f))
        assertEquals(7,t.activeId);assertTrue(t.multi);assertTrue(t.moved)
    }
    @Test fun liftedSecondFingerAlsoRebasesWithoutJump(){
        val t=PreviewTouch(8f);t.begin(3,10f,20f);t.multiple();t.rebase(3,100f,200f)
        assertEquals(0f to 0f,t.move(100f,200f));assertEquals(-4f to 6f,t.move(96f,206f))
        t.end();assertEquals(-1,t.activeId)
    }
    @Test fun tapsAndDragsStaySeparate(){
        val t=PreviewTouch(8f);t.begin(0,10f,20f);assertEquals(0f to 0f,t.move(12f,22f));assertFalse(t.moved)
        t.move(30f,20f);assertTrue(t.moved);assertFalse(t.multi)
        t.begin(1,0f,0f);assertFalse(t.moved);assertFalse(t.multi)
    }
    @Test fun resultUsesOwn2048PixelCoordinates(){
        val d=PreviewViewport.Bounds(-400f,-300f,1200f,900f)
        val o=PreviewRegions.visible(4000,3000,d,800,600)!!
        val r=PreviewRegions.visible(2048,1536,d,800,600)!!
        assertEquals(NativeImageMath.Box(1000,750,3000,2250),o)
        assertEquals(NativeImageMath.Box(512,384,1536,1152),r)
        assertEquals(o.left/4000f,r.left/2048f,0f);assertEquals(o.top/3000f,r.top/1536f,0f)
        assertEquals(1,PreviewRegions.sample(r,2048,d.w))
    }
    @Test fun regionMappingHandlesPortraitAndOutsideViewport(){
        val d=PreviewViewport.Bounds(-300f,-400f,900f,1200f)
        assertEquals(NativeImageMath.Box(384,512,1152,1536),PreviewRegions.visible(1536,2048,d,600,800))
        assertNull(PreviewRegions.visible(1536,2048,PreviewViewport.Bounds(900f,0f,1000f,100f),600,800))
        assertNull(PreviewRegions.visible(0,0,d,600,800))
        assertEquals(NativeImageMath.Box(0,0,1,1),PreviewRegions.visible(1,1,d,600,800))
    }
    @Test fun completedTimeIsReadableStableAndNonNegative(){
        assertEquals("12.3 s",ProcessingTime.seconds(12345));assertEquals("0.0 s",ProcessingTime.seconds(-1))
        assertEquals("3601.0 s",ProcessingTime.seconds(3601000))
    }
}

class HardwarePolicyTest {
    @Test fun forcedCpuNeverRequestsNnapiOnAnySupportedApi(){
        for(api in 26..40)assertFalse("API $api",HardwarePolicy.requestNnapi(api,forceCpu=true))
    }
    @Test fun otherModelsRetainExistingNnapiPolicy(){
        for(api in 26..40)assertEquals(api>=29,HardwarePolicy.requestNnapi(api,forceCpu=false))
    }
    @Test fun forcedCpuDoesNotAlterSymbolicDimensions(){
        assertFalse(HardwarePolicy.requestNnapi(36,true))
        assertEquals(mapOf("height" to 518L,"width" to 686L),HardwarePolicy.dimensions(686,518))
    }

    @Test fun nonCpuNnapiNeedsAndroid10(){
        assertFalse(HardwarePolicy.requestNnapi(26));assertFalse(HardwarePolicy.requestNnapi(28))
        assertTrue(HardwarePolicy.requestNnapi(29));assertTrue(HardwarePolicy.requestNnapi(35))
    }
    @Test fun eachMultiscalePassGetsItsOwnActualShape(){
        assertEquals(mapOf("height" to 1536L,"width" to 2048L),HardwarePolicy.dimensions(2048,1536))
        assertEquals(mapOf("height" to 384L,"width" to 512L),HardwarePolicy.dimensions(512,384))
    }
    @Test fun compatibilityFallbackNeverRetriesMemoryOrCancellation(){
        for(m in listOf("NNAPI OUT_OF_MEMORY","NNAPI out of memory","NNAPI bad_alloc","NNAPI failed to allocate","NNAPI memory allocation failure","NNAPI run terminated","NNAPI cancelled"))assertFalse(m,HardwarePolicy.compatibilityFailure(m))
        assertFalse(HardwarePolicy.compatibilityFailure(null));assertFalse(HardwarePolicy.compatibilityFailure("invalid graph"))
    }
    @Test fun exactMtkTargetDeviceErrorIsRecognized(){
        assertTrue(HardwarePolicy.compatibilityFailure("Error code - ORT_EP_FAIL - message: The model cannot run using the current set of target devices: mtk-dsp_shim, mtk-mdla_shim, mtk-neuron_shim"))
    }
    @Test fun mtkMemoryAndTerminationStillNeverRetry(){
        for (suffix in listOf("out of memory","OUT_OF_MEMORY","bad_alloc","failed to allocate","memory allocation","resource exhausted","cancelled","terminated","interrupted"))
            assertFalse(HardwarePolicy.compatibilityFailure("The model cannot run using the current set of target devices: $suffix"))
    }
    @Test fun hardwareFailureRetriesSameInputOnce(){
        val input=Any();var attempts=0;var releases=0
        val result=withCpuRetry(attempt={attempts++;if(attempts==1)throw IllegalStateException("driver");input},shouldRetry={true},beforeRetry={releases++})
        assertSame(input,result);assertEquals(2,attempts);assertEquals(1,releases)
    }
    @Test fun cpuFailureEscapesWithoutThirdAttempt(){
        var attempts=0;val failure=IllegalStateException("CPU failed")
        try { withCpuRetry<Unit>(attempt={attempts++;throw failure},shouldRetry={true},beforeRetry={});fail() }
        catch(e:IllegalStateException){assertSame(failure,e)}
        assertEquals(2,attempts)
    }
    @Test fun unrelatedFailureDoesNotRetry(){
        var attempts=0;val failure=IllegalArgumentException("invalid graph")
        try { withCpuRetry<Unit>(attempt={attempts++;throw failure},shouldRetry={false},beforeRetry={fail("retry forbidden")});fail() }
        catch(e:IllegalArgumentException){assertSame(failure,e)}
        assertEquals(1,attempts)
    }
    @Test fun cancellationBeforeRetryStopsImmediately(){
        var attempts=0;val cancelled=java.util.concurrent.CancellationException("cancelled")
        try { withCpuRetry<Unit>(attempt={attempts++;throw IllegalStateException("driver")},shouldRetry={true},beforeRetry={throw cancelled});fail() }
        catch(e:java.util.concurrent.CancellationException){assertSame(cancelled,e)}
        assertEquals(1,attempts)
    }
    @Test fun fatalCauseNeverRetries(){
        var attempts=0;val oom=OutOfMemoryError("test")
        try { withCpuRetry<Unit>(attempt={attempts++;throw RuntimeException(oom)},shouldRetry={true},beforeRetry={fail("retry forbidden")});fail() }
        catch(e:OutOfMemoryError){assertSame(oom,e)}
        assertEquals(1,attempts)
    }
    @Test fun successfulHardwareDoesNotRetry(){
        var attempts=0
        assertEquals(42,withCpuRetry(attempt={attempts++;42},shouldRetry={true},beforeRetry={fail("retry forbidden")}))
        assertEquals(1,attempts)
    }
    @Test fun unsupportedDriverAllowsCpuWithoutResizing(){
        assertTrue(HardwarePolicy.compatibilityFailure("NNAPI compilation failed: no supported devices"))
        assertTrue(HardwarePolicy.compatibilityFailure("Execution provider is not supported"))
    }
}
