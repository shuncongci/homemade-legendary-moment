package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class ExternalModePolicyTest {
    private fun photo(id:String="new",size:Long=100,modified:Long=10,pending:Boolean=false,created:Long=0,name:String="$id.jpg")=ExternalPhoto("/DCIM/Camera/$id","file:///DCIM/Camera/$id",name,size,modified,pending,created)
    private fun settle(g:ExternalArrivalGate,ps:List<ExternalPhoto>,start:Long=0):List<ExternalPhoto>{assertTrue(g.observe(ps,start).isEmpty());assertTrue(g.observe(ps,start+1500).isEmpty());return g.observe(ps,start+3000)}
    @Test fun allExistingPhotosAreBaselineNotWork(){val old=(1..500).map{photo("old$it")};assertTrue(settle(ExternalArrivalGate(old),old).isEmpty())}
    @Test fun noUnprocessedFlagCanTurnOldIntoWork(){val old=listOf(photo("never-developed"));val g=ExternalArrivalGate(old);repeat(8){assertTrue(g.observe(old,it*5000L).isEmpty())}}
    @Test fun oldEditedPhotoIsNotNew(){val p=photo();assertTrue(settle(ExternalArrivalGate(listOf(p)),listOf(p.copy(size=999,modified=900))).isEmpty())}
    @Test fun oldPendingPhotoIsStillBaseline(){val p=photo(pending=true);assertTrue(settle(ExternalArrivalGate(listOf(p)),listOf(p.copy(pending=false))).isEmpty())}
    @Test fun oldEmptyPlaceholderIsNotNewWhenWritten(){val p=photo(size=0);assertTrue(settle(ExternalArrivalGate(listOf(p)),listOf(p.copy(size=500))).isEmpty())}
    @Test fun onlyArrivalsAfterBaselineAreWork(){val old=photo("old");val p=photo();assertEquals(listOf(p),settle(ExternalArrivalGate(listOf(old)),listOf(old,p)))}
    @Test fun stoppedPeriodPhotosAreSkippedOnReopen(){val old=photo("old");val duringStop=photo("during-stop");assertTrue(settle(ExternalArrivalGate(listOf(old,duringStop)),listOf(old,duringStop)).isEmpty())}
    @Test fun processRestartDoesNotBackfillOldQueue(){val oldQueued=photo("old-queued");val restarted=ExternalArrivalGate(listOf(oldQueued));assertTrue(settle(restarted,listOf(oldQueued)).isEmpty())}
    @Test fun restartCanAcceptLaterCapture(){val oldQueued=photo("old-queued");val p=photo();assertEquals(listOf(p),settle(ExternalArrivalGate(listOf(oldQueued)),listOf(oldQueued,p)))}
    @Test fun emittedPhotoIsNeverEmittedTwice(){val p=photo();val g=ExternalArrivalGate(emptyList());assertEquals(listOf(p),settle(g,listOf(p)));repeat(8){assertTrue(g.observe(listOf(p),5000L+it*1000).isEmpty())}}
    @Test fun emittedPathModificationIsNotAnotherArrival(){val p=photo();val g=ExternalArrivalGate(emptyList());settle(g,listOf(p));assertTrue(g.observe(listOf(p.copy(size=900,modified=901)),8000).isEmpty())}
    @Test fun needsThreeObservations(){val g=ExternalArrivalGate(emptyList());assertTrue(g.observe(listOf(photo()),0).isEmpty());assertTrue(g.observe(listOf(photo()),5000).isEmpty());assertEquals(1,g.observe(listOf(photo()),5001).size)}
    @Test fun threeInstantObservationsAreNotStable(){val g=ExternalArrivalGate(emptyList());repeat(3){assertTrue(g.observe(listOf(photo()),it.toLong()).isEmpty())}}
    @Test fun sizeChangeRestartsSettling(){val g=ExternalArrivalGate(emptyList());val p=photo();g.observe(listOf(p),0);g.observe(listOf(p),1500);val changed=p.copy(size=200);assertTrue(g.observe(listOf(changed),3000).isEmpty());assertTrue(g.observe(listOf(changed),4500).isEmpty());assertEquals(listOf(changed),g.observe(listOf(changed),6000))}
    @Test fun modifiedTimeChangeRestartsSettling(){val g=ExternalArrivalGate(emptyList());val p=photo();g.observe(listOf(p),0);g.observe(listOf(p),1500);val changed=p.copy(modified=50);assertTrue(g.observe(listOf(changed),3000).isEmpty());assertTrue(g.observe(listOf(changed),4500).isEmpty());assertEquals(1,g.observe(listOf(changed),6000).size)}
    @Test fun pendingPhotoCannotProcess(){val g=ExternalArrivalGate(emptyList());repeat(5){assertTrue(g.observe(listOf(photo(pending=true)),it*5000L).isEmpty())}}
    @Test fun pendingClearedStartsFreshStableWindow(){val g=ExternalArrivalGate(emptyList());g.observe(listOf(photo(pending=true)),0);assertEquals(1,settle(g,listOf(photo()),5000).size)}
    @Test fun zeroLengthNeverProcesses(){val g=ExternalArrivalGate(emptyList());repeat(5){assertTrue(g.observe(listOf(photo(size=0)),it*5000L).isEmpty())}}
    @Test fun disappearedArrivalRestartsStableWindow(){val g=ExternalArrivalGate(emptyList());val p=photo();g.observe(listOf(p),0);g.observe(listOf(p),1500);g.observe(emptyList(),3000);assertEquals(listOf(p),settle(g,listOf(p),6000))}
    @Test fun disappearedOldPhotoDoesNotBecomeNewWhenItReturns(){val p=photo();val g=ExternalArrivalGate(listOf(p));g.observe(emptyList(),1000);assertTrue(settle(g,listOf(p),5000).isEmpty())}
    @Test fun delayedIndexingOfPreStartPhotoIsNotNew(){val p=photo(created=900);assertTrue(settle(ExternalArrivalGate(emptyList(),1000),listOf(p)).isEmpty())}
    @Test fun afterStartCaptureCanProcess(){val p=photo(created=1001);assertEquals(listOf(p),settle(ExternalArrivalGate(emptyList(),1000),listOf(p)))}
    @Test fun stableUnknownTimestampStillNeedsNewIdentity(){val old=photo("old",created=0);val p=photo(created=0);assertEquals(listOf(p),settle(ExternalArrivalGate(listOf(old),1000),listOf(old,p)))}
    @Test fun newArrivalsAreOrderedByCaptureMetadataThenKey(){val a=photo("a",modified=1);val b=photo("b",modified=1);val c=photo("c",modified=2);assertEquals(listOf(a,b,c),settle(ExternalArrivalGate(emptyList()),listOf(c,b,a)))}
    @Test fun unsupportedFilesAreNeverWork(){assertTrue(settle(ExternalArrivalGate(emptyList()),listOf(photo(name="camera.mp4"),photo("part",name="IMG.jpg.part"))).isEmpty())}
    @Test fun selfExportsAreNeverWork(){assertTrue(settle(ExternalArrivalGate(emptyList()),listOf(photo(name=ExternalModePolicy.OUTPUT_PREFIX+"test.jpg"))).isEmpty())}
    @Test fun supportedCameraFormats(){listOf("JPG","jpeg","png","WEBP","heic","heif","DNG").forEach{assertTrue(ExternalModePolicy.image("IMG.$it"))}}
    @Test fun excludesHiddenAndTemporaryFiles(){listOf(".hidden.jpg","IMG.jpg.part","IMG.tmp","file","movie.mp4").forEach{assertFalse(ExternalModePolicy.image(it))}}
    @Test fun cameraScorePrefersDcimCamera(){assertTrue(ExternalModePolicy.cameraScore("DCIM/Camera/")>ExternalModePolicy.cameraScore("DCIM/100ANDRO/"));assertEquals(0,ExternalModePolicy.cameraScore("Pictures/Downloads"))}
    @Test fun cameraScoreHandlesStorageRoot(){assertEquals(100,ExternalModePolicy.cameraScore("/storage/emulated/0/DCIM/Camera"))}
    @Test fun outputDirectoryIsExcluded(){assertTrue(ExternalModePolicy.outputPath("Pictures/传奇一瞬/外接/"));assertTrue(ExternalModePolicy.outputPath("/storage/emulated/0/Pictures/传奇一瞬/外接"));assertTrue(ExternalModePolicy.outputPath("Pictures/传奇一瞬"))}
    @Test fun cameraIsNotOutput(){assertFalse(ExternalModePolicy.outputPath("DCIM/Camera"));assertFalse(ExternalModePolicy.outputPath("Pictures/传奇一瞬-other"))}
    @Test fun idIsStableAndSafe(){val p=photo();assertEquals(p.id,p.copy(name="different.jpg").id);assertTrue(p.id.matches(Regex("[a-f0-9]{64}")))}
    @Test fun contentIdentityChangesWithMetadata(){assertNotEquals(photo().id,photo(size=101).id);assertNotEquals(photo().id,photo(modified=101).id);assertNotEquals(photo().id,photo("different").id)}
    @Test fun sourceSnapshotRequiresSameKeySizeTimeAndNotPending(){val p=photo();assertTrue(p.sameBytes(p));assertFalse(p.sameBytes(p.copy(key="other")));assertFalse(p.sameBytes(p.copy(size=9)));assertFalse(p.sameBytes(p.copy(modified=9)));assertFalse(p.sameBytes(p.copy(pending=true)))}
    @Test fun sdrPolicyNeverInvokesHdrDetection(){var called=false;assertFalse(ExternalExportPolicy.SDR.mode().wantsHdr{called=true;true});assertFalse(called)}
    @Test fun autoPolicyUsesRealSourceDetection(){assertFalse(ExternalExportPolicy.AUTO_HDR.mode().wantsHdr{false});assertTrue(ExternalExportPolicy.AUTO_HDR.mode().wantsHdr{true})}
    @Test fun defaultStrategyIsConservativeSdr(){assertEquals(ExternalExportPolicy.SDR,ExternalExportPolicy.fromId(null));assertEquals(ExternalExportPolicy.SDR,ExternalExportPolicy.fromId("invalid"))}
    @Test fun stablePolicyIdsRoundTrip(){ExternalExportPolicy.entries.forEach{assertEquals(it,ExternalExportPolicy.fromId(it.id))}}
    @Test fun ordinaryEditorRemainsStrictHdr(){assertTrue(HdrDevelopMode.HDR.strict);try{HdrDevelopMode.HDR.requireOutput(false);fail()}catch(_:IllegalStateException){}}
    @Test fun hdrHasOnlyJpegOutput(){ExportFormat.entries.forEach{assertTrue(ExternalModePolicy.outputName(photo().id,true,it).endsWith(".jpg"))}}
    @Test fun sdrUsesChosenFormat(){ExportFormat.entries.forEach{assertTrue(ExternalModePolicy.outputName(photo().id,false,it).endsWith(".${it.extension}"))}}
    @Test fun filenameCannotEscapeFolder(){try{ExternalModePolicy.outputName("../../test",false,ExportFormat.PNG);fail()}catch(_:IllegalArgumentException){}}
    @Test fun noFallbackOnCancel(){assertFalse(HdrAutoPolicy.canFallback(CancellationException()));assertFalse(HdrAutoPolicy.canFallback(IllegalStateException(CancellationException())))}
    @Test fun noFallbackOnIoFailure(){assertFalse(HdrAutoPolicy.canFallback(IOException()));assertFalse(HdrAutoPolicy.canFallback(IllegalStateException(IOException())))}
    @Test fun noFallbackOnMemoryFailure(){assertFalse(HdrAutoPolicy.canFallback(OutOfMemoryError()));assertFalse(HdrAutoPolicy.canFallback(IllegalStateException(OutOfMemoryError())))}
    @Test fun ordinaryCapabilityFailureCanFallback(){assertTrue(HdrAutoPolicy.canFallback(IllegalArgumentException("unsupported gainmap")))}
    @Test fun recoverRecognizesOnlyPublishedExistingOutputs(){assertTrue(ExternalModePolicy.shouldRecoverAsDone(true,true));assertFalse(ExternalModePolicy.shouldRecoverAsDone(false,true));assertFalse(ExternalModePolicy.shouldRecoverAsDone(true,false));assertFalse(ExternalModePolicy.shouldRecoverAsDone(false,false))}
    @Test fun photoOutputDoesNotChangeInputKey(){val p=photo();val key=p.key;ExternalModePolicy.outputName(p.id,true,ExportFormat.PNG);assertEquals(key,p.key)}
    @Test(expected=IllegalArgumentException::class) fun excessiveBaselineFailsClosed(){ExternalArrivalGate((0..ExternalModePolicy.MAX_ENTRIES).map{photo("$it")})}
    @Test(expected=IllegalArgumentException::class) fun excessiveScanFailsClosed(){ExternalArrivalGate(emptyList()).observe((0..ExternalModePolicy.MAX_ENTRIES).map{photo("$it")},0)}
}
