package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class PhotoFlowTest {
    @Test fun defaultModeRemainsColorOnly(){val f=PhotoFlow();assertEquals(ProcessingMode.COLOR_ONLY,f.processingMode);assertTrue(f.colorEnabled);assertFalse(f.blur)}
    @Test fun blurOnlyNeedsPhotoAndFocus(){val f=PhotoFlow();f.selectProcessingMode(ProcessingMode.BLUR_ONLY);assertFalse(f.canGenerate);f.photoLoaded();assertFalse(f.canGenerate);f.picked(LegendaryMath.Point(.5f,.5f));assertTrue(f.canGenerate);assertFalse(f.colorEnabled);assertTrue(f.blur)}
    @Test fun blurOnlySkipsGanAndFusionBranch(){
        val original=Any();var colorCalls=0
        val result=ProcessingMode.BLUR_ONLY.selectColorPath(color={colorCalls++;error("GAN/fusion must not run")},original={original})
        assertSame(original,result);assertEquals(0,colorCalls)
    }
    @Test fun colorModesKeepColorBranch(){for(mode in listOf(ProcessingMode.COLOR_ONLY,ProcessingMode.COLOR_AND_BLUR)){var calls=0;assertEquals(7,mode.selectColorPath(color={calls++;7},original={error("wrong branch")}));assertEquals(1,calls)}}
    @Test fun noEmptyModeAllowed(){try{ProcessingMode.from(false,false);fail()}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("至少"))}}
    @Test fun modeSwitchCannotHappenWhileBusy(){val f=PhotoFlow();f.begin(PhotoFlow.Operation.GENERATE);try{f.selectProcessingMode(ProcessingMode.BLUR_ONLY);fail()}catch(_:IllegalStateException){};assertEquals(ProcessingMode.COLOR_ONLY,f.processingMode)}
    @Test fun modeSwitchExitsFocusPicking(){val f=PhotoFlow();f.photoLoaded();f.selectProcessingMode(ProcessingMode.BLUR_ONLY);f.chooseFocus();assertTrue(f.picking);f.selectProcessingMode(ProcessingMode.COLOR_ONLY);assertFalse(f.picking);assertTrue(f.canGenerate)}
    @Test fun disablingBlurOnlyReturnsToColorNotNoOp(){val f=PhotoFlow();f.selectProcessingMode(ProcessingMode.BLUR_ONLY);f.toggleBlur();assertEquals(ProcessingMode.COLOR_ONLY,f.processingMode);f.toggleBlur();assertEquals(ProcessingMode.COLOR_AND_BLUR,f.processingMode)}
    @Test fun blurOnlyRepeatAndSaveWork(){val f=PhotoFlow();f.photoLoaded();f.selectProcessingMode(ProcessingMode.BLUR_ONLY);f.picked(LegendaryMath.Point(.3f,.4f));repeat(2){f.begin(PhotoFlow.Operation.GENERATE);f.generated();f.finish();assertTrue(f.canGenerate);assertTrue(f.canSave);assertEquals(ProcessingMode.BLUR_ONLY,f.processingMode)}}
    @Test fun newPhotoClearsBlurOnlyFocus(){val f=PhotoFlow();f.photoLoaded();f.selectProcessingMode(ProcessingMode.BLUR_ONLY);f.picked(LegendaryMath.Point(.3f,.4f));f.photoLoaded();assertNull(f.focus);assertFalse(f.canGenerate);assertEquals(ProcessingMode.BLUR_ONLY,f.processingMode)}

    @Test fun pickedFocusImmediatelyUnlocksGenerate(){val f=PhotoFlow();f.photoLoaded();f.toggleBlur();assertFalse(f.canGenerate);f.chooseFocus();assertFalse(f.canGenerate);f.picked(LegendaryMath.Point(.25f,.5f));assertTrue(f.canGenerate);assertFalse(f.picking)}
    @Test fun finishColorAllowsRepeatWithoutToggling(){val f=PhotoFlow();f.photoLoaded();repeat(3){assertTrue(f.canGenerate);f.begin(PhotoFlow.Operation.GENERATE);assertFalse(f.canGenerate);f.generated();f.finish();assertTrue(f.canGenerate);assertTrue(f.canSave)}}
    @Test fun finishBlurAllowsRepeatWithoutToggling(){val f=PhotoFlow();f.photoLoaded();f.toggleBlur();f.picked(LegendaryMath.Point(.5f,.5f));f.begin(PhotoFlow.Operation.GENERATE);f.generated();f.finish();assertTrue(f.canGenerate);assertTrue(f.blur)}
    @Test fun cancelOrFailureUnlocksAndRetainsPreviousResult(){val f=PhotoFlow();f.photoLoaded();f.generated();f.begin(PhotoFlow.Operation.GENERATE);f.cancel();assertTrue(f.cancelling);assertFalse(f.canGenerate);f.finish();assertTrue(f.canGenerate);assertTrue(f.canSave);assertTrue(f.hasResult);assertFalse(f.cancelling)}
    @Test fun saveThenRegenerateAndChooseNewPhoto(){val f=PhotoFlow();f.photoLoaded();f.generated();f.begin(PhotoFlow.Operation.SAVE);f.saved();f.finish();assertFalse(f.canSave);assertTrue(f.canGenerate);f.generated();assertTrue(f.canSave);f.toggleBlur();f.picked(LegendaryMath.Point(.3f,.4f));f.photoLoaded();assertNull(f.focus);assertFalse(f.hasResult);assertFalse(f.canGenerate);f.toggleBlur();assertTrue(f.canGenerate)}
    @Test fun blurOffNeverNeedsFocusAndBusyBlocksEveryGeneration(){val f=PhotoFlow();f.photoLoaded();assertTrue(f.canGenerate);f.begin(PhotoFlow.Operation.PREVIEW);assertFalse(f.canGenerate);f.finish();assertTrue(f.canGenerate);f.aperture(.95f);assertEquals(.95f,f.aperture,0f)}
}
