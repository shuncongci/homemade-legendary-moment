package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

class BokehProgressTest {
    private fun <T> cache(block:(File)->T):T {val d=Files.createTempDirectory("region-progress-").toFile();return try{block(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun photo(w:Int=113,h:Int=83)=Planes(w,h).also{p->for(c in 0..2)for(i in 0 until p.size)p.plane(c)[i]=(if(i%67==0)255 else 20+(i*13+c*23)%150).toByte()}
    private fun depth()=Frame(17,13,1,FloatArray(221){i->(.5+.4*sin(i*.1)).toFloat()})
    private fun patch(s:BokehRegionReveal,i:Int)=BokehPreview.patch(photo(s.width,s.height),s.cores[i-1],i,s.cores.size,Cancel())
    private fun eq(a:Planes,b:Planes){assertEquals(a.width,b.width);assertEquals(a.height,b.height);for(c in 0..2)for(i in 0 until a.size)assertTrue(abs((a.plane(c)[i].toInt()and 255)-(b.plane(c)[i].toInt()and 255))<=1)}
    @Test fun explicitOriginalCapIs1250w(){assertEquals(12_500_000L,BokehRenderPolicy.MAX_PIXELS);assertEquals(5000 to 2500,BokehRenderPolicy.originalSize(5000,2500))}
    @Test fun originalLargePhotoIsReducedProportionally(){assertEquals(4082 to 3061,LeicaSizes.processingSize(16384,12288,true,BokehOutput.ORIGINAL))}
    @Test fun exactPreviouslySuccessfulPhotoIsNotReduced(){assertEquals(2852 to 4380,BokehRenderPolicy.originalSize(2852,4380))}
    @Test fun oddAndExifSwappedSizesAreBounded(){for((w,h) in listOf(10003 to 10007,8000 to 6000,6000 to 4000,5001 to 2500)){
        val (x,y)=BokehRenderPolicy.originalSize(w,h);assertTrue(x.toLong()*y<=12_500_000);assertEquals(y to x,BokehRenderPolicy.originalSize(h,w));assertTrue(abs(x.toDouble()/y-w.toDouble()/h)/(w.toDouble()/h)<.001)
    }}
    @Test fun tinySourcesAreNeverEnlarged(){for((w,h) in listOf(1 to 1,1 to 77,113 to 83,1000 to 900))assertEquals(w to h,BokehRenderPolicy.originalSize(w,h))}
    @Test fun extremeAxesCannotOverflow(){for((w,h) in listOf(Int.MAX_VALUE to 1,1 to Int.MAX_VALUE,Int.MAX_VALUE to Int.MAX_VALUE,2 to Int.MAX_VALUE)){val (x,y)=BokehRenderPolicy.originalSize(w,h);assertTrue(x in 1..w && y in 1..h);assertTrue(x.toLong()*y<=12_500_000)}}
    @Test(expected=IllegalArgumentException::class) fun invalidSizeRejected(){BokehRenderPolicy.originalSize(0,3)}
    @Test fun onlyOriginalOptionSelectsFourParts(){for(o in BokehOutput.entries)assertEquals(o==BokehOutput.ORIGINAL,BokehRenderPolicy.split(o))}
    @Test fun smallOptionsKeepTheirSizes(){assertEquals(2048 to 1536,LeicaSizes.processingSize(8000,6000,true,BokehOutput.SIZE_2048));assertEquals(1024 to 768,LeicaSizes.processingSize(8000,6000,true,BokehOutput.SIZE_1024))}
    @Test fun smallOptionHasOneActualCore(){assertEquals(listOf(BokehBox(0,0,2048,1536)),BokehRenderPolicy.parts(2048,1536,false))}
    @Test fun originalOptionHasFourCores(){assertEquals(4,BokehRenderPolicy.parts(2852,4380,true).size)}
    @Test fun colorOnlyPolicyRemains50Mp(){for(o in BokehOutput.entries)assertEquals(8000 to 6000,LeicaSizes.processingSize(8000,6000,false,o))}
    @Test fun selectedLabelDisclosesOriginalBudget(){assertTrue(BokehOutput.ORIGINAL.label.contains("1250万"))}
    @Test fun croppedRenderCannotBypassFullSceneCap(){val p=EditorCrop.renderPlan(16384,12288,CompositionGeometry.Crop(.2,.2,.8,.8),BokehOutput.ORIGINAL,true);assertTrue(p.workWidth.toLong()*p.workHeight<=12_500_000);val b=CompositionGeometry.pixels(p.crop,p.workWidth,p.workHeight);assertTrue(p.width<=b.w && p.height<=b.h)}
    @Test fun unrelatedColorCropKeeps50MpBudget(){assertEquals(8164,EditorCrop.renderPlan(16384,12288,CompositionGeometry.FULL,BokehOutput.ORIGINAL,false).workWidth)}
    @Test fun everyPartNumberSurvivesUiShortening(){for(i in 1..4)for(phase in listOf("虚化","SDR高光","HDR增光","HDR减光","HDR合成","完成")){val s="$i/4 · $phase";assertEquals(s,EditorPresentation.stage("6/7 $s"));assertTrue(s.length<=16)}}
    @Test fun actualDepthLayerCountSurvives(){assertEquals("2/4 · 虚化 · 32/64层",EditorPresentation.stage("6/7 2/4 · 虚化 · 32/64层"))}
    @Test fun depthProgressDoesNotResetPhaseClock(){assertEquals(BokehProgress.key("6/7 2/4 · 虚化"),BokehProgress.key("6/7 2/4 · 虚化 · 48/64层"))}
    @Test fun changingPartOrPhaseChangesClockIdentity(){assertNotEquals(BokehProgress.key("1/4 · SDR高光"),BokehProgress.key("2/4 · SDR高光"));assertNotEquals(BokehProgress.key("2/4 · SDR高光"),BokehProgress.key("2/4 · HDR增光"))}
    @Test fun progressBarRepresentsActualLocalLayersOnly(){assertEquals(32 to 64,BokehProgress.bar("6/7 2/4 · 虚化 · 32/64层"));assertNull(BokehProgress.bar("6/7 2/4 · HDR增光"))}
    @Test fun completedPartsBarIsNotInferredFromTime(){assertEquals(3 to 4,BokehProgress.bar("6/7 3/4 · 完成"));assertNull(BokehProgress.bar("准备 · 光学参数"))}
    @Test fun singleRegionDoesNotPretendToHaveFourParts(){assertEquals("SDR高光",BokehProgress.label(1,1,"SDR高光"));assertEquals("虚化 · 64/64层",EditorPresentation.stage("6/7 虚化 · 64/64层"))}
    @Test fun preparationAndFinalCompositionKeepShortDetails(){assertEquals("准备 · 光学参数",EditorPresentation.stage("6/7 准备 · 光学参数"));assertEquals("合成 · 主体边缘",EditorPresentation.stage("6/7 合成 · 主体边缘"))}
    @Test fun previewGridCoversOddAndThinPhotosExactly(){for((w,h) in listOf(157 to 113,2852 to 4380,1 to 19,17 to 1,1 to 1)){val s=BokehRegionReveal(w,h,true);val (pw,ph)=s.previewSize;val count=IntArray(pw*ph);for(r in s.rects)for(y in r.y until r.y+r.h)for(x in r.x until r.x+r.w)count[y*pw+x]++;assertTrue(count.all{it==1})}}
    @Test fun queuedPatchAreaIsOnePreviewNotFourPhotos(){val s=BokehRegionReveal(5000,2500,true);assertTrue(s.previewSize.first<=640 && s.previewSize.second<=640);assertEquals(s.previewSize.first*s.previewSize.second,s.rects.sumOf{it.w*it.h});assertTrue(s.rects.sumOf{it.w*it.h}*4<=640*640*4)}
    @Test fun patchReadsOnlyItsWrittenCore(){val p=photo();for((i,b) in BokehBox.four(p.width,p.height).withIndex()){val a=BokehPreview.patch(p,b,i+1,4,Cancel());for(y in 0 until a.rect.h)for(x in 0 until a.rect.w){val gx=(((x+a.rect.x+.5)*p.width/a.canvasW).toInt()).coerceIn(b.left,b.right-1);val gy=(((y+a.rect.y+.5)*p.height/a.canvasH).toInt()).coerceIn(b.top,b.bottom-1);assertEquals(p.p0[gy*p.width+gx].toInt()and 255,(a.pixels[y*a.rect.w+x] ushr 16)and 255)}}}
    @Test fun pendingPreviewCannotBecomeDoneByWaiting(){val s=BokehRegionReveal(113,83,true);s.advance(1e8f);assertEquals(0,s.done);for(i in 0..3){assertEquals(.52f,s.dark(i),0f);assertEquals(0f,s.glow(i),0f)}}
    @Test fun completedCoreBrightensButOthersStayDark(){val s=BokehRegionReveal(113,83,true);assertTrue(s.accept(patch(s,1)));assertEquals(0f,s.dark(0),0f);assertTrue(s.glow(0) in .01f.. .08f);assertEquals(.52f,s.dark(1),0f)}
    @Test fun smallPulseSettlesWithoutFakeCompletion(){val s=BokehRegionReveal(113,83,true);s.accept(patch(s,1));val pulse=s.glow(0);s.advance(1000f);assertTrue(s.glow(0)<pulse);assertTrue(s.glow(0)>0);assertEquals(1,s.done)}
    @Test fun reducedMotionKeepsTrueCompletion(){val s=BokehRegionReveal(113,83,true);s.accept(patch(s,1));s.settle();assertEquals(1,s.done);assertEquals(.018f,s.glow(0),0f);assertFalse(s.completed(1))}
    @Test fun duplicateAndOutOfOrderCallbacksAreIgnored(){val s=BokehRegionReveal(113,83,true);assertFalse(s.accept(patch(s,2)));assertTrue(s.accept(patch(s,1)));assertFalse(s.accept(patch(s,1)));assertEquals(1,s.done)}
    @Test fun wrongPatchDimensionsAreRejected(){val s=BokehRegionReveal(113,83,true);val p=patch(s,1);assertFalse(s.accept(p.copy(canvasW=p.canvasW+1)));assertFalse(s.accept(p.copy(pixels=IntArray(0))));assertEquals(0,s.done)}
    @Test fun restartingDoesNotReusePreviousDoneFlags(){val s=BokehRegionReveal(113,83,true);s.accept(patch(s,1));assertEquals(0,BokehRegionReveal(113,83,true).done)}
    @Test fun singleRegionRevealUsesOneRealEvent(){val s=BokehRegionReveal(113,83,false);assertEquals(1,s.cores.size);assertTrue(s.accept(patch(s,1)));assertEquals(1,s.done)}
    @Test fun sameStageUpdatesAccumulateWithoutRoundingReset(){var clock=0L;val s=StageTiming{clock};s.start("虚化");clock=75;s.start("虚化");clock=200;s.finish();assertEquals("虚化 ${ProcessingTime.seconds(200)}",s.summary())}
    @Test fun phaseTimesAreExclusive(){var clock=0L;val s=StageTiming{clock};s.start("虚化");clock=300;s.start("高光");clock=700;s.finish();assertEquals("虚化 ${ProcessingTime.seconds(300)}｜高光 ${ProcessingTime.seconds(400)}",s.summary())}
    @Test fun partSummaryDisclosesNestedTimes(){val r=BokehRunReport(listOf(BokehPartTime(1,4,2000,"虚化 1秒｜高光 1秒")));assertTrue(r.summary().contains("不重复累加"));assertTrue(r.summary().contains("左上"))}
    @Test fun timingDetailsCanBeRestoredVerbatim(){val r=BokehRunReport((1..4).map{BokehPartTime(it,4,it*1000L,"虚化 0.3秒")}).summary();val encoded=java.util.Properties().apply{setProperty("result.timing",r)};val bytes=java.io.ByteArrayOutputStream();encoded.store(bytes,null);val decoded=java.util.Properties().apply{load(bytes.toByteArray().inputStream())};assertEquals(r,decoded.getProperty("result.timing"));assertEquals(4,ProcessTimingText.compact(r).split(" · ").size)}
    @Test fun exportTimeIsSeparateFromRenderTotal(){val s=ProcessTimingText.details(7000,"读取 1秒｜虚化 6秒",2000);assertTrue(s.contains("相册导出"));assertTrue(s.contains("另计"));assertTrue(s.contains("\n虚化"))}
    @Test fun rendererEmitsExactlyFourSequentialPatches(){cache{d->val p=photo();val seen=arrayListOf<Int>();val r=FourTileBokeh.render(p,depth(),.38f,.95f,Cancel(),d,onPreview={a->seen.add(a.index);assertEquals(4,a.total)},onReport={assertEquals(4,it.parts.size)});assertEquals(listOf(1,2,3,4),seen);assertTrue(d.list()!!.isEmpty());assertEquals(p.width,r.width)}}
    @Test fun singleAndFourPathsKeepTheSameSdrResult(){cache{d->val p=photo();val dep=depth();val a=FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d);val seen=arrayListOf<Int>();val b=FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d,split=false,onPreview={seen.add(it.index);assertEquals(1,it.total)});eq(a,b);assertEquals(listOf(1),seen)}}
    @Test fun callbacksDoNotMutateOrReplaceOutput(){cache{d->val p=photo();val dep=depth();val a=FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d);val b=FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d,onPreview={it.pixels.fill(0)});eq(a,b)}}
    @Test fun actualMonotonicPartTimesAndStepsAreReported(){cache{d->var time=0L;var report:BokehRunReport?=null;FourTileBokeh.render(photo(),depth(),.38f,.95f,Cancel(),d,clock={time+=7;time},onReport={report=it});assertEquals(4,report!!.parts.size);for(p in report!!.parts){assertTrue(p.millis>0);assertTrue(p.steps.contains("虚化"));assertFalse(p.steps.contains("SDR高光"))}}}
    @Test fun zeroDefocusAlsoPublishesFourRealKeptCores(){cache{d->val p=photo();var n=0;val a=FourTileBokeh.render(p,Frame(3,3,1,FloatArray(9){.38f}),.38f,.95f,Cancel(),d,onPreview={n++});eq(p,a);assertEquals(4,n)}}
    @Test fun cancellingPreviewCleansScratchAndDoesNotPublishReport(){cache{d->val c=Cancel();var reports=0;var previews=0;try{FourTileBokeh.render(photo(),depth(),.38f,.95f,c,d,onPreview={previews++;c.cancelled=true},onReport={reports++});fail()}catch(_:CancellationException){};assertEquals(1,previews);assertEquals(0,reports);assertTrue(d.list()!!.isEmpty())}}
    @Test fun failingPreviewDoesNotLeavePartialHdr(){cache{d->val p=photo();val f=File(d,"hdr");try{FourTileBokeh.render(p,depth(),.38f,.95f,Cancel(),d,sharp=HdrPixels.of(Frame(p.width,p.height,3,FloatArray(p.size*3){2f})),hdrTarget=f,onPreview={throw IllegalStateException("preview failure")});fail()}catch(_:IllegalStateException){};assertFalse(f.exists());assertTrue(d.list()!!.isEmpty())}}
    @Test fun steadyWaitingDoesNotRequireContinuousPreviewFrames(){val s=BokehRegionReveal(113,83,true);assertFalse(s.needsFrames);s.accept(patch(s,1));assertTrue(s.needsFrames);s.advance(601f);assertFalse(s.needsFrames);assertEquals(1,s.done)}
    @Test fun singleTimingDoesNotCallItABlock(){val s=BokehRunReport(listOf(BokehPartTime(1,1,3000,"虚化 2秒"))).summary();assertTrue(s.contains("整幅"));assertFalse(s.contains("第1块"));assertTrue(ProcessTimingText.compact(s).isEmpty())}
    @Test fun singleRegionHdrMatchesFourAndKeepsValuesAboveOne(){cache{d->val p=photo(89,67);val dep=depth();val sharp=HdrPixels.of(Frame(p.width,p.height,3,FloatArray(p.size*3){i->if(i%2==0)2.8f else .07f}));val a=File(d,"four");val b=File(d,"single");FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d,sharp=sharp,hdrTarget=a);FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d,sharp=sharp,hdrTarget=b,split=false);BokehFloatFile.open(a).use{x->BokehFloatFile.open(b).use{y->val xx=x.read(x.whole,Cancel());val yy=y.read(y.whole,Cancel());assertArrayEquals(xx.data,yy.data,3e-5f);assertTrue(yy.data.max()>1f)}}}}
}
