package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.CompositionGeometry.Crop

class GaicCompositionTest {
    private val full=CompositionGeometry.FULL
    private val a=Crop(0.0,0.0,.6,.8)
    private val b=Crop(.4,.2,1.0,1.0)
    private val c=Crop(.2,.1,.8,.9)
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    private fun cancelled(block:(Cancel)->Unit){try{block(Cancel().apply{cancelled=true});fail("Expected cancellation")}catch(_:CancellationException){}}
    @Test fun originalIsAlwaysScored(){assertEquals(full,GaicCompositionMath.candidates(4000,3000,BokehOutput.ORIGINAL).first())}
    @Test fun outputPixelEligibility(){for(o in BokehOutput.entries)for((w,h) in listOf(4000 to 3000,3000 to 4000,2048 to 1536,800 to 600)){
        val crops=GaicCompositionMath.candidates(w,h,o);assertTrue(crops.size in 1..801)
        assertEquals(crops.size,crops.distinct().size)
        for(crop in crops){assertTrue(CompositionOutput.eligible(w,h,crop,o));assertEquals(crop,CompositionGeometry.snapped(crop,w,h))}
    }}
    @Test fun exact2048SourceIsNotCroppedBelowBudget(){assertTrue(GaicCompositionMath.candidates(2048,1536,BokehOutput.SIZE_2048).all{val b=CompositionGeometry.pixels(it,2048,1536);maxOf(b.w,b.h)>=2048})}
    @Test fun nativeBudgetIncludesSmallCrops(){assertTrue(GaicCompositionMath.candidates(4000,3000,BokehOutput.ORIGINAL).any{it.area<.4})}
    @Test fun extremesRemainLegal(){for((w,h)in listOf(1 to 1,1 to 50000,50000 to 1,23 to 19))for(c in GaicCompositionMath.candidates(w,h,BokehOutput.ORIGINAL)){assertTrue(c.area>0);assertTrue(c.l>=0 && c.r<=1 && c.t>=0 && c.b<=1)}}
    @Test fun deterministicGeometry(){assertEquals(GaicCompositionMath.candidates(3000,4000,BokehOutput.ORIGINAL),GaicCompositionMath.candidates(3000,4000,BokehOutput.ORIGINAL))}
    @Test fun enumerateCancellation(){cancelled{GaicCompositionMath.candidates(4000,3000,BokehOutput.ORIGINAL,it)}}
    @Test fun inputCancellation(){cancelled{GaicCompositionMath.input(Planes(32,32),it)}}
    @Test fun rankCancellation(){cancelled{GaicCompositionMath.select(listOf(full),floatArrayOf(0f),it)}}
    @Test fun highestModelScoreIsFirst(){val s=GaicCompositionMath.select(listOf(full,a,b),floatArrayOf(1f,2f,4f));assertEquals(b,s.first().crop);assertEquals(full,s.last().crop);assertEquals(3,s.size)}
    @Test fun originalWinsWithoutHeuristicRescue(){val s=GaicCompositionMath.select(listOf(full,a,b),floatArrayOf(9f,2f,4f));assertEquals(listOf(full),s.map{it.crop})}
    @Test fun equalScoresDoNotForceCrop(){assertEquals(1,GaicCompositionMath.select(listOf(full,a),floatArrayOf(2f,2f)).size)}
    @Test fun negativeScoresAreStillRanked(){val s=GaicCompositionMath.select(listOf(full,a),floatArrayOf(-3f,-2f));assertEquals(a,s.first().crop)}
    @Test fun nonfiniteScoresAreErrorsNotRuleFallback(){for(v in floatArrayOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))rejected{GaicCompositionMath.select(listOf(full,a),floatArrayOf(0f,v))}}
    @Test fun scoreCountMismatchRejected(){rejected{GaicCompositionMath.select(listOf(full,a),floatArrayOf(1f))}}
    @Test fun missingOriginalRejected(){rejected{GaicCompositionMath.select(listOf(a),floatArrayOf(1f))}}
    @Test fun duplicateNearCropsRemoved(){val near=Crop(.001,.001,.601,.801);val s=GaicCompositionMath.select(listOf(full,a,near),floatArrayOf(0f,2f,3f));assertEquals(listOf(near,full),s.map{it.crop})}
    @Test fun noHumanRulesOrIntentAdded(){val s=GaicCompositionMath.select(listOf(full,a,b,c),floatArrayOf(0f,3f,2f,1f));assertTrue(s.all{it.intent==null});assertEquals(3.0,s.first().improvement,0.0)}
    @Test fun atMostThreeModelAlternatives(){val crops=GaicCompositionMath.candidates(4000,3000,BokehOutput.ORIGINAL);val s=GaicCompositionMath.select(crops,FloatArray(crops.size){it.toFloat()});assertTrue(s.size in 1..4);assertEquals(1,s.count{it.crop==full});for((i,v)in s.withIndex())for(o in s.take(i))assertTrue(CompositionGeometry.iou(v.crop,o.crop)<=.88)}
    @Test fun analysis1220IsUnchanged(){assertEquals(1220 to 915,CompositionGeometry.analysisSize(4000,3000));assertEquals(1220 to 915,GaicCompositionMath.modelSize(1220,915))}
    @Test fun noSmallPhotoUpscale(){assertEquals(100 to 60,CompositionGeometry.analysisSize(100,60));assertEquals(100 to 60,GaicCompositionMath.modelSize(100,60))}
    @Test fun tinyAxesPadRatherThanResize(){assertEquals(1220 to 32,GaicCompositionMath.modelSize(1220,1));assertEquals(32 to 32,GaicCompositionMath.modelSize(1,1))}
    @Test fun oversizeAnalysisRejected(){rejected{GaicCompositionMath.modelSize(1221,1000)}}
    @Test fun rgbImageNetNchw(){val image=Planes.fromArgb(IntArray(32*32){0xffff8000.toInt()},32,32);val f=GaicCompositionMath.input(image);assertEquals((1f-.485f)/.229f,f[0],1e-6f);assertEquals((128f/255-.456f)/.224f,f[1024],1e-6f);assertEquals(-.406f/.225f,f[2048],1e-6f)}
    @Test fun tinyPaddingReplicatesPixels(){val f=GaicCompositionMath.input(Planes.fromArgb(intArrayOf(0xff204080.toInt()),1,1));for(c in 0..2)for(i in 0 until 1024)assertEquals(f[c*1024],f[c*1024+i],0f)}
    @Test fun unpaddedCoordinatesAndNoExtraPlusOne(){assertArrayEquals(floatArrayOf(0f,0f,1220f,1f,305f,0f,915f,1f),GaicCompositionMath.boxes(listOf(full,Crop(.25,0.0,.75,1.0)),1220,1),0f)}
    @Test fun noPreCropResizeInExport(){val p=CompositionOutput.plan(4000,3000,a,BokehOutput.ORIGINAL);assertEquals(2400,p.width);assertEquals(2400,p.height);assertEquals(4000,p.workWidth)}
}
