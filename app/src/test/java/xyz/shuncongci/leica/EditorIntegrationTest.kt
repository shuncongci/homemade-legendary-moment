package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.*
import xyz.shuncongci.leica.CompositionGeometry.Crop

class EditorIntegrationTest {
    private val full=CompositionGeometry.FULL
    private val crop=Crop(.125,.125,.875,.875)
    private val focus=LegendaryMath.Point(.2f,.6f)
    private fun image(w:Int=65,h:Int=43)=Planes.fromArgb(IntArray(w*h){i->0xff000000.toInt() or (((i*37)%256) shl 16) or (((i*13)%256) shl 8) or ((i*19)%256)},w,h)
    private fun infer(a:LegendaryMath.Frame,b:LegendaryMath.Frame)=LegendaryMath.Frame(a.w,a.h,3,FloatArray(a.data.size){i->(a.data[i]*.91f+abs(b.data[i%a.size])*.03f).coerceIn(0f,1f)})
    @Test fun cached2048DoesNotRerenderOrEnlarge(){val p=EditorCrop.plan(2048,1536,crop,BokehOutput.SIZE_2048);assertEquals(1536,p.width);assertEquals(1152,p.height);assertEquals(p.width,p.availableWidth)}
    @Test fun requested1024IsAppliedAfterCropping(){val p=EditorCrop.plan(4000,3000,crop,BokehOutput.SIZE_1024);assertEquals(1024,p.width);assertEquals(768,p.height);assertEquals(3000,p.availableWidth)}
    @Test fun originalMeansExistingPixelsNotRecoveryOfSource(){val p=EditorCrop.plan(2048,1536,crop,BokehOutput.ORIGINAL);assertEquals(1536,p.width);assertEquals(1152,p.height)}
    @Test fun tinySourcesAreNeverEnlarged(){for(o in BokehOutput.entries)for((w,h) in listOf(1 to 1,1 to 5000,7 to 11,900 to 700)){val p=EditorCrop.plan(w,h,crop,o);assertTrue(p.width in 1..p.availableWidth);assertTrue(p.height in 1..p.availableHeight)}}
    @Test fun originalFullPlanIsExact(){val p=EditorCrop.plan(4031,3023,full,BokehOutput.ORIGINAL);assertEquals(4031,p.width);assertEquals(3023,p.height);assertEquals(full,p.crop)}
    @Test fun composeFullIsPixelIdempotent(){val w=4031;val h=3023;var p=crop;val expected=CompositionGeometry.pixels(p,w,h);repeat(100){p=EditorCrop.combine(p,full,w,h);assertEquals(expected,CompositionGeometry.pixels(p,w,h))}}
    @Test fun nestedCropUsesIntegerParentOffsets(){val w=4031;val h=3023;val a=CompositionGeometry.pixels(crop,w,h);val local=Crop(.2,.1,.8,.7);val b=CompositionGeometry.pixels(local,a.w,a.h);val got=CompositionGeometry.pixels(EditorCrop.combine(crop,local,w,h),w,h);assertEquals(NativeImageMath.Box(a.left+b.left,a.top+b.top,a.left+b.right,a.top+b.bottom),got)}
    @Test fun repeatedCropStaysInsideCurrentFrame(){var c=full;repeat(10){val next=EditorCrop.combine(c,crop,10001,7001);assertTrue(next.l>=c.l && next.t>=c.t && next.r<=c.r && next.b<=c.b);c=next}}
    @Test fun explicitRegenerationTargetsCropNotWholeScene(){val p=EditorCrop.renderPlan(4000,3000,crop,BokehOutput.SIZE_2048);assertEquals(2048,p.width);assertTrue(p.workWidth>2048);assertTrue(p.workWidth<=4000)}
    @Test fun insufficientOriginalStillDoesNotEnlargeOnExplicitRegenerate(){val p=EditorCrop.renderPlan(2500,1800,Crop(.4,.4,.6,.6),BokehOutput.SIZE_2048);assertEquals(500,p.width);assertEquals(360,p.height)}
    @Test fun applyingRawCompositionCreatesUnsavedEditableResult(){val f=PhotoFlow();f.photoLoaded();f.compositionApplied();assertTrue(f.hasResult);assertTrue(f.canSave);assertFalse(f.saved);assertFalse(f.resultStale);assertNull(f.focus)}
    @Test fun applyDoesNotSilentlyMarkStaleParametersRendered(){val f=PhotoFlow();f.photoLoaded();f.generated();f.parametersChanged();f.saved();f.compositionApplied();assertTrue(f.resultStale);assertFalse(f.saved);assertTrue(f.canSave)}
    @Test fun changingOnlyCompositionWorkflowDoesNotInvalidatePixels(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectProcessingMode(ProcessingMode.COLOR_ONLY);assertFalse(f.resultStale);assertTrue(PhotoWorkflow.COLOR_COMPOSITION.composeAfter)}
    @Test fun realProcessingChangeStillInvalidatesPixels(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectProcessingMode(ProcessingMode.BLUR_ONLY);assertTrue(f.resultStale)}
    @Test fun changingOutputAfterSaveAllowsNewSizeWithoutPretendingToRerender(){val f=PhotoFlow();f.photoLoaded();f.generated();f.saved();f.outputChanged();assertFalse(f.saved);assertTrue(f.canSave);assertTrue(f.resultStale)}
    @Test fun cancelledAnalysisPreservesSavedImage(){val f=PhotoFlow();f.photoLoaded();f.generated();f.saved();f.begin(PhotoFlow.Operation.COMPOSITION);assertFalse(f.canSave);f.cancel();f.finish();assertTrue(f.hasResult);assertTrue(f.saved);assertFalse(f.resultStale)}
    @Test(expected=IllegalStateException::class) fun busyCannotCommitCrop(){val f=PhotoFlow();f.photoLoaded();f.begin(PhotoFlow.Operation.COMPOSITION);f.compositionApplied()}
    @Test fun noRealPassMeansNoTileRevealOrWave(){val r=BokehTileReveal();r.advance(100000f);for(i in 0 until 12){assertEquals(0f,r.opacity(i,4,3,focus),0f);assertEquals(0f,r.pulse(i,4,3,focus),0f)};assertFalse(r.active)}
    @Test fun actualPassAnimatesDifferentTilesAtDifferentTimes(){val r=BokehTileReveal();r.accept(1);r.advance(210f);val p=(0 until 88).map{r.opacity(it,11,8,focus)};assertTrue(p.distinct().size>4);assertTrue(p.any{it>0 && it<1});assertTrue((0 until 88).any{r.pulse(it,11,8,focus)>0})}
    @Test fun completedSweepDoesNotReplayWhileWaiting(){val r=BokehTileReveal();r.accept(1);r.advance(720f);assertFalse(r.active);repeat(10){r.advance(1000f);for(i in 0 until 12){assertEquals(1f,r.opacity(i,4,3,focus),0f);assertEquals(0f,r.pulse(i,4,3,focus),0f)}}}
    @Test fun duplicateAndOutOfOrderPassesDoNotRestart(){val r=BokehTileReveal();r.accept(2);r.advance(200f);assertFalse(r.accept(2));assertFalse(r.accept(1));assertEquals(200f,r.elapsed,0f);assertTrue(r.accept(3));assertEquals(0f,r.elapsed,0f)}
    @Test fun reducedMotionSettlesOnlyActualData(){val r=BokehTileReveal();r.settle();assertEquals(0,r.pass);r.accept(1);r.settle();assertEquals(1f,r.opacity(0,1,1,focus),0f);assertFalse(r.active)}
    @Test fun resettingCannotCarryPassAcrossPhotos(){val r=BokehTileReveal();r.accept(3);r.reset();assertEquals(0,r.pass);assertFalse(r.active);assertTrue(r.accept(1))}
    @Test(expected=IllegalArgumentException::class) fun invalidPassRejected(){BokehTileReveal().accept(0)}
    @Test(expected=IllegalArgumentException::class) fun invalidClockRejected(){BokehTileReveal().advance(Float.NaN)}
    @Test fun realPassWaitsForColorTailBeforeBokeh(){val m=DevelopMotion();m.startColor(true);m.colorCompleted();m.startBokeh();m.bokehPassCompleted();assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);m.advance(DevelopMotion.COLOR_MS-1f);assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);m.advance(1f);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage);m.advance(100000f);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage)}
    @Test fun finalResultOnlySettlesNoSecondLift(){val m=DevelopMotion();m.startBokeh();m.advance(550f);m.bokehPassCompleted();m.resultCompleted(true);var lift=m.bokehLift;repeat(42){m.advance(10f);assertTrue(m.bokehLift<=lift);lift=m.bokehLift};assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test(expected=IllegalStateException::class) fun passWithoutRealBokehStartRejected(){DevelopMotion().bokehPassCompleted()}
    @Test fun realMultiscaleCallbacksArriveBeforeHighlightAndFinalOutput(){val im=Planes(65,43).apply{for(ch in 0..2){java.util.Arrays.fill(plane(ch),30.toByte());for(y in 19..23)for(x in 29..33)plane(ch)[y*65+x]=240.toByte()}};val coc=LegendaryMath.Frame(im.width,im.height,1,FloatArray(im.size){16f});val events=mutableListOf<String>();var calls=0
        LegendaryMath.renderCoc(im,coc,2.8f,Cancel(),globalScales=floatArrayOf(1f,2f,4f),highlightRenderer={a,_,_,_->events.add("highlight");LegendaryMath.Frame(a.w,a.h,3,FloatArray(a.data.size))},onPass={a->calls++;events.add("pass$calls");assertEquals(im.width,a.w);assertEquals(im.height,a.h);assertTrue(a.data.all{it.isFinite()})},infer={a,b->events.add("infer");infer(a,b)})
        assertEquals(listOf("infer","pass1","infer","pass2","infer","pass3","highlight"),events)
    }
    @Test fun displayCallbacksLeaveAllProductionPixelsUnchanged(){for(ap in listOf(.95f,2.8f,LegendaryMath.apertures.last())){val im=image();val coc=LegendaryMath.Frame(im.width,im.height,1,FloatArray(im.size){(it%17-8)*2f});var n=0
        fun render(cb:((LegendaryMath.Frame)->Unit)?)=LegendaryMath.renderCoc(im,coc,ap,Cancel(),globalScales=floatArrayOf(1f,2f),onPass=cb,infer=::infer)
        val a=render(null);val b=render{f->n++;val q=LegendaryMath.resize(f,31,19);LegendaryMath.planes(q)}
        assertEquals(2,n);assertArrayEquals(a.p0,b.p0);assertArrayEquals(a.p1,b.p1);assertArrayEquals(a.p2,b.p2)
    }}
    @Test fun cancelledPassStopsBeforeFurtherInferenceOrHighlight(){val im=image();val c=Cancel();var calls=0;var high=false
        try{LegendaryMath.renderCoc(im,LegendaryMath.Frame(im.width,im.height,1,FloatArray(im.size){16f}),2.8f,c,globalScales=floatArrayOf(1f,2f),highlightRenderer={a,_,_,_->high=true;a},onPass={calls++;c.cancelled=true},infer=::infer);fail()}catch(_:CancellationException){}
        assertEquals(1,calls);assertFalse(high)
    }
    @Test fun zeroCocNeverInventsModelPass(){val im=image();val out=LegendaryMath.renderCoc(im,LegendaryMath.Frame(im.width,im.height,1,FloatArray(im.size)),2.8f,Cancel(),onPass={fail("no inference")},infer={_,_->error("no inference")});assertSame(im,out)}
    @Test fun committedCropExportIsExactExistingPixelSubset(){val w=79;val h=53;val pixels=IntArray(w*h){i->((i*23%256) shl 24) or ((i*83%256) shl 16) or ((i*11%256) shl 8) or (i*37%256)}
        val chosen=EditorCrop.combine(crop,Crop(.11,.19,.92,.91),w,h);val plan=EditorCrop.plan(w,h,chosen,BokehOutput.ORIGINAL);val box=CompositionGeometry.pixels(plan.crop,w,h)
        val bytes=ByteArrayOutputStream();CompositionExport.writeSized(bytes,w,h,plan.crop,plan.width,plan.height,Cancel()){b->IntArray(b.w*b.h){i->pixels[(b.top+i/b.w)*w+b.left+i%b.w]}}
        val imageIO=Class.forName("javax.imageio.ImageIO");val img=imageIO.getMethod("read",java.io.InputStream::class.java).invoke(null,ByteArrayInputStream(bytes.toByteArray()))
        val getRGB=img.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        assertEquals(box.w,plan.width);assertEquals(box.h,plan.height)
        for(y in 0 until box.h)for(x in 0 until box.w)assertEquals(pixels[(box.top+y)*w+box.left+x],getRGB.invoke(img,x,y))
    }
}
