package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Point
import xyz.shuncongci.leica.LegendaryMath.Frame

class SubjectRequestRegressionTest {
    private val a=Point(.2f,.4f);private val b=Point(.8f,.4f)
    private fun flow()=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);picked(Point(.5f,.5f));toggleSubject();chooseSubjects();addSubjectPoint(a);endSubjectPicking()}
    @Test fun explicitAddCalculationMustNotBeRejectedByAnOpticalPreview(){val f=flow();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);assertTrue("A running optical-only preview must queue an explicit subject calculation, not discard its click",f.canComputeSubjects)}
    @Test fun cannotQueueWhileCancelling(){val f=flow();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.cancel();assertFalse(f.canComputeSubjects)}
    @Test fun unrelatedOperationsDoNotAllowSubjectRequests(){for(op in listOf(PhotoFlow.Operation.DEPTH,PhotoFlow.Operation.GENERATE,PhotoFlow.Operation.PREVIEW,PhotoFlow.Operation.SAVE,PhotoFlow.Operation.COMPOSITION)){val f=flow();f.begin(op);assertFalse(f.canComputeSubjects)}}
    @Test fun missingFocusStillBlocksRequest(){val f=flow();f.photoLoaded();assertFalse(f.canComputeSubjects)}
    @Test fun emptySelectionBlocksRequest(){val f=flow();f.clearSubjectPoints();assertFalse(f.canComputeSubjects)}
    @Test fun disabledProtectionBlocksRequest(){val f=flow();f.toggleSubject();assertFalse(f.canComputeSubjects)}
    @Test fun lateExplicitRequestForcesAnotherOpticalPass(){val f=flow();val updates=FocusPreviewUpdates();val work=SubjectPreviewWork();val optical=updates.request();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);assertFalse(work.needs(f.subjectTicket()));assertTrue(f.canComputeSubjects);work.request(f.subjectTicket());updates.request();assertFalse(updates.publish(optical));assertTrue(work.needs(f.subjectTicket()));assertTrue(updates.pending)}
    @Test fun ordinaryOpticalCompletionDoesNotConsumeExplicitWork(){val f=flow();val work=SubjectPreviewWork();work.request(f.subjectTicket());work.changed();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);f.finish();assertTrue(work.needs(f.subjectTicket()));assertFalse(work.isPresented(f.subjectTicket()))}
    @Test fun changingApertureDoesNotDropExplicitRequest(){val f=flow();val work=SubjectPreviewWork();work.request(f.subjectTicket());f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);repeat(20){f.aperture(if(it%2==0).95f else 2.8f);work.changed()};assertTrue(work.needs(f.subjectTicket()))}
    @Test fun completedCacheWithoutPresentationIsRetryable(){val f=flow();val work=SubjectPreviewWork();val t=f.subjectTicket();work.request(t);f.acceptSubjectCompletion(t,listOf(a));assertTrue(f.subjectReady);assertFalse(work.isPresented(t));assertTrue(work.needs(t));work.abort();assertFalse(work.needs(t));assertTrue(f.canComputeSubjects)}
    @Test(expected=IllegalStateException::class) fun partialCacheCannotConsumeRequest(){val f=flow();f.chooseSubjects();f.addSubjectPoint(b);val w=SubjectPreviewWork();w.request(f.subjectTicket());w.publish(f.subjectTicket(),listOf(a),true)}
    @Test(expected=IllegalStateException::class) fun missingOverlayCannotClaimApplied(){val f=flow();val w=SubjectPreviewWork();w.request(f.subjectTicket());w.publish(f.subjectTicket(),listOf(a),false)}
    @Test fun completeActualSnapshotAndOverlaySettleWork(){val f=flow();val w=SubjectPreviewWork();w.request(f.subjectTicket());w.publish(f.subjectTicket(),listOf(a),true);assertFalse(w.needs(f.subjectTicket()));assertTrue(w.isPresented(f.subjectTicket()))}
    @Test fun staleReadRejectedAsSoonAsComputeQueued(){val w=SubjectPreviewWork();val old=w.epoch;w.request(flow().subjectTicket());assertFalse(w.acceptsRead(old))}
    @Test fun staleReadRejectedAfterModelCommit(){val w=SubjectPreviewWork();val old=w.epoch;w.changed();assertFalse(w.acceptsRead(old))}
    @Test fun staleReadRejectedEvenAfterOperationIsIdleAgain(){val f=flow();val w=SubjectPreviewWork();val old=w.epoch;f.begin(PhotoFlow.Operation.FOCUS_PREVIEW);w.request(f.subjectTicket());f.acceptSubjectCompletion(f.subjectTicket(),listOf(a));w.publish(f.subjectTicket(),listOf(a),true);f.finish();assertFalse(f.busy);assertFalse(w.acceptsRead(old))}
    @Test fun currentCacheOnlyReadCanPublishWithoutInference(){val f=flow();val w=SubjectPreviewWork();val read=w.epoch;assertTrue(w.acceptsRead(read));w.publish(f.subjectTicket(),listOf(a),true);assertTrue(w.isPresented(f.subjectTicket()))}
    @Test fun changedSelectionClearsQueuedAndPublishedState(){val f=flow();val t=f.subjectTicket();val w=SubjectPreviewWork();w.request(t);w.publish(t,listOf(a),true);w.invalidate();assertFalse(w.needs(t));assertFalse(w.isPresented(t))}
    @Test fun newCoordinateNeedsNewPublication(){val f=flow();val w=SubjectPreviewWork();w.publish(f.subjectTicket(),listOf(a),true);f.moveSubjectPoint(a,b);assertFalse(w.isPresented(f.subjectTicket()))}
    @Test fun actualIncrementalCacheFeedsQueuedRequestOnlyOnce(){
        val f=flow();val cache=SubjectPointCache();val work=SubjectPreviewWork();var calls=0
        fun run(){val alpha=cache.resolve(true,"photo",f.subjectPoints,Cancel()){missing,commit->missing.forEach{p->calls++;commit(p,Frame(31,23,1,FloatArray(713){if(it%31<15 && p==a || it%31>=15 && p==b)1f else 0f}))}}!!;val done=cache.completed("photo",f.subjectPoints);f.acceptSubjectCompletion(f.subjectTicket(),done);work.publish(f.subjectTicket(),done,SubjectProtectionMath.overlay(alpha,31,23,Cancel()).isNotEmpty())}
        work.request(f.subjectTicket());run();assertEquals(1,calls)
        f.chooseSubjects();f.addSubjectPoint(b);f.endSubjectPicking();work.invalidate();f.begin(PhotoFlow.Operation.FOCUS_PREVIEW)
        assertTrue(f.canComputeSubjects);work.request(f.subjectTicket());run();f.finish();assertEquals(2,calls);assertTrue(f.subjectReady);assertTrue(work.isPresented(f.subjectTicket()))
        run();assertEquals(2,calls)
    }
    @Test fun loadingMessagesHaveNoModelNamesOrTick(){for(refining in listOf(false,true)){val s=UiText.subjectLoading(2,3,refining);assertTrue(s.contains("2/3"));for(word in listOf("sam","vitmatte","✓","✔"))assertFalse(s.lowercase().contains(word))};assertEquals("主体保护",UiText.SUBJECT_LABEL)}
    @Test(expected=IllegalArgumentException::class) fun invalidLoadingCountRejected(){UiText.subjectLoading(0,3,false)}
}
