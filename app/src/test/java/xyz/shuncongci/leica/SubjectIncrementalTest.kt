package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

class SubjectIncrementalTest {
    private val a=Point(.2f,.4f);private val b=Point(.5f,.4f);private val d=Point(.8f,.4f)
    private fun mask(p:Point)=Frame(31,23,1,FloatArray(713){i->val k=SubjectSelectionMath.index(p,31,23);if(kotlin.math.abs(i%31-k%31)<=4 && kotlin.math.abs(i/31-k/31)<=4)1f else 0f})
    private fun flow()=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);picked(Point(.5f,.5f));toggleSubject();chooseSubjects();addSubjectPoint(a);addSubjectPoint(b);endSubjectPicking()}
    private fun ready()=flow().apply{subjectCompleted()}
    private fun resolve(c:SubjectPointCache,ps:List<Point>,called:MutableList<Point> = mutableListOf())=c.resolve(true,"p",ps,Cancel()){missing,commit->missing.forEach{called+=it;commit(it,mask(it))}}
    @Test fun addOnlyRunsNewPoint(){val c=SubjectPointCache();val calls=mutableListOf<Point>();resolve(c,listOf(a,b),calls);resolve(c,listOf(a,b,d),calls);assertEquals(listOf(a,b,d),calls)}
    @Test fun arbitraryRemovalMakesNoModelCalls(){val c=SubjectPointCache();resolve(c,listOf(a,b,d));val x=c.resolve(true,"p",listOf(a,d),Cancel()){_,_->error("no inference")};val ref=SubjectSelectionMath.union(listOf(a,d),31,23,Cancel()){p,_,_->mask(p)};assertArrayEquals(ref.data,x!!.data,0f)}
    @Test fun removalOfOverlappingObjectUsesMaxNotSubtraction(){val c=SubjectPointCache();val near=Point(.3f,.4f);resolve(c,listOf(a,near));val x=c.resolve(true,"p",listOf(near),Cancel()){_,_->error("no inference")};assertArrayEquals(mask(near).data,x!!.data,0f)}
    @Test fun unchangedSubsetHasSameUnionObject(){val c=SubjectPointCache();val x=resolve(c,listOf(a,b));assertSame(x,c.resolve(true,"p",listOf(b,a,a),Cancel()){_,_->error("no inference")})}
    @Test fun disabledDoesNotComputeOrEvict(){val c=SubjectPointCache();resolve(c,listOf(a));assertNull(c.resolve(false,"q",emptyList(),Cancel().apply{cancelled=true}){_,_->error("disabled")});assertTrue(c.contains("p",listOf(a)))}
    @Test fun moveOnlyComputesReplacement(){val c=SubjectPointCache();val calls=mutableListOf<Point>();resolve(c,listOf(a,b),calls);resolve(c,listOf(a,d),calls);assertEquals(listOf(a,b,d),calls)}
    @Test fun cancelledSecondPointKeepsFirstCommittedAlpha(){val c=SubjectPointCache();val token=Cancel();try{c.resolve(true,"p",listOf(a,b),token){_,commit->commit(a,mask(a));token.cancelled=true;commit(b,mask(b))};fail()}catch(_:CancellationException){};assertEquals(listOf(a),c.completed("p",listOf(a,b)));assertFalse(c.contains("p",listOf(a,b)));val calls=mutableListOf<Point>();resolve(c,listOf(a,b),calls);assertEquals(listOf(b),calls)}
    @Test fun failureKeepsEarlierSuccessfulPointsAndRetryOnlyMissing(){val c=SubjectPointCache();try{c.resolve(true,"p",listOf(a,b),Cancel()){_,commit->commit(a,mask(a));error("matte failure")};fail()}catch(_:IllegalStateException){};val calls=mutableListOf<Point>();resolve(c,listOf(a,b),calls);assertEquals(listOf(b),calls)}
    @Test fun invalidPointIsNotCommitted(){val c=SubjectPointCache();try{c.resolve(true,"p",listOf(a,b),Cancel()){_,commit->commit(a,mask(a));commit(b,mask(a))};fail()}catch(_:IllegalArgumentException){};assertEquals(listOf(a),c.completed("p",listOf(a,b)))}
    @Test fun cannotClaimWholeGroupAfterPartialCompute(){val c=SubjectPointCache();try{c.resolve(true,"p",listOf(a,b),Cancel()){_,commit->commit(a,mask(a))};fail()}catch(_:IllegalStateException){};assertFalse(c.contains("p",listOf(a,b)));assertTrue(c.contains("p",listOf(a)))}
    @Test fun invalidNumericAlphaRejected(){val c=SubjectPointCache();try{c.resolve(true,"p",listOf(a),Cancel()){_,commit->commit(a,Frame(1,1,1,floatArrayOf(Float.NaN)))};fail()}catch(_:IllegalArgumentException){};assertFalse(c.contains("p",listOf(a)))}
    @Test fun emptySelectionHasNoCachedUnion(){val c=SubjectPointCache();resolve(c,listOf(a));assertNull(c.cached("p",emptyList(),Cancel()));assertFalse(c.contains("p",emptyList()))}
    @Test fun newPhotoCannotReuseOldPoint(){val c=SubjectPointCache();resolve(c,listOf(a));var calls=0;c.resolve(true,"other",listOf(a),Cancel()){_,commit->calls++;commit(a,mask(a))};assertEquals(1,calls);assertFalse(c.contains("p",listOf(a)))}
    @Test fun explicitClearDropsAllPoints(){val c=SubjectPointCache();resolve(c,listOf(a,b));c.clear();assertTrue(c.completed("p",listOf(a,b)).isEmpty())}
    @Test fun previewCanShowOnlyCompletedSubsetWithoutInferring(){val c=SubjectPointCache();resolve(c,listOf(a));assertArrayEquals(mask(a).data,c.cached("p",listOf(a,b),Cancel())!!.data,0f);assertFalse(c.contains("p",listOf(a,b)))}
    @Test fun noSourceMaskMutationDuringUnion(){val c=SubjectPointCache();val ma=mask(a);val before=ma.data.copyOf();c.resolve(true,"p",listOf(a,b),Cancel()){_,commit->commit(a,ma);commit(b,mask(b))};assertArrayEquals(before,ma.data,0f)}
    @Test fun uiKeepsCompletedWhenAdding(){val f=ready();f.chooseSubjects();f.addSubjectPoint(d);assertEquals(2,f.subjectCompletedCount);assertEquals(1,f.subjectPendingCount);assertFalse(f.subjectReady)}
    @Test fun uiRemovingMiddleIsImmediatelyReady(){val f=ready();f.chooseSubjects();f.addSubjectPoint(d);f.subjectCompleted();f.removeSubjectPoint(b);assertEquals(listOf(a,d),f.subjectPoints);assertTrue(f.subjectReady)}
    @Test fun uiRemovingUncomputedPointRestoresReady(){val f=ready();f.chooseSubjects();f.addSubjectPoint(d);f.removeSubjectPoint(d);assertTrue(f.subjectReady)}
    @Test fun uiMoveKeepsOtherPointsReadyAndFocusFixed(){val f=ready();val focus=f.focus;f.moveSubjectPoint(b,d);assertEquals(listOf(a,d),f.subjectPoints);assertEquals(1,f.subjectCompletedCount);assertEquals(focus,f.focus)}
    @Test fun uiMovingOntoExistingPointDeduplicates(){val f=ready();f.moveSubjectPoint(b,a);assertEquals(listOf(a),f.subjectPoints);assertTrue(f.subjectReady)}
    @Test fun apertureTicketCannotLoseModelCompletion(){val f=flow();val model=f.subjectTicket();val preview=FocusPreviewUpdates();val old=preview.request();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.aperture(.95f);preview.request();assertTrue(f.acceptSubjectCompletion(model,listOf(a,b)));assertFalse(preview.publish(old));assertTrue(f.subjectReady);f.finish();assertTrue(f.canGenerate)}
    @Test fun laterPreviewFailureCannotResetCommittedCompletion(){val f=flow();val model=f.subjectTicket();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.acceptSubjectCompletion(model,listOf(a,b));f.finish();assertTrue(f.subjectReady);assertTrue(f.canGenerate)}
    @Test fun delayedOldSelectionCannotMarkMovedPointReady(){val f=flow();val old=f.subjectTicket();f.moveSubjectPoint(b,d);assertFalse(f.acceptSubjectCompletion(old,listOf(a,b)));assertFalse(f.subjectReady)}
    @Test fun delayedPhotoCompletionIsRejectedEvenSameCoordinates(){val f=flow();val old=f.subjectTicket();f.photoLoaded();f.picked(Point(.5f,.5f));f.chooseSubjects();f.addSubjectPoint(a);f.addSubjectPoint(b);assertFalse(f.acceptSubjectCompletion(old,listOf(a,b)))}
    @Test fun partialProgressAndRepeatedCallbacksAreIdempotent(){val f=flow();val t=f.subjectTicket();repeat(10){assertTrue(f.acceptSubjectCompletion(t,listOf(a)))};assertEquals(1,f.subjectCompletedCount);assertFalse(f.canGenerate);f.acceptSubjectCompletion(t,listOf(a,b));assertTrue(f.canGenerate)}
    @Test fun finishAndToggleDoNotEraseProgress(){val f=ready();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.finish();f.toggleSubject();f.toggleSubject();assertTrue(f.subjectReady)}
    @Test fun clearingLastPointCannotClaimReady(){val f=ready();f.removeSubjectPoint(a);f.removeSubjectPoint(b);assertFalse(f.subjectReady);assertFalse(f.canGenerate)}
    @Test fun focusRepickStillExplicitlyInvalidatesAll(){val f=ready();val old=f.subjectTicket();f.picked(f.focus!!);assertEquals(0,f.subjectCompletedCount);assertFalse(f.acceptSubjectCompletion(old,listOf(a,b)))}
}
