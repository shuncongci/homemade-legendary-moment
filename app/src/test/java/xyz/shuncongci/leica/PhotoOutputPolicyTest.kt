package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import xyz.shuncongci.leica.CompositionGeometry.Crop

/** Real sizing/export math and panel state; not Android UI or 50 MP on-device inference. */
class PhotoOutputPolicyTest {
    private val full=CompositionGeometry.FULL
    private val crop=Crop(.125,.125,.875,.875)
    private fun ready()=PhotoFlow().apply{photoLoaded()}

    @Test fun onlyTheFourBlurWorkflowsExposeOutputSelection(){
        val expected=setOf(PhotoWorkflow.BLUR,PhotoWorkflow.COLOR_BLUR,PhotoWorkflow.BLUR_COMPOSITION,PhotoWorkflow.ALL)
        for(w in PhotoWorkflow.entries)assertEquals(w.label,w in expected,w.allowsOutputSelection)
    }
    @Test fun nonBlurWorkflowsIgnoreEveryRememberedBokehSize(){
        for(w in listOf(PhotoWorkflow.COLOR,PhotoWorkflow.COLOR_COMPOSITION,PhotoWorkflow.COMPOSITION))
            for(o in BokehOutput.entries)assertEquals(BokehOutput.ORIGINAL,w.output(o))
    }
    @Test fun returningToBlurPreservesItsRememberedChoice(){
        for(w in PhotoWorkflow.entries.filter{it.allowsOutputSelection})for(o in BokehOutput.entries){
            assertEquals(o,w.output(o))
            assertEquals(BokehOutput.ORIGINAL,PhotoWorkflow.COLOR.output(o))
            assertEquals(o,w.output(o))
        }
    }
    @Test fun compositionDoesNotInheritBlurFromThePreviousFlow(){
        val f=ready();f.selectProcessingMode(ProcessingMode.BLUR_ONLY)
        assertTrue(f.blur)
        assertFalse(PhotoWorkflow.COMPOSITION.allowsOutputSelection)
        assertEquals(BokehOutput.ORIGINAL,PhotoWorkflow.COMPOSITION.output(BokehOutput.SIZE_1024))
    }
    @Test fun automaticOutputKeepsNativePixelsThrough50Mp(){
        assertEquals(50_000_000L,LeicaSizes.COLOR_MAX_PIXELS)
        for((w,h) in listOf(4000 to 3000,8000 to 6000,10000 to 5000,5000 to 10000,360 to 480,1 to 1))
            for(o in BokehOutput.entries)assertEquals(w to h,LeicaSizes.processingSize(w,h,false,o))
    }
    @Test fun onlySourcesOver50MpAreReduced(){
        assertEquals(8164 to 6123,LeicaSizes.processingSize(16384,12288,false,BokehOutput.SIZE_1024))
        assertEquals(6123 to 8164,LeicaSizes.processingSize(12288,16384,false,BokehOutput.SIZE_2048))
        val (w,h)=LeicaSizes.colorSize(10001,5000)
        assertTrue(w<10001 || h<5000);assertTrue(w.toLong()*h<=50_000_000L)
    }
    @Test fun largeOddAndExifSwappedSizesKeepTheirRatio(){
        for((w,h) in listOf(16385 to 12289,12001 to 9001,10003 to 10007,200000 to 501)){
            val (x,y)=LeicaSizes.colorSize(w,h)
            assertEquals(y to x,LeicaSizes.colorSize(h,w))
            assertTrue(x in 1..w && y in 1..h)
            assertTrue(x.toLong()*y in 49_000_000L..50_000_000L)
            assertTrue(abs(x.toDouble()/y-w.toDouble()/h)/(w.toDouble()/h)<.01)
        }
    }
    @Test fun extremeDimensionsDoNotOverflowOrIteratePerPixel(){
        for((w,h) in listOf(Int.MAX_VALUE to 1,1 to Int.MAX_VALUE,Int.MAX_VALUE to Int.MAX_VALUE,2 to Int.MAX_VALUE)){
            val (x,y)=LeicaSizes.colorSize(w,h)
            assertTrue(x in 1..w && y in 1..h);assertTrue(x.toLong()*y<=50_000_000L)
        }
        assertEquals(50_000_000 to 1,LeicaSizes.colorSize(Int.MAX_VALUE,1))
        assertEquals(1 to 50_000_000,LeicaSizes.colorSize(1,Int.MAX_VALUE))
    }
    @Test fun invalidAutomaticSizesAreRejected(){
        for((w,h) in listOf(0 to 1,1 to 0,-1 to 100,100 to -1)){
            try{LeicaSizes.colorSize(w,h);fail("invalid automatic size")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun bokehStillUsesAllThreeSizesWithoutAnAutomatic50MpCap(){
        assertEquals(16384 to 12288,LeicaSizes.processingSize(16384,12288,true,BokehOutput.ORIGINAL))
        assertEquals(2048 to 1536,LeicaSizes.processingSize(16384,12288,true,BokehOutput.SIZE_2048))
        assertEquals(1024 to 768,LeicaSizes.processingSize(16384,12288,true,BokehOutput.SIZE_1024))
        for(o in BokehOutput.entries)assertEquals(360 to 480,LeicaSizes.processingSize(360,480,true,o))
    }
    @Test fun ganAnalysisRemainsIndependentOfAutomaticFullSizeOutput(){
        for(o in BokehOutput.entries)for(blur in listOf(false,true)){
            assertEquals(1024 to 512,LeicaSizes.modelSize(10000,5000,blur,o))
            assertEquals(1024 to 768,LeicaSizes.modelSize(16384,12288,blur,o))
        }
    }
    @Test fun nonBlurCropUsesNativePixelsInsteadOfTheHidden1024Choice(){
        for(o in BokehOutput.entries){
            val p=EditorCrop.plan(4000,3000,crop,o,false)
            assertEquals(3000,p.width);assertEquals(2250,p.height)
            assertEquals(p.availableWidth,p.width);assertEquals(p.availableHeight,p.height)
        }
    }
    @Test fun existingSmallResultIsNotEnlargedWhenBlurIsSwitchedOff(){
        val p=EditorCrop.plan(2048,1536,crop,BokehOutput.SIZE_1024,false)
        assertEquals(1536,p.width);assertEquals(1152,p.height)
    }
    @Test fun rawCompositionCapsTheFinalCropRatherThanUpscalingIt(){
        val p=EditorCrop.plan(16384,12288,full,BokehOutput.SIZE_2048,false)
        assertEquals(8164,p.width);assertEquals(6123,p.height)
        val small=EditorCrop.plan(16384,12288,Crop(.375,.375,.625,.625),BokehOutput.SIZE_1024,false)
        assertEquals(4096,small.width);assertEquals(3072,small.height)
    }
    @Test fun colorRegenerationCapsTheFullSceneAt50MpBeforeCropping(){
        val p=EditorCrop.renderPlan(16384,12288,crop,BokehOutput.SIZE_1024,false)
        assertEquals(8164,p.workWidth);assertEquals(6123,p.workHeight)
        assertTrue(p.workWidth.toLong()*p.workHeight<=50_000_000L)
        val expected=EditorCrop.plan(p.workWidth,p.workHeight,crop,BokehOutput.ORIGINAL,false)
        assertEquals(expected.width,p.width);assertEquals(expected.height,p.height)
    }
    @Test fun nonBlurRegenerationIgnoresAllRememberedResolutionChoices(){
        for(o in BokehOutput.entries){
            val p=EditorCrop.renderPlan(4000,3000,crop,o,false)
            assertEquals(4000,p.workWidth);assertEquals(3000,p.workHeight)
            assertEquals(3000,p.width);assertEquals(2250,p.height)
        }
    }
    @Test fun smallCropSourcesAreNeverUpscaledInEitherMode(){
        for(blur in listOf(false,true))for(o in BokehOutput.entries)for((w,h) in listOf(1 to 1,7 to 11,360 to 480)){
            val p=EditorCrop.plan(w,h,crop,o,blur)
            assertTrue(p.width in 1..p.availableWidth && p.height in 1..p.availableHeight)
        }
    }
    @Test fun uncroppedNonBlurPngOver2048KeepsEveryArgbPixel(){checkOriginalExport(full)}
    @Test fun croppedNonBlurPngOver2048KeepsEveryArgbPixel(){checkOriginalExport(crop)}
    private fun checkOriginalExport(c:Crop){
        val w=3001;val h=9
        val pixels=IntArray(w*h){i->((i*71%256) shl 24) or ((i*31%256) shl 16) or ((i*17%256) shl 8) or (i*7%256)}
        val p=EditorCrop.plan(w,h,c,BokehOutput.SIZE_1024,false)
        val b=CompositionGeometry.pixels(p.crop,w,h)
        assertTrue(p.width>2048);assertEquals(b.w,p.width);assertEquals(b.h,p.height)
        val bytes=ByteArrayOutputStream()
        CompositionExport.writeSized(bytes,w,h,p.crop,p.width,p.height,Cancel()){box->IntArray(box.w*box.h){i->pixels[(box.top+i/box.w)*w+box.left+i%box.w]}}
        val io=Class.forName("javax.imageio.ImageIO")
        val image=io.getMethod("read",java.io.InputStream::class.java).invoke(null,ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(p.width,image.javaClass.getMethod("getWidth").invoke(image))
        assertEquals(p.height,image.javaClass.getMethod("getHeight").invoke(image))
        val getRGB=image.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        for(y in 0 until p.height)for(x in 0 until p.width)assertEquals(pixels[(b.top+y)*w+b.left+x],getRGB.invoke(image,x,y))
    }
    @Test fun startingGenerationDoesNotCollapseBeforeARealCommit(){
        val f=ready();f.begin(PhotoFlow.Operation.GENERATE);assertTrue(f.parametersExpanded)
        f.generated();assertFalse(f.parametersExpanded);f.finish();assertFalse(f.parametersExpanded)
        assertTrue(f.hasResult);assertTrue(f.canSave);assertFalse(f.saved)
    }
    @Test fun cancellationPreservesTheManualPanelState(){
        for(expanded in listOf(false,true)){
            val f=ready();f.showParameters(expanded);f.begin(PhotoFlow.Operation.GENERATE);f.cancel();f.finish()
            assertEquals(expanded,f.parametersExpanded);assertFalse(f.hasResult)
        }
    }
    @Test fun failureOrAnyNonGeneratingOperationDoesNotCollapseParameters(){
        for(op in PhotoFlow.Operation.entries.filter{it!=PhotoFlow.Operation.IDLE})for(expanded in listOf(false,true)){
            val f=ready();f.showParameters(expanded);f.begin(op);f.finish()
            assertEquals(expanded,f.parametersExpanded)
        }
    }
    @Test fun userCanReopenParametersAndTheNextResultCollapsesAgain(){
        val f=ready();f.generated();assertFalse(f.parametersExpanded)
        f.showParameters(true);f.parametersChanged();assertTrue(f.parametersExpanded)
        f.generated();assertFalse(f.parametersExpanded)
    }
    @Test fun importingTheNextPhotoReopensParameters(){
        val f=ready();f.generated();assertFalse(f.parametersExpanded)
        f.photoLoaded();assertTrue(f.parametersExpanded);assertFalse(f.hasResult)
    }
    @Test fun applyingCompositionCollapsesButDoesNotSaveOrRerender(){
        val f=ready();f.begin(PhotoFlow.Operation.COMPOSITION);f.finish();assertTrue(f.parametersExpanded)
        f.compositionApplied();assertFalse(f.parametersExpanded)
        assertTrue(f.hasResult);assertTrue(f.canSave);assertFalse(f.saved);assertNull(f.focus)
    }
    @Test fun compositionChoiceCanTemporarilyOpenAndRestoreCollapsedParameters(){
        val f=ready();f.generated();val before=f.parametersExpanded
        f.showParameters(true);assertTrue(f.parametersExpanded)
        f.begin(PhotoFlow.Operation.COMPOSITION);f.cancel();f.finish();f.showParameters(before)
        assertFalse(f.parametersExpanded);assertTrue(f.hasResult);assertTrue(f.canSave)
    }
}
