package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class SubjectMotionTest {
    private fun frame(w:Int=48,h:Int=32,f:(Int)->Float)=LegendaryMath.Frame(w,h,1,FloatArray(w*h,f))
    private val point=LegendaryMath.Point(.4f,.6f)
    private fun ready()=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);picked(point);toggleSubject();chooseSubjects();addSubjectPoint(point);endSubjectPicking();subjectCompleted()}
    @Test fun offOnRestoresCompletedFlagWithoutRecompute(){val f=ready();repeat(20){f.toggleSubject();assertFalse(f.subjectReady);f.toggleSubject();assertTrue(f.subjectReady)}}
    @Test fun disabledThenEnabledUsesIdenticalCachedObject(){val cache=SubjectMaskCache();var calls=0;val a=cache.get(true,"photo",point,Cancel()){calls++;frame{1f}}
        repeat(10){assertNull(cache.get(false,"photo",point,Cancel()){error("disabled")});assertSame(a,cache.get(true,"photo",point,Cancel()){calls++;frame{1f}})};assertEquals(1,calls)}
    @Test fun disabledWithOtherKeyDoesNotEvictRetainedResult(){val c=SubjectMaskCache();c.get(true,"a",point,Cancel()){frame{1f}};assertNull(c.get(false,"b",point,Cancel()){error("disabled")});assertTrue(c.contains("a",point))}
    @Test fun explicitRepickSameCoordinatesInvalidatesUi(){val f=ready();f.toggleSubject();f.picked(point);f.toggleSubject();assertFalse(f.subjectReady)}
    @Test fun explicitRepickClearedCacheRecomputesEvenAtSameCoordinates(){val c=SubjectMaskCache();var calls=0;repeat(2){c.get(true,"a",point,Cancel()){calls++;frame{1f}};c.clear()};assertEquals(2,calls)}
    @Test fun apertureOutputAndResultDoNotEvictCompletion(){val f=ready();f.aperture(.95f);f.outputChanged();f.generated();f.toggleSubject();f.toggleSubject();assertTrue(f.subjectReady);assertTrue(f.resultStale)}
    @Test fun photoSwitchWhileDisabledDoesEvictCompletion(){val f=ready();f.toggleSubject();f.photoLoaded();f.toggleSubject();assertFalse(f.subjectReady)}
    @Test fun modelFailureIsNotMarkedCached(){val c=SubjectMaskCache();try{c.get(true,"p",point,Cancel()){error("model")}}catch(_:IllegalStateException){};assertFalse(c.contains("p",point))}
    @Test fun cancelledCachedRequestDoesNotRunOrEraseCache(){val c=SubjectMaskCache();c.get(true,"p",point,Cancel()){frame{1f}};try{c.get(true,"p",point,Cancel().apply{cancelled=true}){error("compute")};fail()}catch(_:CancellationException){};assertTrue(c.contains("p",point))}
    @Test fun foregroundAtEndExactlyOnFocusPlane(){for(d in 0..100)for(f in 0..100)assertEquals(SubjectDepthMotion.z(f/100f),SubjectDepthMotion.movedZ(d/100f,f/100f,1f),1e-7f)}
    @Test fun startRetainsSourceDepth(){for(d in 0..100)assertEquals(SubjectDepthMotion.z(d/100f),SubjectDepthMotion.movedZ(d/100f,.72f,0f),0f)}
    @Test fun motionMonotonicallyApproachesFocusWithoutOvershoot(){for(d in floatArrayOf(.1f,.9f)){var previous=1f;for(i in 0..120){val delta=abs(SubjectDepthMotion.movedZ(d,.45f,i/120f)-SubjectDepthMotion.z(.45f));assertTrue(delta<=previous+1e-7f);previous=delta}}}
    @Test fun outOfRangeProgressClamped(){assertEquals(SubjectDepthMotion.z(.1f),SubjectDepthMotion.movedZ(.1f,.8f,-2f),0f);assertEquals(SubjectDepthMotion.z(.8f),SubjectDepthMotion.movedZ(.1f,.8f,2f),1e-7f)}
    @Test fun foregroundAndBackgroundWeightedSeparately(){val d=frame{if(it%48%2==0).2f else .8f};val a=frame{if(it%48%2==0)1f else 0f};val v=SubjectDepthMotion.create(d,a,.6f,Cancel());assertTrue(v.foreground.depth.all{abs(it-.2f)<1e-6f});assertTrue(v.background.depth.all{abs(it-.8f)<1e-6f});assertTrue(v.coverage.all{it==.5f})}
    @Test fun previewDoesNotMutateDepthOrMask(){val d=frame{it/1536f};val a=frame{if(it%3==0)1f else .2f};val before=d.data.copyOf();val mask=a.data.copyOf();SubjectDepthMotion.create(d,a,.45f,Cancel());assertArrayEquals(before,d.data,0f);assertArrayEquals(mask,a.data,0f)}
    @Test fun zeroAndOneAlphaStayFinite(){for(a in listOf(0f,1f)){val v=SubjectDepthMotion.create(frame{.3f},frame{a},.6f,Cancel());assertTrue(v.foreground.depth.all{it.isFinite()});assertTrue(v.background.depth.all{it.isFinite()})}}
    @Test fun tinyAndPortraitInputsAreSafe(){for((w,h)in listOf(1 to 1,1 to 32,32 to 1,27 to 49)){val v=SubjectDepthMotion.create(frame(w,h){.4f},frame(w,h){1f},.5f,Cancel());assertEquals(v.coverage.size,v.foreground.cols*v.foreground.rows);assertTrue(v.foreground.depth.all{abs(it-.4f)<1e-6})}}
    @Test fun layerAlphaIsComplementaryAtEveryPixel(){val w=48;val h=32;val pixels=IntArray(w*h){0xffabcdef.toInt()};val a=frame{it/1535f};val fg=SubjectDepthMotion.layerPixels(pixels,w,h,a,true);val bg=SubjectDepthMotion.layerPixels(pixels,w,h,a,false);for(i in pixels.indices){assertEquals(255,(fg[i] ushr 24)+(bg[i] ushr 24));assertEquals(0xabcdef,fg[i] and 0xffffff)}}
    @Test fun opaqueSubjectLeavesNoOldOpaqueCopy(){val pixels=IntArray(1536){-1};val a=frame{1f};assertTrue(SubjectDepthMotion.layerPixels(pixels,48,32,a,false).all{it ushr 24==0});assertTrue(SubjectDepthMotion.layerPixels(pixels,48,32,a,true).all{it ushr 24==255})}
    @Test fun transparentSubjectLeavesBackgroundUnchanged(){val pixels=IntArray(1536){0xff88aa33.toInt()};val a=frame{0f};assertArrayEquals(pixels,SubjectDepthMotion.layerPixels(pixels,48,32,a,false))}
    @Test fun projectedSubjectAgreesWithFocusPlaneAtEveryDragAngle(){val out=FloatArray(3);val plane=FloatArray(3);for(yaw in -8..8)for(pitch in -5..5){DepthVisualMath.project(.2f,-.3f,SubjectDepthMotion.movedZ(.1f,.7f,1f),yaw/10f,pitch/10f,out);DepthVisualMath.project(.2f,-.3f,SubjectDepthMotion.z(.7f),yaw/10f,pitch/10f,plane);assertArrayEquals(plane,out,1e-7f)}}
    @Test fun cancelledPresentationDataNeverReturned(){try{SubjectDepthMotion.create(frame{.3f},frame{1f},.2f,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
}
