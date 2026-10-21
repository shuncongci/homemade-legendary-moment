package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SubjectProtectionTest {
    private val p=LegendaryMath.Point(.3f,.4f)
    private fun mask(w:Int=23,h:Int=19,v:Float=1f)=LegendaryMath.Frame(w,h,1,FloatArray(w*h){v})
    private fun image(w:Int=23,h:Int=19)=Planes.fromArgb(IntArray(w*h){i->0xff000000.toInt() or ((i*731)%0xffffff)},w,h)
    @Test fun disabledNeverTouchesComputeEvenWithCancelledToken(){val c=Cancel().apply{cancelled=true};assertNull(SubjectMaskCache().get(false,"a",p,c){error("must not decode/load/infer")})}
    @Test fun disabledNeverReturnsExistingMask(){val cache=SubjectMaskCache();cache.get(true,"a",p,Cancel()){mask()};assertNull(cache.get(false,"a",p,Cancel()){error("disabled")})}
    @Test fun repeatedClickReusesOnlySamePhotoAndExactPoint(){val c=SubjectMaskCache();var n=0;fun get(s:String,q:LegendaryMath.Point)=c.get(true,s,q,Cancel()){n++;mask()}
        val a=get("a",p);assertSame(a,get("a",p));get("b",p);get("b",LegendaryMath.Point(.31f,.4f));assertEquals(3,n)}
    @Test fun clearForNewPhotoDropsMask(){val c=SubjectMaskCache();val a=c.get(true,"a",p,Cancel()){mask()};c.clear();assertNotSame(a,c.get(true,"a",p,Cancel()){mask()})}
    @Test fun cancelDuringComputeDoesNotPublish(){val cache=SubjectMaskCache();val c=Cancel();try{cache.get(true,"a",p,c){c.cancelled=true;mask()};fail()}catch(_:CancellationException){};var n=0;cache.get(true,"a",p,Cancel()){n++;mask()};assertEquals(1,n)}
    @Test fun failedComputeDoesNotPublish(){val cache=SubjectMaskCache();try{cache.get(true,"a",p,Cancel()){error("native failure")};fail()}catch(_:IllegalStateException){};var n=0;cache.get(true,"a",p,Cancel()){n++;mask()};assertEquals(1,n)}
    @Test(expected=IllegalArgumentException::class) fun invalidMaskRejected(){SubjectMaskCache().get(true,"a",p,Cancel()){mask(v=Float.NaN)}}
    @Test(expected=IllegalArgumentException::class) fun emptyMaskRejected(){SubjectMaskCache().get(true,"a",p,Cancel()){mask(v=0f)}}
    @Test fun defaultOffAndNoFalseReady(){val f=PhotoFlow();assertFalse(f.subjectEnabled);assertFalse(f.subjectReady);f.toggleSubject();assertTrue(f.subjectEnabled);assertFalse(f.subjectReady)}
    private fun ready():PhotoFlow=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);toggleSubject();picked(p);chooseSubjects();addSubjectPoint(p);begin(PhotoFlow.Operation.FOCUS_PREVIEW);subjectCompleted();finish()}
    @Test fun newPhotoInvalidatesCompletion(){val f=ready();f.photoLoaded();assertFalse(f.subjectReady);assertNull(f.focus)}
    @Test fun repickInvalidatesCompletion(){val f=ready();f.picked(p);assertFalse(f.subjectReady)}
    @Test fun disableInvalidatesCompletionAndMarksResultStale(){val f=ready();f.generated();f.toggleSubject();assertFalse(f.subjectReady);assertFalse(f.subjectEnabled);assertTrue(f.resultStale)}
    @Test fun changingApertureDoesNotInvalidateSegmentation(){val f=ready();f.aperture(.95f);assertTrue(f.subjectReady)}
    @Test(expected=IllegalStateException::class) fun cancelledCannotClaimCompletion(){val f=ready();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.subjectInvalidated();f.cancel();f.subjectCompleted()}
    @Test(expected=IllegalStateException::class) fun cannotToggleWhileNativeBusy(){val f=ready();f.begin(PhotoFlow.Operation.GENERATE);f.toggleSubject()}
    @Test fun knownTrimapRegionsAreExactIncludingBorders(){assertTrue(SubjectProtectionMath.trimap(mask(),Cancel()).all{it==1f});assertTrue(SubjectProtectionMath.trimap(mask(v=0f),Cancel()).all{it==0f})}
    @Test fun trimapHasUnknownBand(){val m=mask(31,31,0f);for(y in 8..22)for(x in 8..22)m.data[y*31+x]=1f;val t=SubjectProtectionMath.trimap(m,Cancel());assertEquals(1f,t[15*31+15],0f);assertEquals(0f,t[0],0f);assertEquals(128f/255f,t[8*31+15],0f)}
    @Test fun mattePaddingIsNormalizedZeroNotReplicated(){val rgb=LegendaryMath.rgb(image());val input=SubjectProtectionMath.matteInput(rgb,FloatArray(rgb.size){1f},Cancel());assertEquals(32,input.w);assertEquals(32,input.h);for(c in 0..3){assertEquals(0f,input.data[c*input.size+31*32+31],0f)};assertEquals(1f,input.data[3*input.size],0f)}
    @Test fun matteUsesCheckpointHalfMeanHalfStdNotSamImageNet(){val rgb=LegendaryMath.Frame(1,1,3,floatArrayOf(0f,.5f,1f));val a=SubjectProtectionMath.matteInput(rgb,floatArrayOf(1f),Cancel());assertEquals(-1f,a.data[0],0f);assertEquals(0f,a.data[a.size],0f);assertEquals(1f,a.data[2*a.size],0f)}
    @Test fun outputCropDoesNotStretch(){val a=LegendaryMath.Frame(32,32,1,FloatArray(1024){it/1024f});val r=SubjectProtectionMath.unpad(a,23,19,Cancel());assertEquals(a.data[18*32+22],r.data.last(),0f)}
    @Test fun maskSetsBothPositiveAndNegativeCocToZero(){val coc=LegendaryMath.Frame(23,19,1,FloatArray(23*19){if(it%2==0)30f else -20f});val r=SubjectProtectionMath.protectedCoc(coc,mask(),Cancel());assertTrue(r.data.all{it==0f})}
    @Test fun zeroMaskLeavesEveryCocUnchanged(){val a=LegendaryMath.Frame(23,19,1,FloatArray(23*19){it-200f});assertArrayEquals(a.data,SubjectProtectionMath.protectedCoc(a,mask(v=0f),Cancel()).data,0f)}
    @Test fun opaqueSubjectIsByteExactAfterBackgroundSpill(){val sharp=image();val blurred=Planes(23,19);val out=SubjectProtectionMath.composite(sharp,blurred,mask(),Cancel());for(c in 0..2)assertArrayEquals(sharp.plane(c),out.plane(c))}
    @Test fun transparentMaskDoesNotChangeBackground(){val sharp=image();val blurred=Planes(23,19);val out=SubjectProtectionMath.composite(sharp,blurred,mask(v=0f),Cancel());for(c in 0..2)assertArrayEquals(blurred.plane(c),out.plane(c))}
    @Test fun boundaryUsesAlphaRatherThanHardCut(){val sharp=Planes(23,19,ByteArray(437){100},ByteArray(437){100},ByteArray(437){100});val out=SubjectProtectionMath.composite(sharp,Planes(23,19),mask(v=.5f),Cancel());assertTrue(out.p0.all{it.toInt()==50})}
    @Test fun entireOpaqueSubjectSkipsBokehAndHighlightCalls(){val im=image();val d=mask(v=.9f);val out=LegendaryMath.render(im,d,.1f,.95f,Cancel(),subjectMask=mask(),highlightRenderer={_,_,_,_->error("no highlight")},infer={_,_->error("no bokeh")});for(c in 0..2)assertArrayEquals(im.plane(c),out.plane(c))}
    @Test fun realRenderProtectsForegroundEvenWhenDepthSaysFar(){val im=image(71,53);val a=mask(71,53,0f);for(y in 11..40)for(x in 15..50)a.data[y*71+x]=1f;val d=mask(71,53,.9f);var called=0
        val out=LegendaryMath.render(im,d,.1f,.95f,Cancel(),subjectMask=a,highlightRenderer={h,_,_,_->LegendaryMath.Frame(h.w,h.h,3,FloatArray(h.data.size){.4f})},infer={rgb,_->called++;LegendaryMath.Frame(rgb.w,rgb.h,3,FloatArray(rgb.data.size))})
        assertTrue(called>0);for(c in 0..2)for(i in a.data.indices)if(a.data[i]==1f)assertEquals(im.plane(c)[i],out.plane(c)[i])
    }
    @Test fun disabledRenderIsPixelIdenticalToExistingPath(){val im=image(65,43);val d=mask(65,43,.8f);fun render(explicit:Boolean)=if(explicit)LegendaryMath.render(im,d,.2f,2.8f,Cancel(),subjectMask=null,infer={a,_->a})else LegendaryMath.render(im,d,.2f,2.8f,Cancel(),infer={a,_->a})
        val a=render(true);val b=render(false);for(c in 0..2)assertArrayEquals(a.plane(c),b.plane(c))}
    @Test fun analysisSizeIsFixedNotOutputDependent(){assertEquals(1280 to 1708,SubjectProtectionMath.analysisSize(1280,1708));assertEquals(800 to 534,SubjectProtectionMath.analysisSize(800,534));assertEquals(2048 to 1536,SubjectProtectionMath.analysisSize(4000,3000));assertEquals(1535 to 2048,SubjectProtectionMath.analysisSize(3000,4002))}
    @Test fun blueProtectionOverlayDoesNotAlterMask(){val a=mask(v=.5f);val before=a.data.copyOf();val pixels=SubjectProtectionMath.overlay(a,23,19,Cancel());assertEquals(56,pixels[0] ushr 24);assertEquals(0x38c8f4,pixels[0] and 0xffffff);assertArrayEquals(before,a.data,0f)}
}
