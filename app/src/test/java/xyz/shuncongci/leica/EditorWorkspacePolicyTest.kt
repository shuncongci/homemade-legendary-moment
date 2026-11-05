package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** UI intent/state regression, not Android layout, codec, model or display acceptance. */
class EditorWorkspacePolicyTest {
    private fun result(saved:Boolean=false)=PhotoFlow().apply{photoLoaded();begin(PhotoFlow.Operation.GENERATE);generated();finish();if(saved)saved()}
    private fun choose(f:PhotoFlow,from:HdrDevelopMode=HdrDevelopMode.HDR,to:HdrDevelopMode=HdrDevelopMode.SDR)=EditorWorkspacePolicy.chooseMode(f,from,to,true)
    private fun temp(action:(File)->Unit){val d=Files.createTempDirectory("workspace-state-").toFile();try{action(d)}finally{check(d.deleteRecursively())}}
    @Test fun dockAlwaysHasFourStablePositiveShares(){val w=EditorWorkspacePolicy.actionWeights();assertEquals(listOf(1f,1f,1.7f,1f),w);assertEquals(4,w.size);assertTrue(w.all{it>0});assertTrue(w[2]>w[0])}
    @Test fun modeChoiceRequiresHdrPhotoAndIdleEditing(){for(hdr in listOf(false,true))for(only in listOf(false,true))for(photo in listOf(false,true))for(busy in listOf(false,true))for(composing in listOf(false,true))assertEquals(hdr && !only && photo && !busy && !composing,EditorWorkspacePolicy.canChooseMode(hdr,only,photo,busy,composing))}
    @Test fun everySdrSourceChoiceIsSdr(){for(mode in HdrDevelopMode.entries)assertSame(HdrDevelopMode.SDR,EditorWorkspacePolicy.normalizedMode(false,mode))}
    @Test fun hdrChoiceNeverExposesAuto(){for(mode in HdrDevelopMode.entries)assertNotSame(HdrDevelopMode.AUTO,EditorWorkspacePolicy.normalizedMode(true,mode));assertSame(HdrDevelopMode.HDR,EditorWorkspacePolicy.normalizedMode(true,HdrDevelopMode.AUTO))}
    @Test fun selectionDoesNotStartRendering(){val f=PhotoFlow().apply{photoLoaded()};assertSame(HdrDevelopMode.SDR,choose(f));assertFalse(f.busy);assertFalse(f.hasResult);assertFalse(f.resultStale);assertFalse(f.saved)}
    @Test fun sameChoiceDoesNotMakeSavedResultStale(){val f=result(true);val before=f.snapshot();choose(f,HdrDevelopMode.HDR,HdrDevelopMode.HDR);assertEquals(before,f.snapshot())}
    @Test fun normalizedSameChoiceDoesNotInvalidateSdr(){val f=result(true);val before=f.snapshot();assertSame(HdrDevelopMode.SDR,EditorWorkspacePolicy.chooseMode(f,HdrDevelopMode.SDR,HdrDevelopMode.HDR,false));assertEquals(before,f.snapshot())}
    @Test fun switchingModeOnlyMarksFutureParametersPending(){val f=result(true);val before=f.snapshot();choose(f);assertEquals(before+mapOf("stale" to "true"),f.snapshot());assertTrue(f.hasResult);assertTrue(f.saved);assertFalse(f.busy)}
    @Test fun switchingBackDoesNotEraseOtherPendingParameters(){val f=result();f.parametersChanged();val sdr=choose(f);choose(f,sdr,HdrDevelopMode.HDR);assertTrue(f.resultStale);assertTrue(f.hasResult)}
    @Test fun choicePreservesFocusAndCompletedSubjects(){val f=result();f.selectProcessingMode(ProcessingMode.COLOR_AND_BLUR);f.picked(LegendaryMath.Point(.4f,.6f));f.toggleSubject();f.chooseSubjects();f.addSubjectPoint(LegendaryMath.Point(.2f,.3f));f.endSubjectPicking();f.subjectCompleted();val ticket=f.subjectTicket();val before=f.snapshot();choose(f);assertEquals(before,f.snapshot());assertEquals(ticket,f.subjectTicket());assertTrue(f.subjectReady);assertEquals(1,f.subjectCompletedCount)}
    @Test fun choicePreservesEncodingPreferenceAndPanelVisibility(){val f=result(true);f.selectExportFormat(ExportFormat.WEBP_HIGH);f.showParameters(true);val before=f.snapshot();choose(f);assertEquals(before+mapOf("stale" to "true"),f.snapshot());assertSame(ExportFormat.WEBP_HIGH,f.exportFormat);assertTrue(f.parametersExpanded)}
    @Test fun everyBusyOperationRejectsModeMutation(){for(op in PhotoFlow.Operation.entries.filter{it!=PhotoFlow.Operation.IDLE}){val f=result(true);f.begin(op);val before=f.snapshot();try{choose(f);fail("busy $op accepted")}catch(_:IllegalStateException){};assertEquals(before,f.snapshot());assertSame(op,f.operation);f.finish()}}
    @Test fun changingIntentKeepsExistingResultExportable(){val f=result();choose(f);assertTrue(f.hasResult);assertTrue(f.canSave);assertTrue(f.canGenerate);assertFalse(f.parametersExpanded)}
    @Test fun failedNewRenderKeepsPendingPreviousResult(){val f=result();choose(f);f.begin(PhotoFlow.Operation.GENERATE);f.finish();assertTrue(f.hasResult);assertTrue(f.resultStale);assertTrue(f.canSave);assertFalse(f.parametersExpanded)}
    @Test fun cancelledNewRenderKeepsPreviousResult(){val f=result(true);choose(f);f.begin(PhotoFlow.Operation.GENERATE);f.cancel();f.finish();assertTrue(f.hasResult);assertTrue(f.saved);assertTrue(f.resultStale);assertFalse(f.cancelling)}
    @Test fun successfulRenderCommitsNewResultAndClearsPending(){val f=result(true);choose(f);f.begin(PhotoFlow.Operation.GENERATE);f.generated();f.finish();assertTrue(f.hasResult);assertFalse(f.saved);assertFalse(f.resultStale);assertFalse(f.parametersExpanded)}
    @Test fun geometryApplyDoesNotApplyPendingRenderingParameters(){val f=result();choose(f);f.compositionApplied();assertTrue(f.resultStale);assertTrue(f.hasResult);assertFalse(f.saved);assertFalse(f.parametersExpanded)}
    @Test fun absentResultIsNeverAdvertisedAsHdrOrPending(){for(hdr in listOf(false,true))for(pending in listOf(false,true))assertEquals("尚无成片",EditorWorkspacePolicy.renderedSummary(false,hdr,pending))}
    @Test fun resultSummaryUsesActualHdr(){assertEquals("现有成片 · HDR",EditorWorkspacePolicy.renderedSummary(true,true,false));assertEquals("现有成片 · SDR",EditorWorkspacePolicy.renderedSummary(true,false,false))}
    @Test fun pendingSummaryExplicitlyExportsPreviousPixels(){for(hdr in listOf(false,true)){val t=EditorWorkspacePolicy.renderedSummary(true,hdr,true);assertTrue(t.startsWith("现有成片 · ${if(hdr)"HDR"else "SDR"}"));assertTrue(t.contains("参数待应用"));assertTrue(t.contains("导出仍为上次成片"))}}
    @Test fun sourceSdrKeepsPlainActionLabels(){for(action in listOf("显影","重新显影","显影并构图","先选焦点"))for(mode in HdrDevelopMode.entries)assertEquals(action,EditorWorkspacePolicy.primaryLabel(action,mode,false,false))}
    @Test fun compositionOnlyKeepsPlainActionLabels(){for(action in listOf("选择构图","显影","请点焦点"))for(mode in HdrDevelopMode.entries)assertEquals(action,EditorWorkspacePolicy.primaryLabel(action,mode,true,true))}
    @Test fun blockersAreNotDisguisedAsReadyActions(){for(action in listOf("先选焦点","请点焦点","先计算保护","请结束选点","选择构图"))assertEquals(action,EditorWorkspacePolicy.primaryLabel(action,HdrDevelopMode.HDR,true,false))}
    @Test fun explicitModeAppearsOnPrimaryDevelopAction(){for(mode in listOf(HdrDevelopMode.HDR,HdrDevelopMode.SDR)){assertEquals("${mode.name}显影",EditorWorkspacePolicy.primaryLabel("显影",mode,true,false));assertEquals("${mode.name}显影",EditorWorkspacePolicy.primaryLabel("重新显影",mode,true,false));assertEquals("${mode.name}显影中",EditorWorkspacePolicy.primaryLabel("显影中",mode,true,false));assertEquals("${mode.name}显影＋构图",EditorWorkspacePolicy.primaryLabel("显影并构图",mode,true,false))}}
    @Test fun allSevenWorkflowsAndSizeRoutingSurviveModeChoice(){assertEquals(7,PhotoWorkflow.entries.size);for(w in PhotoWorkflow.entries){val f=result();w.processing?.let{f.selectProcessingMode(it)};val mode=f.processingMode;val output=w.output(BokehOutput.SIZE_1024);choose(f);assertSame(mode,f.processingMode);assertSame(output,w.output(BokehOutput.SIZE_1024));assertEquals(w.allowsOutputSelection,output==BokehOutput.SIZE_1024)}}
    @Test fun nextModeCannotChangeExportFormatListOfExistingPixels(){for(actualHdr in listOf(false,true)){val f=result();val options=ExportMenuChoices.available(actualHdr,35);val text=EditorWorkspacePolicy.renderedSummary(true,actualHdr,false);choose(f);assertEquals(options,ExportMenuChoices.available(actualHdr,35));assertEquals(text+"\n参数待应用 · 导出仍为上次成片",EditorWorkspacePolicy.renderedSummary(f.hasResult,actualHdr,f.resultStale))}}
    @Test fun changingIntentCannotReuseOppositeHdrComposition(){for((from,to) in listOf(HdrDevelopMode.HDR to HdrDevelopMode.SDR,HdrDevelopMode.SDR to HdrDevelopMode.HDR)){val f=result();val next=choose(f,from,to);assertFalse(HdrDisplayPolicy.reuseForComposition(true,f.hasResult,f.resultStale,next,from==HdrDevelopMode.HDR));assertNotEquals(from.renderKey("same"),next.renderKey("same"))}}
    @Test fun explicitHdrStillRejectsMissingHdrInsteadOfFallingBack(){val f=result();val next=choose(f,HdrDevelopMode.SDR,HdrDevelopMode.HDR);try{next.requireOutput(false);fail("missing HDR accepted")}catch(_:IllegalStateException){};assertTrue(f.hasResult);assertTrue(f.resultStale)}
    @Test fun durableIntentRoundTripLeavesSourceResultAndCheckpointUntouched(){temp{dir->
        val session=EditSession(File(dir,"edit"));val input=File(dir,"input").apply{writeBytes(ByteArray(127){it.toByte()})};session.importSource(input)
        session.store.update(blobs=mapOf("result" to {it.writeText("last complete pixels")},"result-pair" to {it.writeText("last HDR values")},"result-jpeg" to {it.writeText("last gainmap JPEG")},"render.hdr" to {it.writeText("previous render checkpoint")}))
        val keys=listOf("source","result","result-pair","result-jpeg","render.hdr");val bytes=keys.associateWith{session.store.file(it)!!.readBytes()};val f=result(true);val next=choose(f)
        session.ui(f.snapshot()+mapOf("hdrDevelop" to next.name));val restored=EditSession(File(dir,"edit"));val restoredFlow=PhotoFlow().apply{restore(restored.ui())}
        assertSame(next,HdrDevelopMode.restore(restored.ui()["hdrDevelop"],true));assertEquals(f.snapshot(),restoredFlow.snapshot());for(key in keys)assertArrayEquals(bytes.getValue(key),restored.store.file(key)!!.readBytes());assertArrayEquals(bytes.getValue("source"),input.readBytes())
    }}
}
