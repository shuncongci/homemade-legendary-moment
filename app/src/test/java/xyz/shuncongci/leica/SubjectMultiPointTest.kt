package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

class SubjectMultiPointTest {
    private val focus=Point(.5f,.5f);private val a=Point(.2f,.3f);private val b=Point(.8f,.6f)
    private fun flow()=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);picked(this@SubjectMultiPointTest.focus);toggleSubject();chooseSubjects()}
    private fun ready()=flow().apply{addSubjectPoint(a);addSubjectPoint(b);begin(PhotoFlow.Operation.FOCUS_PREVIEW);subjectCompleted();finish()}
    private fun mask(p:Point,w:Int=31,h:Int=23)=Frame(w,h,1,FloatArray(w*h){i->val k=SubjectSelectionMath.index(p,w,h);if(kotlin.math.abs(i%w-k%w)<=3 && kotlin.math.abs(i/w-k/w)<=3)1f else 0f})
    private fun all()=Frame(31,23,1,FloatArray(713){1f})
    private fun union(points:List<Point>)=SubjectSelectionMath.union(points,31,23,Cancel()){p,_,_->mask(p)}
    @Test fun focusRemainsSingleWhileManySubjectsAccumulate(){val f=flow();f.addSubjectPoint(a);f.addSubjectPoint(b);assertEquals(focus,f.focus);assertEquals(listOf(a,b),f.subjectPoints);assertTrue(f.subjectPicking)}
    @Test fun focusIsNeverAnAutomaticSubjectSeed(){val f=flow();assertTrue(f.subjectPoints.isEmpty());f.chooseFocus();f.picked(a);assertEquals(a,f.focus);assertTrue(f.subjectPoints.isEmpty())}
    @Test fun mayChooseSubjectsBeforeFocusButCannotComputeYet(){val f=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);toggleSubject();chooseSubjects();addSubjectPoint(a)};assertNull(f.focus);assertFalse(f.canComputeSubjects);f.picked(focus);assertTrue(f.canComputeSubjects)}
    @Test fun collectingPointsDoesNotPretendToCompute(){val f=flow();f.addSubjectPoint(a);f.addSubjectPoint(b);assertFalse(f.busy);assertFalse(f.subjectReady);assertTrue(f.canComputeSubjects)}
    @Test fun enabledDirtySelectionCannotGenerateUnprotectedResult(){val f=flow();f.addSubjectPoint(a);f.endSubjectPicking();assertFalse(f.canGenerate);f.toggleSubject();assertTrue(f.canGenerate)}
    @Test fun wholeBatchCompletionEnablesGeneration(){val f=ready();assertTrue(f.subjectReady);assertTrue(f.canGenerate);assertFalse(f.subjectPicking)}
    @Test fun offOnRetainsWholeBatch(){val f=ready();repeat(5){f.toggleSubject();assertFalse(f.subjectReady);f.toggleSubject();assertTrue(f.subjectReady)};assertEquals(listOf(a,b),f.subjectPoints)}
    @Test fun addInvalidatesButNeverReplacesEarlierPoints(){val f=ready();f.chooseSubjects();f.addSubjectPoint(Point(.7f,.7f));assertFalse(f.subjectReady);assertEquals(listOf(a,b),f.subjectPoints.take(2));assertEquals(3,f.subjectPoints.size)}
    @Test fun undoRemovesOnlyLastAndNotFocus(){val f=ready();f.undoSubjectPoint();assertEquals(listOf(a),f.subjectPoints);assertEquals(focus,f.focus);assertTrue(f.subjectReady)}
    @Test fun clearSubjectsKeepsOneFocus(){val f=ready();f.clearSubjectPoints();assertTrue(f.subjectPoints.isEmpty());assertEquals(focus,f.focus);assertFalse(f.subjectReady);assertFalse(f.canComputeSubjects)}
    @Test fun newPhotoClearsAllPointsAndFocus(){val f=ready();f.photoLoaded();assertTrue(f.subjectPoints.isEmpty());assertNull(f.focus);assertFalse(f.subjectReady)}
    @Test fun repickFocusPreservesSelectionsButInvalidatesCompletion(){val f=ready();f.picked(focus);assertEquals(listOf(a,b),f.subjectPoints);assertFalse(f.subjectReady)}
    @Test fun exactDuplicateDoesNotInvalidateOrDuplicateReadyBatch(){val f=ready();f.chooseSubjects();assertFalse(f.addSubjectPoint(a));assertTrue(f.subjectReady);assertEquals(2,f.subjectPoints.size)}
    @Test fun pickingModesAreMutuallyExclusive(){val f=flow();f.chooseFocus();assertTrue(f.picking);assertFalse(f.subjectPicking);f.chooseSubjects();assertTrue(f.subjectPicking);assertFalse(f.picking)}
    @Test(expected=IllegalStateException::class) fun cannotAppendWhileNativeBusy(){val f=ready();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.addSubjectPoint(a)}
    @Test(expected=IllegalStateException::class) fun noFalseCompletionWithoutAnySubject(){val f=flow();f.subjectCompleted()}
    @Test fun cacheKeyCopiesCanonicalWholeSet(){val source=mutableListOf(b,a,a);val key=SubjectSelectionMath.key(source);source.clear();assertEquals(listOf(a,b),key)}
    @Test fun cacheReusesReorderedGroupAndDisabledDoesNotEvict(){val c=SubjectMaskCache();val m=c.get(true,"photo",listOf(a,b),Cancel()){all()};assertNull(c.get(false,"other",emptyList(),Cancel()){error("disabled")});assertSame(m,c.get(true,"photo",listOf(b,a,a),Cancel()){error("must reuse")})}
    @Test fun changingAnyPointRecomputesWholeGroup(){val c=SubjectMaskCache();var calls=0;for(points in listOf(listOf(a),listOf(a,b),listOf(b)))c.get(true,"p",points,Cancel()){calls++;all()};assertEquals(3,calls)}
    @Test(expected=IllegalArgumentException::class) fun enabledEmptyGroupIsRejected(){SubjectMaskCache().get(true,"p",emptyList(),Cancel()){all()}}
    @Test fun unionKeepsBothDisjointObjects(){val x=union(listOf(a,b));assertTrue(x.data.indices.all{x.data[it]==kotlin.math.max(mask(a).data[it],mask(b).data[it])});assertTrue(x.data.sum()>mask(a).data.sum());SubjectSelectionMath.requireCovered(x,listOf(a,b))}
    @Test fun unionIsOrderIndependentAndOverlapDoesNotAddAlpha(){assertArrayEquals(union(listOf(a,b)).data,union(listOf(b,a,a)).data,0f);assertTrue(union(listOf(a,a)).data.all{it in 0f..1f})}
    @Test fun singlePointAndSourceMaskAreUnchanged(){val source=mask(a);val before=source.data.copyOf();val x=SubjectSelectionMath.union(listOf(a),31,23,Cancel()){_,_,_->source};assertArrayEquals(before,x.data,0f);assertArrayEquals(before,source.data,0f)}
    @Test fun cancelledBatchNeverPublishesCache(){val c=SubjectMaskCache();val token=Cancel();try{c.get(true,"p",listOf(a,b),token){SubjectSelectionMath.union(listOf(a,b),31,23,token){p,i,_->if(i==1)token.cancelled=true;mask(p)}};fail()}catch(_:CancellationException){};assertFalse(c.contains("p",listOf(a,b)))}
    @Test fun secondPointFailureNeverCachesFirstPointOnly(){val c=SubjectMaskCache();try{c.get(true,"p",listOf(a,b),Cancel()){SubjectSelectionMath.union(listOf(a,b),31,23,Cancel()){p,i,_->if(i==1)error("native failure");mask(p)}};fail()}catch(_:IllegalStateException){};assertFalse(c.contains("p",listOf(a,b)))}
    @Test(expected=IllegalArgumentException::class) fun unionRejectsMaskThatMissesSelectedPoint(){SubjectSelectionMath.union(listOf(a,b),31,23,Cancel()){_,_,_->mask(a)}}
    @Test fun selectionUsesHighestScoringCandidateContainingPoint(){val logits=FloatArray(3*256*256){if(it in 65536 until 131072)1f else -1f};val out=SubjectSelectionMath.select(logits,floatArrayOf(.99f,.8f,.1f),a,31,23,Cancel());assertTrue(out.data.all{it==1f})}
    @Test(expected=IllegalArgumentException::class) fun selectionRejectsAllCandidatesMissingPoint(){SubjectSelectionMath.select(FloatArray(3*256*256){-1f},floatArrayOf(.9f,.8f,.7f),a,31,23,Cancel())}
    @Test fun finishEnforcesKnownRegionsAndChecksEveryPoint(){val out=SubjectSelectionMath.finish(Frame(32,32,1,FloatArray(1024){.6f}),31,23,FloatArray(713){if(it==0)0f else 1f},listOf(a,b),Cancel());assertEquals(0f,out.data[0],0f);SubjectSelectionMath.requireCovered(out,listOf(a,b))}
    @Test(expected=IllegalArgumentException::class) fun refinementCannotPublishWithOneMissingTarget(){SubjectSelectionMath.finish(Frame(32,32,1,FloatArray(1024)),31,23,mask(a).data,listOf(a,b),Cancel())}
    @Test fun finalRenderProtectsBothObjectsWithoutForegroundLightByteExactly(){val u=union(listOf(a,b));val p=Planes(31,23,ByteArray(713){245.toByte()},ByteArray(713){180.toByte()},ByteArray(713){70});val out=LegendaryMath.render(p,Frame(31,23,1,FloatArray(713){.1f}),.9f,.95f,Cancel(),subjectMask=u){rgb,_->rgb};for(c in 0..2)for(i in u.data.indices)if(u.data[i]==1f)assertEquals(p.plane(c)[i],out.plane(c)[i])}
}
