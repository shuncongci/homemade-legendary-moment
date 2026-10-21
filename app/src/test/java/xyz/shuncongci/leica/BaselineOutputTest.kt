package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** New coverage only for UI/output options; baseline fast pipeline tests remain. */
class BaselineOutputTest {
    @Test fun labelsAndDefaultAreExact(){
        assertEquals(listOf("原图像素","2048出图","1024出图"),BokehOutput.entries.map{it.label})
        assertEquals(2048 to 1536,LeicaSizes.processingSize(4000,3000,true))
    }
    @Test fun originalOutputKeepsEntirePhotoAndExifAxes(){
        for((w,h) in listOf(4000 to 3000,3000 to 4000,6000 to 4000,360 to 480)){
            assertEquals(w to h,LeicaSizes.workingSize(w,h,BokehOutput.ORIGINAL))
            assertEquals(w to h,LeicaSizes.processingSize(w,h,true,BokehOutput.ORIGINAL))
        }
    }
    @Test fun default2048PreservesFastBaselineSizing(){
        for((w,h) in listOf(4000 to 3000,3000 to 4000,5000 to 3000,360 to 480,1 to 100000)){
            assertEquals(LeicaSizes.workingSize(w,h),LeicaSizes.workingSize(w,h,BokehOutput.SIZE_2048))
            assertEquals(LeicaSizes.modelSize(w,h,true),LeicaSizes.modelSize(w,h,true,BokehOutput.SIZE_2048))
        }
    }
    @Test fun size1024PreservesWholeFrameWithoutCropping(){
        assertEquals(1024 to 768,LeicaSizes.workingSize(4000,3000,BokehOutput.SIZE_1024))
        assertEquals(768 to 1024,LeicaSizes.workingSize(3000,4000,BokehOutput.SIZE_1024))
        assertEquals(1024 to 614,LeicaSizes.workingSize(5000,3000,BokehOutput.SIZE_1024))
    }
    @Test fun ganLong1024IsIndependentOfPhotoSizeBokehAndOutput(){
        for(output in BokehOutput.entries)for(blur in listOf(false,true))
            for((w,h) in listOf(4000 to 3000,3000 to 4000,6000 to 4000,12000 to 9000,360 to 480,360 to 360,100 to 10000)){
                val actual=LeicaSizes.modelSize(w,h,blur,output)
                assertEquals(LeicaSizes.modelSize(w,h),actual)
                assertEquals(1024,kotlin.math.max(actual.first,actual.second))
                assertTrue(kotlin.math.abs(actual.first-1024.0*w/maxOf(w,h))<=.5)
                assertTrue(kotlin.math.abs(actual.second-1024.0*h/maxOf(w,h))<=.5)
                val work=LeicaSizes.processingSize(w,h,blur,output)
                val fromWork=LeicaSizes.modelSize(work.first,work.second,blur,output)
                assertEquals(1024,kotlin.math.max(fromWork.first,fromWork.second))
            }
        for(output in BokehOutput.entries){
            assertEquals(1024 to 768,LeicaSizes.modelSize(4000,3000,true,output))
            assertEquals(768 to 1024,LeicaSizes.modelSize(3000,4000,true,output))
            assertEquals(1024 to 1024,LeicaSizes.modelSize(6000,6000,true,output))
        }
    }
    @Test fun colorOnlyIgnoresBokehOutputAndKeepsLong1024Analysis(){
        for(output in BokehOutput.entries){
            assertEquals(6000 to 4000,LeicaSizes.processingSize(6000,4000,false,output))
            assertEquals(1024 to 683,LeicaSizes.modelSize(6000,4000,false,output))
            assertEquals(768 to 1024,LeicaSizes.modelSize(360,480,false,output))
        }
    }
    @Test fun decodeSamplingDependsOnlyOnChosenOutput(){
        assertEquals(1,LeicaSizes.workingSample(12000,9000,BokehOutput.ORIGINAL))
        assertEquals(4,LeicaSizes.workingSample(8192,6144,BokehOutput.SIZE_2048))
        assertEquals(8,LeicaSizes.workingSample(8192,6144,BokehOutput.SIZE_1024))
    }
    @Test fun ganDecodeSamplingDependsOnSourceLongEdgeOnly(){
        assertEquals(2,LeicaSizes.ganSample(4000,3000))
        assertEquals(8,LeicaSizes.ganSample(12000,9000))
        assertEquals(16,LeicaSizes.ganSample(24000,18000))
        assertEquals(1,LeicaSizes.ganSample(360,480))
        for((w,h) in listOf(4001 to 3001,12000 to 9000,24000 to 18000,100000 to 100000,Int.MAX_VALUE to Int.MAX_VALUE)){
            val sample=LeicaSizes.ganSample(w,h)
            assertEquals(sample,LeicaSizes.ganSample(h,w))
            assertEquals(0,sample and (sample-1))
            assertTrue(kotlin.math.max(w,h)/sample>=1024)
            assertTrue(kotlin.math.max(w,h)/sample<2048)
        }
    }
    @Test fun sampledPhotoIsResampledToExact1024BeforeGan(){
        val (w,h)=LeicaSizes.modelSize(4001,3001)
        assertEquals(1024 to 768,w to h)
        val sampled=Planes(2001,1501)
        sampled.p0.fill(80);sampled.p1.fill(120);sampled.p2.fill(100)
        val input=Resample.resize(sampled,w,h,Resample.Filter.BILINEAR)
        assertEquals(1024,input.width);assertEquals(768,input.height)
        assertTrue(input.p0.all{it.toInt()==80})
        assertTrue(input.p1.all{it.toInt()==120})
        assertTrue(input.p2.all{it.toInt()==100})
        assertEquals(768 to 1024,LeicaSizes.modelSize(3001,4001))
        assertEquals(4001 to 3001,LeicaSizes.processingSize(4001,3001,false))
    }
    @Test fun automaticColorBudgetIs50MpAndBokehRemainsExplicit(){
        assertEquals(4000 to 3000,LeicaSizes.processingSize(4000,3000,false))
        assertEquals(5000 to 2500,LeicaSizes.colorSize(5000,2500))
        assertEquals(8164 to 6123,LeicaSizes.colorSize(16384,12288))
        assertEquals(2,LeicaSizes.colorSample(16384,12288))
        assertEquals(1,LeicaSizes.colorSample(4000,3000))
        for((w,h) in listOf(16384 to 12288,12288 to 16384,6000 to 4000,9000 to 3000,20000 to 10000,100000 to 200)){
            val (cw,ch)=LeicaSizes.colorSize(w,h)
            assertTrue(cw.toLong()*ch<=LeicaSizes.COLOR_MAX_PIXELS)
            val expectedPixels=minOf(w.toLong()*h,LeicaSizes.COLOR_MAX_PIXELS)
            assertTrue(cw.toLong()*ch>=expectedPixels*99/100)
            assertTrue(kotlin.math.abs(cw.toDouble()/ch-w.toDouble()/h)/(w.toDouble()/h)<=0.01)
            val s=LeicaSizes.colorSample(w,h)
            assertTrue(w/s>=cw && h/s>=ch)
        }
        assertEquals(2048 to 1536,LeicaSizes.processingSize(16384,12288,true,BokehOutput.SIZE_2048))
        assertEquals(1024 to 768,LeicaSizes.processingSize(16384,12288,true,BokehOutput.SIZE_1024))
        assertEquals(16384 to 12288,LeicaSizes.processingSize(16384,12288,true,BokehOutput.ORIGINAL))
        assertEquals(1024 to 768,LeicaSizes.modelSize(16384,12288))
    }
    @Test fun colorModelLongEdgeIsBoundedForExtremeAspectRatios(){
        for((w,h) in listOf(1 to Int.MAX_VALUE,Int.MAX_VALUE to 1,100 to 10000,10000 to 100,Int.MAX_VALUE to Int.MAX_VALUE)){
            val (mw,mh)=LeicaSizes.modelSize(w,h)
            assertEquals(1024,maxOf(mw,mh));assertTrue(mw in 1..1024 && mh in 1..1024)
        }
        assertEquals(1 to 1024,LeicaSizes.modelSize(1,Int.MAX_VALUE))
        assertEquals(1024 to 10,LeicaSizes.modelSize(10000,100))
    }
    @Test fun ganSamplingUsesLongEdgeForPanoramas(){
        assertEquals(8,LeicaSizes.ganSample(8192,100))
        assertEquals(8,LeicaSizes.ganSample(100,8192))
        assertEquals(1,LeicaSizes.ganSample(1024,768))
        assertEquals(2,LeicaSizes.ganSample(2048,1536))
    }
    @Test fun colorModelLongEdgeTracksExifAxes(){
        for(exif in 1..8){
            val (w,h)=NativeImageMath.size(4000,3000,exif)
            assertEquals(if(exif in 5..8)768 to 1024 else 1024 to 768,LeicaSizes.modelSize(w,h))
        }
    }
    @Test fun colorModelRejectsInvalidDimensions(){
        for((w,h) in listOf(0 to 3000,4000 to 0,-1 to 100,100 to -1)){
            try{LeicaSizes.modelSize(w,h);fail("invalid model shape accepted")}catch(expected:IllegalArgumentException){}
            try{LeicaSizes.ganSample(w,h);fail("invalid decode shape accepted")}catch(expected:IllegalArgumentException){}
        }
    }
    @Test fun uiStageTimerAccumulatesAndStopsOnce(){
        var now=0L;val t=StageTiming{now}
        t.start("深度");now=1250;t.start("虚化模型");now=3000;t.start("虚化模型");now=4000;t.finish()
        assertEquals("深度 1.3 s｜虚化模型 2.8 s",t.summary())
        now=5000;t.finish();assertEquals("深度 1.3 s｜虚化模型 2.8 s",t.summary())
    }
}
