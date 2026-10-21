package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.*
import xyz.shuncongci.leica.CompositionGeometry.Crop

class CompositionOutputTest {
    private val crop=Crop(.1,.1,.9,.9)
    private fun plan(w:Int=4000,h:Int=3000,output:BokehOutput=BokehOutput.SIZE_2048,c:Crop=crop)=CompositionOutput.plan(w,h,c,output)
    private fun reject(f:()->Unit){try{f();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun crop2048IsFinalNotWorkBudget(){val p=plan();assertEquals(2048,p.width);assertEquals(1536,p.height);assertEquals(2560,p.workWidth);assertEquals(1920,p.workHeight)}
    @Test fun crop1024IsFinalBudget(){val p=plan(output=BokehOutput.SIZE_1024);assertEquals(1024,p.width);assertEquals(768,p.height);assertEquals(1280,p.workWidth)}
    @Test fun nativeCropNeverUsesAnalysisPixels(){val p=plan(output=BokehOutput.ORIGINAL);assertEquals(3200,p.width);assertEquals(2400,p.height);assertEquals(4000,p.workWidth);assertEquals(3000,p.workHeight)}
    @Test fun portraitAxis(){val p=plan(3072,4080);assertEquals(2048,p.height);assertTrue(p.workHeight>2048);assertTrue(abs(p.width.toDouble()/p.height-3072.0/4080)<.001)}
    @Test fun squareOutput(){val p=plan(c=Crop(.2,.1,.8,.9));assertEquals(2048,p.width);assertEquals(2048,p.height)}
    @Test fun fullSceneNotExpandedUnnecessarily(){val p=plan(c=CompositionGeometry.FULL);assertEquals(2048,p.workWidth);assertEquals(1536,p.workHeight)}
    @Test fun insufficientCropNotOfferedWhenSourceCanFillBudget(){assertFalse(CompositionOutput.eligible(2400,1800,crop,BokehOutput.SIZE_2048));reject{plan(2400,1800)}}
    @Test fun smallSourceStaysSmall(){val p=plan(1500,1000);assertEquals(1200,p.width);assertEquals(800,p.height);assertEquals(1500,p.workWidth)}
    @Test fun exactBudgetSourceNotUpscaled(){val p=plan(2048,1536,c=CompositionGeometry.FULL);assertEquals(2048,p.width);assertEquals(2048,p.workWidth);assertFalse(CompositionOutput.eligible(2048,1536,crop,BokehOutput.SIZE_2048))}
    @Test fun originalOutputAcceptsNativeCropRegardlessOfBudget(){assertEquals(8,plan(10,10,BokehOutput.ORIGINAL).width)}
    @Test fun randomizedCoverageNoUpscaleNoPostCropLoss(){val rng=java.util.Random(514)
        repeat(1000){val w=1+rng.nextInt(16000);val h=1+rng.nextInt(16000);val c=CompositionGeometry.fit(w,h,w.toDouble()/h,.7+.3*rng.nextDouble(),rng.nextDouble(),rng.nextDouble())
            for(o in BokehOutput.entries){if(!CompositionOutput.eligible(w,h,c,o))continue;val p=plan(w,h,o,c);val source=CompositionGeometry.pixels(p.crop,w,h);val render=CompositionGeometry.pixels(p.crop,p.workWidth,p.workHeight)
                assertEquals(min(o.longEdge?:max(source.w,source.h),max(source.w,source.h)),max(p.width,p.height))
                assertTrue(p.width<=source.w && p.height<=source.h);assertTrue(p.workWidth<=w && p.workHeight<=h)
                assertTrue(render.w>=p.width && render.h>=p.height)
            }
        }
    }
    @Test fun extremeAspectRemainsNonempty(){assertEquals(1,plan(50000,1,c=CompositionGeometry.FULL).height);assertEquals(1,plan(1,50000,c=CompositionGeometry.FULL).width)}
    @Test fun hugeNativeKeepsFullSourceBudget(){val p=plan(16384,12288,BokehOutput.ORIGINAL);assertEquals(16384,p.workWidth);assertEquals(12288,p.workHeight)}
    @Test fun focusSnapshotIsImmutable(){val focus=LegendaryMath.Point(.4f,.7f);val a=CompositionAppearance(true,true,focus,2.8f);assertSame(focus,a.focus);assertEquals(2.8f,a.aperture,0f)}
    @Test fun rejectsBlurWithoutFocus(){reject{CompositionAppearance(false,true,null,2.8f)}}
    @Test fun onlyBlurHonorsRequestedOutput(){
        assertEquals(1024 to 768,LeicaSizes.processingSize(4000,3000,true,BokehOutput.SIZE_1024))
        assertEquals(2048 to 1536,LeicaSizes.processingSize(4000,3000,true,BokehOutput.SIZE_2048))
        assertEquals(4000 to 3000,LeicaSizes.processingSize(4000,3000,true,BokehOutput.ORIGINAL))
        for(o in BokehOutput.entries)assertEquals(4000 to 3000,LeicaSizes.processingSize(4000,3000,false,o))
        for(blur in listOf(false,true))for(o in BokehOutput.entries)assertEquals(360 to 480,LeicaSizes.processingSize(360,480,blur,o))
    }
    @Test fun automaticOptionsAreFewAndExportable(){val f=CompositionAnalysis.extract(Planes.fromArgb(IntArray(320*240){i->if(i%320 in 35..120 && i/320 in 75..175)0xffe95522.toInt()else 0xff556677.toInt()},320,240))
        for(o in BokehOutput.entries){val s=CompositionAnalysis.automatic(f,4000,3000,o);assertTrue(s.size in 1..4);assertEquals(CompositionGeometry.FULL,s[0].crop)
            for((i,v)in s.withIndex()){assertTrue(CompositionOutput.eligible(4000,3000,v.crop,o));for(other in s.take(i))assertTrue(CompositionGeometry.iou(v.crop,other.crop)<=.88)}
        }
    }
    @Test fun automaticCancellation(){val f=CompositionAnalysis.extract(Planes(10,10));try{CompositionAnalysis.automatic(f,4000,3000,BokehOutput.ORIGINAL,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    private class Decoded(val image:Any){
        val width=image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height=image.javaClass.getMethod("getHeight").invoke(image) as Int
        fun pixel(x:Int,y:Int)=image.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(image,x,y) as Int
    }
    private fun resized(w:Int,h:Int,c:Crop,tw:Int,th:Int,pixel:(Int,Int)->Int):Decoded{
        val out=ByteArrayOutputStream();CompositionExport.writeSized(out,w,h,c,tw,th,Cancel()){b->IntArray(b.w*b.h){pixel(b.left+it%b.w,b.top+it/b.w)}}
        return Decoded(checkNotNull(Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java).invoke(null,ByteArrayInputStream(out.toByteArray()))))
    }
    @Test fun exact2048AfterResizedCrop(){val p=plan();val im=resized(4000,3000,p.crop,p.width,p.height){_,_->0xff2387c4.toInt()};assertEquals(2048,im.width);assertEquals(1536,im.height);assertEquals(0xff2387c4.toInt(),im.pixel(2047,1535))}
    @Test fun exact1024AfterResizedCrop(){val p=plan(output=BokehOutput.SIZE_1024);val im=resized(4000,3000,p.crop,p.width,p.height){_,_->-1};assertEquals(1024,im.width);assertEquals(768,im.height)}
    @Test fun downsamplingUsesAllPixelsNotNearest(){val im=resized(8,6,CompositionGeometry.FULL,4,3){x,y->if((x+y)%2==0)-1 else 0xff000000.toInt()};for(y in 0..2)for(x in 0..3)assertEquals(0xff808080.toInt(),im.pixel(x,y))}
    @Test fun transparentPixelsDoNotBleedColor(){val im=resized(2,1,CompositionGeometry.FULL,1,1){x,_->if(x==0)0xffff0000.toInt()else 0x000000ff};assertEquals(0x80ff0000.toInt(),im.pixel(0,0))}
    @Test fun cropCoordinatesAppliedBeforeSampling(){val im=resized(8,6,Crop(.25,0.0,.75,1.0),2,3){x,y->0xff000000.toInt() or(x*20 shl 16)or(y*20)};assertEquals(0xff32000a.toInt(),im.pixel(0,0));assertEquals(0xff5a005a.toInt(),im.pixel(1,2))}
    @Test fun nonintegerAreaWeights(){val im=resized(3,1,CompositionGeometry.FULL,2,1){x,_->0xff000000.toInt() or(x*90 shl 16)};assertEquals(0xff1e0000.toInt(),im.pixel(0,0));assertEquals(0xff960000.toInt(),im.pixel(1,0))}
    @Test fun nativePixelsAndAlphaNotResampled(){val im=resized(3,1,CompositionGeometry.FULL,3,1){x,_->x*0x41765132};for(x in 0..2)assertEquals(x*0x41765132,im.pixel(x,0))}
    @Test fun noUpscaleOfLowResolutionFinishedImage(){reject{CompositionExport.writeSized(ByteArrayOutputStream(),1536,1024,crop,2048,1365,Cancel()){error("Must not read")}}}
    @Test fun resizedStripsBounded(){var peak=0;var reads=0;CompositionExport.writeSized(ByteArrayOutputStream(),4000,3000,crop,1024,768,Cancel()){b->peak=max(peak,b.w*b.h);reads++;IntArray(b.w*b.h){-1}};assertTrue(peak<=262144);assertTrue(reads>1)}
    @Test fun cancelledResizeNeverReadsOrPublishes(){val out=ByteArrayOutputStream();try{CompositionExport.writeSized(out,4000,3000,crop,1024,768,Cancel().apply{cancelled=true}){error("Must not read")};fail()}catch(_:CancellationException){};assertEquals(0,out.size())}
    @Test fun badReaderIsRejected(){reject{CompositionExport.writeSized(ByteArrayOutputStream(),8,6,CompositionGeometry.FULL,4,3,Cancel()){intArrayOf(1)}}}
}
