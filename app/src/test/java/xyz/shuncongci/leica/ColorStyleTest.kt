package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Model identity, persistence, snapshot and interaction invariants; no Android mocks or training. */
class ColorStyleTest {
    @Test fun exactlyTwoUserStyles(){assertEquals(listOf("徕卡冷调","徕卡暖调"),LeicaStyle.entries.map{it.label})}
    @Test fun defaultIsCool(){assertSame(LeicaStyle.COOL,LeicaStyle.DEFAULT);assertSame(LeicaStyle.COOL,PhotoFlow().style)}
    @Test fun stableIdsRestoreBothModes(){for(s in LeicaStyle.entries)assertSame(s,LeicaStyle.fromId(s.id))}
    @Test fun unknownSavedValueFallsBackSafely(){for(id in listOf(null,"","LEGENDARY","0","unknown"))assertSame(LeicaStyle.DEFAULT,LeicaStyle.fromId(id))}
    @Test fun assetsAreDistinctEpoch6Models(){assertEquals(2,LeicaStyle.entries.map{it.asset}.toSet().size);for(s in LeicaStyle.entries)assertTrue(s.asset.endsWith("-epoch6.fp16.onnx"))}
    @Test fun hashesAreDistinctValidSha256(){assertEquals(2,LeicaStyle.entries.map{it.sha256}.toSet().size);for(s in LeicaStyle.entries)assertTrue(s.sha256.matches(Regex("[a-f0-9]{64}")))}
    @Test fun coldIsTheFourPointZeroCheckpoint(){assertEquals("leica/leica-cool-epoch6.fp16.onnx",LeicaStyle.COOL.asset)}
    @Test fun warmIsTheThreePointZeroCheckpoint(){assertEquals("leica/leica-warm-epoch6.fp16.onnx",LeicaStyle.WARM.asset)}
    @Test fun styleSelectionBeforePhotoDoesNotCreateResult(){val f=PhotoFlow();f.selectStyle(LeicaStyle.WARM);assertFalse(f.hasPhoto);assertFalse(f.hasResult);assertFalse(f.resultStale);assertFalse(f.canGenerate)}
    @Test fun choosingSameModeDoesNotInvalidateResult(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(f.style);assertFalse(f.resultStale);assertTrue(f.hasResult)}
    @Test fun switchingStyleMarksExistingColorResultStale(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(LeicaStyle.WARM);assertTrue(f.resultStale);assertTrue(f.hasResult);assertTrue(f.canSave);assertTrue(f.canGenerate)}
    @Test fun styleEditDoesNotOverwriteOrResaveOldResult(){val f=PhotoFlow();f.photoLoaded();f.generated();f.saved();f.selectStyle(LeicaStyle.WARM);assertTrue(f.saved);assertFalse(f.canSave);assertTrue(f.resultStale)}
    @Test fun newGenerationClearsStaleOnlyOnSuccess(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(LeicaStyle.WARM);f.begin(PhotoFlow.Operation.GENERATE);f.finish();assertTrue(f.resultStale);f.begin(PhotoFlow.Operation.GENERATE);f.generated();f.finish();assertFalse(f.resultStale);assertFalse(f.saved)}
    @Test fun busyRejectsStyleEditsForAllOperations(){for(op in PhotoFlow.Operation.entries.filter{it!=PhotoFlow.Operation.IDLE}){val f=PhotoFlow();f.begin(op);try{f.selectStyle(LeicaStyle.WARM);fail("busy style edit accepted: $op")}catch(_:IllegalStateException){};assertSame(LeicaStyle.DEFAULT,f.style)}}
    @Test fun cancelledGenerationKeepsSelectedStyleAndOldPixels(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(LeicaStyle.WARM);f.begin(PhotoFlow.Operation.GENERATE);f.cancel();f.finish();assertSame(LeicaStyle.WARM,f.style);assertTrue(f.hasResult);assertTrue(f.resultStale)}
    @Test fun changingPhotoKeepsUserStyleButNotPreviousResult(){val f=PhotoFlow();f.selectStyle(LeicaStyle.WARM);f.photoLoaded();f.generated();f.photoLoaded();assertSame(LeicaStyle.WARM,f.style);assertFalse(f.hasResult);assertFalse(f.resultStale);assertTrue(f.parametersExpanded)}
    @Test fun hiddenStyleIsRememberedThroughBlurOnly(){val f=PhotoFlow();f.selectStyle(LeicaStyle.WARM);f.selectProcessingMode(ProcessingMode.BLUR_ONLY);f.photoLoaded();f.picked(LegendaryMath.Point(.5f,.5f));f.generated();f.selectStyle(LeicaStyle.COOL);assertFalse(f.resultStale);f.selectProcessingMode(ProcessingMode.COLOR_AND_BLUR);assertSame(LeicaStyle.COOL,f.style);assertTrue(f.resultStale)}
    @Test fun styleSwitchDoesNotInvalidateFocusOrCompletedSubjects(){val f=PhotoFlow();f.photoLoaded();f.selectProcessingMode(ProcessingMode.COLOR_AND_BLUR);val p=LegendaryMath.Point(.4f,.5f);f.picked(p);f.toggleSubject();f.chooseSubjects();f.addSubjectPoint(p);f.endSubjectPicking();f.subjectCompleted();val ticket=f.subjectTicket();f.selectStyle(LeicaStyle.WARM);assertSame(p,f.focus);assertTrue(f.subjectReady);assertEquals(ticket,f.subjectTicket());assertTrue(f.canGenerate)}
    @Test fun onlyColorWorkflowsExposeStyleChoice(){for(w in PhotoWorkflow.entries)assertEquals(w.processing?.colorEnabled==true,w.allowsColorSelection)}
    @Test fun styleControlsDoNotChangeOutputPolicy(){for(w in PhotoWorkflow.entries){val f=PhotoFlow();w.processing?.let{f.selectProcessingMode(it)};val old=w.output(BokehOutput.SIZE_1024);f.selectStyle(LeicaStyle.WARM);assertEquals(old,w.output(BokehOutput.SIZE_1024))}}
    @Test fun resultAppearanceCapturesActualStyle(){val f=PhotoFlow();f.selectStyle(LeicaStyle.WARM);val a=CompositionAppearance(true,false,null,2.8f,f.style);f.selectStyle(LeicaStyle.COOL);assertSame(LeicaStyle.WARM,a.style);assertSame(LeicaStyle.COOL,f.style)}
    @Test fun legacyAppearanceDefaultsToCurrentDefault(){assertSame(LeicaStyle.DEFAULT,CompositionAppearance(false,false,null,2.8f).style)}
    @Test fun modeSwitchDoesNotRunAnyOperation(){val f=PhotoFlow();f.photoLoaded();f.selectStyle(LeicaStyle.WARM);assertEquals(PhotoFlow.Operation.IDLE,f.operation);assertFalse(f.hasResult)}
}
