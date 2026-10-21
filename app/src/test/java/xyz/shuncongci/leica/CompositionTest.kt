package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.CompositionGeometry.Crop
import xyz.shuncongci.leica.CompositionGeometry.Point

class CompositionTest {
    private fun image(w:Int=320,h:Int=240,cx:Double=.33,cy:Double=.4):Planes {
        return Planes.fromArgb(IntArray(w*h){i->val dx=i%w-w*cx;val dy=i/w-h*cy
            if(dx*dx/(w*w*.012)+dy*dy/(h*h*.025)<1)0xffe85c35.toInt()else 0xff525963.toInt()},w,h)
    }
    private fun failExpected(action:()->Unit){try{action();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun analysisBudget1220(){assertEquals(919 to 1220,CompositionGeometry.analysisSize(3072,4080));assertEquals(1220 to 919,CompositionGeometry.analysisSize(4080,3072));assertEquals(1220 to 1220,CompositionGeometry.analysisSize(2000,2000))}
    @Test fun smallNeverUpscaled(){for(w in listOf(1,17,1219,1220))for(h in listOf(1,65,1220)){assertEquals(w to h,CompositionGeometry.analysisSize(w,h))}}
    @Test fun analysisExtremeAspect(){assertEquals(1220 to 1,CompositionGeometry.analysisSize(200000,1));assertEquals(1 to 1220,CompositionGeometry.analysisSize(1,200000))}
    @Test fun analysisRejectsInvalid(){failExpected{CompositionGeometry.analysisSize(0,20)};failExpected{CompositionGeometry.analysisSize(1,-1)}}
    @Test fun decodeAlwaysCoversAnalysis(){for(w in listOf(1,100,1220,1221,3072,50000))for(h in listOf(1,200,1221,4080)){val s=CompositionGeometry.sample(w,h);val (tw,th)=CompositionGeometry.analysisSize(w,h);assertTrue(w/s>=tw && h/s>=th);assertTrue(s and (s-1)==0)}}
    @Test fun ratiosPreservedAcrossSizes(){for((w,h) in listOf(3072 to 4080,4080 to 3072,1200 to 1200,10000 to 100))for(r in listOf(1.0,4.0/3,1.5,16.0/9,.75,2.0/3,9.0/16)){val c=CompositionGeometry.fit(w,h,r);assertEquals(r,c.w*w/(c.h*h),1e-8);assertTrue(c.l>=0 && c.r<=1 && c.t>=0 && c.b<=1)}}
    @Test fun fullPixelBoxExact(){val b=CompositionGeometry.pixels(CompositionGeometry.FULL,3072,4080);assertEquals(NativeImageMath.Box(0,0,3072,4080),b)}
    @Test fun sourcePixelsNotAnalysisPixels(){val c=Crop(.125,.25,.875,.75);val b=CompositionGeometry.pixels(c,4000,3000);assertEquals(3000,b.w);assertEquals(1500,b.h);assertTrue(b.w>1220)}
    @Test fun snappedStable(){val c=Crop(.123456,.234567,.823456,.923456);val s=CompositionGeometry.snapped(c,3072,4080);assertEquals(s,CompositionGeometry.snapped(s,3072,4080));assertEquals(CompositionGeometry.pixels(c,3072,4080),CompositionGeometry.pixels(s,3072,4080))}
    @Test fun subpixelCropAtEdgeStillNonempty(){val b=CompositionGeometry.pixels(Crop(.9999,.9999,1.0,1.0),100,100);assertEquals(NativeImageMath.Box(99,99,100,100),b)}
    @Test fun moveClampsWithoutResizing(){val c=Crop(.2,.2,.7,.8);val b=CompositionGeometry.move(c,9.0,-9.0);assertEquals(c.w,b.w,1e-12);assertEquals(c.h,b.h,1e-12);assertEquals(1.0,b.r,0.0);assertEquals(0.0,b.t,0.0)}
    @Test fun zoomRatioAndBounds(){var c=Crop(.2,.3,.8,.7);val ratio=c.w/c.h;repeat(50){c=CompositionGeometry.zoom(c,1.1);assertEquals(ratio,c.w/c.h,1e-8);assertTrue(c.area<=1)};repeat(100){c=CompositionGeometry.zoom(c,.8);assertEquals(ratio,c.w/c.h,1e-8);assertTrue(c.w>0 && c.h>0)}}
    @Test fun rejectsBadCoordinates(){failExpected{Crop(Double.NaN,0.0,1.0,1.0)};failExpected{Crop(-.1,0.0,1.0,1.0)};failExpected{Crop(.8,0.0,.7,1.0)};failExpected{Point(Double.POSITIVE_INFINITY,.5)}}
    @Test fun iouSymmetric(){val a=Crop(0.0,0.0,.5,1.0);val b=Crop(.5,0.0,1.0,1.0);assertEquals(0.0,CompositionGeometry.iou(a,b),0.0);assertEquals(1.0,CompositionGeometry.iou(a,a),0.0);assertEquals(.5,CompositionGeometry.iou(a,CompositionGeometry.FULL),1e-8)}
    @Test fun recipeRequiresExplicitApply(){val r=CompositionRecipe();assertFalse(r.canSave);r.edit(Crop(.1,.1,.9,.9));assertFalse(r.canSave);r.apply(4000,3000);assertTrue(r.canSave)}
    @Test fun editInvalidatesCommittedExport(){val r=CompositionRecipe();r.apply(4000,3000);r.edit(Crop(.1,.1,.9,.9));assertFalse(r.canSave);assertNull(r.applied)}
    @Test fun undoAndRestoreNeverOverwriteSource(){val r=CompositionRecipe();val a=Crop(.1,.1,.9,.9);r.edit(a);r.edit(Crop(.2,.2,.8,.8));r.undo();assertEquals(a,r.draft);r.reset();assertEquals(CompositionGeometry.FULL,r.draft);r.undo();assertEquals(a,r.draft)}
    @Test fun resumeEditingRequiresReconfirm(){val r=CompositionRecipe();r.apply(1000,800);r.resumeEditing();assertFalse(r.canSave);assertEquals(CompositionGeometry.FULL,r.draft)}
    @Test fun restoredRecipeMustMatch(){val r=CompositionRecipe();val a=Crop(.1,.1,.9,.9);r.restore(a,CompositionGeometry.FULL);assertFalse(r.canSave);r.restore(a,a);assertTrue(r.canSave)}
    @Test fun historyBounded(){val r=CompositionRecipe();repeat(100){r.edit(CompositionGeometry.fit(1000,800,1.25,.7+it*.001))};var n=0;while(r.canUndo){r.undo();n++};assertEquals(40,n)}
    @Test fun integralMatchesDirect(){val w=7;val h=5;val a=DoubleArray(w*h){it.toDouble()};val s=CompositionAnalysis.Integral(w,h,a);assertEquals(a.sum(),s.sum(0,0,w,h),1e-8);assertEquals((1..3).sumOf{y->(2..5).sumOf{x->a[y*w+x]}},s.sum(2,1,6,4),1e-8)}
    @Test fun uniformKeepsFull(){val f=CompositionAnalysis.extract(Planes.fromArgb(IntArray(320*240){0xff777777.toInt()},320,240));assertFalse(f.reliable);assertEquals(1,CompositionAnalysis.suggest(f,4000,3000,4.0/3).size)}
    @Test fun visualLocationRespondsToPixels(){val a=CompositionAnalysis.extract(image(cx=.25));val b=CompositionAnalysis.extract(image(cx=.75));assertTrue(a.reliable && b.reliable);assertTrue(a.centre(CompositionGeometry.FULL).x<.4);assertTrue(b.centre(CompositionGeometry.FULL).x>.6)}
    @Test fun meaningfulAlternativesNotAlwaysKeep(){val f=CompositionAnalysis.extract(image(cx=.23,cy=.43));val suggestions=CompositionAnalysis.suggest(f,4000,3000,4.0/3);assertTrue("Off-centre synthetic object must exercise crop path",suggestions.size>1);assertEquals(CompositionGeometry.FULL,suggestions[0].crop)}
    @Test fun proposalsBoundedDeduplicatedAndSafe(){val f=CompositionAnalysis.extract(image());val items=CompositionAnalysis.suggest(f,4000,3000,4.0/3).drop(1);assertTrue(items.size<=3);assertEquals(items.size,items.map{it.intent}.distinct().size);for((i,a)in items.withIndex()){assertTrue(CompositionAnalysis.safe(f,a.crop,4000,3000,null));assertTrue(a.improvement>.028);for(b in items.take(i))assertTrue(CompositionGeometry.iou(a.crop,b.crop)<=.88)}}
    @Test fun suggestionsDeterministic(){val f=CompositionAnalysis.extract(image());assertEquals(CompositionAnalysis.suggest(f,4000,3000,1.5),CompositionAnalysis.suggest(f,4000,3000,1.5))}
    @Test fun designatedPointPreserved(){val f=CompositionAnalysis.extract(image());val p=Point(.2,.4);for(item in CompositionAnalysis.suggest(f,4000,3000,4.0/3,p).drop(1))assertTrue(item.crop.contains(p,.06))}
    @Test fun neverAcceptCuttingStrongRegion(){val f=CompositionAnalysis.extract(image(cx=.7));assertFalse(CompositionAnalysis.safe(f,Crop(0.0,0.0,.55,1.0),4000,3000,null))}
    @Test fun noSemanticClaimsWithoutModel(){val f=CompositionAnalysis.extract(image());for(s in CompositionAnalysis.suggest(f,4000,3000,1.5)){assertFalse(s.reason.contains("人脸"));assertFalse(s.reason.contains("视线"));assertFalse(s.reason.contains("关节"))}}
    @Test fun cancelExtract(){val c=Cancel().apply{cancelled=true};try{CompositionAnalysis.extract(image(),c);fail()}catch(_:CancellationException){}}
    @Test fun cancelSearch(){val f=CompositionAnalysis.extract(image());try{CompositionAnalysis.suggest(f,4000,3000,1.0,cancel=Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun rejectOversizeAnalysis(){failExpected{CompositionAnalysis.extract(Planes(1221,1))}}
    @Test fun analyseSinglePixel(){val f=CompositionAnalysis.extract(Planes.fromArgb(intArrayOf(-1),1,1));assertEquals(1,CompositionAnalysis.suggest(f,1,1,1.0).size)}
    // Android's compile bootclasspath excludes java.desktop; the direct JVM runner has the standard ImageIO decoder.
    private class Decoded(val image:Any) {
        val width=image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height=image.javaClass.getMethod("getHeight").invoke(image) as Int
        private val getter=image.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        fun getRGB(x:Int,y:Int)=getter.invoke(image,x,y) as Int
    }
    private fun stream(w:Int,h:Int,crop:Crop,pixel:(Int,Int)->Int):Decoded {
        val bytes=ByteArrayOutputStream();CompositionExport.write(bytes,w,h,crop,Cancel()){b->IntArray(b.w*b.h){i->pixel(b.left+i%b.w,b.top+i/b.w)}}
        val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java).invoke(null,ByteArrayInputStream(bytes.toByteArray()))?:error("Invalid PNG")
        return Decoded(decoded)
    }
    @Test fun streamPreservesEveryPixelAndAlpha(){val w=131;val h=159;fun color(x:Int,y:Int)=((x*13+y*7)and 255 shl 24)or((x*9 and 255)shl 16)or((y*11 and 255)shl 8)or((x+y)and 255)
        val c=Crop(.1,.2,.9,.95);val b=CompositionGeometry.pixels(c,w,h);val png=stream(w,h,c,::color);assertEquals(b.w,png.width);assertEquals(b.h,png.height);for(y in 0 until b.h)for(x in 0 until b.w)assertEquals(color(b.left+x,b.top+y),png.getRGB(x,y))}
    @Test fun exportIsNot1220Preview(){val png=stream(4000,3000,Crop(.1,.1,.9,.9)){x,y->0xff000000.toInt() or(x and 255 shl 16)or(y and 255)};assertEquals(3200,png.width);assertEquals(2400,png.height)}
    @Test fun exportUsesBoundedStrips(){var maxPixels=0;var reads=0;val out=ByteArrayOutputStream();CompositionExport.write(out,4000,3000,CompositionGeometry.FULL,Cancel()){b->reads++;maxPixels=max(maxPixels,b.w*b.h);IntArray(b.w*b.h){-1}};assertTrue(reads>1);assertTrue(maxPixels<=262144)}
    @Test fun exportAllEightExifMappings(){val w=127;val h=93
        for(o in 1..8){val (ow,oh)=NativeImageMath.size(w,h,o);val c=Crop(.11,.13,.89,.91);val b=CompositionGeometry.pixels(c,ow,oh)
            val png=stream(ow,oh,c){x,y->val (sx,sy)=NativeImageMath.encoded(x,y,w,h,o);0xff000000.toInt()or(sx shl 16)or(sy shl 8)or(sx xor sy)}
            for(y in 0 until b.h)for(x in 0 until b.w){val (sx,sy)=NativeImageMath.encoded(x+b.left,y+b.top,w,h,o);assertEquals(0xff000000.toInt()or(sx shl 16)or(sy shl 8)or(sx xor sy),png.getRGB(x,y))}
        }}
    @Test fun encodedBoxesCoverUprightCrop(){for(o in 1..8){val(w,h)=NativeImageMath.size(3072,4080,o);val b=CompositionGeometry.pixels(Crop(.1,.2,.9,.8),w,h);val e=NativeImageMath.encodedBox(b,3072,4080,o);assertEquals(b.w.toLong()*b.h,e.w.toLong()*e.h);assertTrue(e.left>=0 && e.right<=3072 && e.top>=0 && e.bottom<=4080)}}
    @Test fun cancelledExportDoesNotFinish(){val c=Cancel();var read=0;val out=ByteArrayOutputStream();try{CompositionExport.write(out,1000,1000,CompositionGeometry.FULL,c){b->read++;if(read==2)c.cancelled=true;IntArray(b.w*b.h){-1}};fail()}catch(_:CancellationException){};assertTrue(read<=2);val bytes=out.toByteArray();assertFalse(String(bytes,Charsets.ISO_8859_1).endsWith("IEND\u00aeB`\u0082"))}
    @Test fun invalidReaderSizeRejected(){try{CompositionExport.write(ByteArrayOutputStream(),20,20,CompositionGeometry.FULL,Cancel()){IntArray(1)};fail()}catch(_:IllegalArgumentException){}}
    @Test fun sourceSelectionChangesOutput(){val c=Crop(.1,.1,.9,.9);val original=stream(100,100,c){_,_->0xff123456.toInt()};val result=stream(100,100,c){_,_->0xff654321.toInt()};assertEquals(0xff123456.toInt(),original.getRGB(0,0));assertEquals(0xff654321.toInt(),result.getRGB(0,0))}
    @Test fun cancelledBeforeExportReadsNothing(){var read=false;try{CompositionExport.write(ByteArrayOutputStream(),20,20,CompositionGeometry.FULL,Cancel().apply{cancelled=true}){read=true;IntArray(400)};fail()}catch(_:CancellationException){};assertFalse(read)}
}
