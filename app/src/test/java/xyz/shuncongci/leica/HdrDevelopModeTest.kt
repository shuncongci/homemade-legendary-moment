package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pure routing and real durable-checkpoint regression; not Android codec or HDR-screen acceptance. */
class HdrDevelopModeTest {
    private inline fun <reified T:Throwable> rejects(block:()->Unit){
        try{block();fail("Expected ${T::class.java.simpleName}")}catch(t:Throwable){if(t !is T)throw t}
    }
    private fun <T> temp(block:(File)->T):T {
        val dir=Files.createTempDirectory("hdr-choice-").toFile()
        try{return block(dir)}finally{check(dir.deleteRecursively())}
    }
    @Test fun sdrNeverInvokesHdrDetection(){assertFalse(HdrDevelopMode.SDR.wantsHdr{error("SDR must not decode HDR")})}
    @Test fun explicitHdrNeverUsesAutomaticDetection(){assertTrue(HdrDevelopMode.HDR.wantsHdr{error("HDR is explicit")})}
    @Test fun legacyAutoStillClassifiesBothSources(){var calls=0;assertTrue(HdrDevelopMode.AUTO.wantsHdr{calls++;true});assertFalse(HdrDevelopMode.AUTO.wantsHdr{calls++;false});assertEquals(2,calls)}
    @Test fun strictOnlyForHdr(){assertTrue(HdrDevelopMode.HDR.strict);assertFalse(HdrDevelopMode.SDR.strict);assertFalse(HdrDevelopMode.AUTO.strict)}
    @Test fun explicitHdrRejectsOldPlatforms(){for(api in 26..33)rejects<IllegalArgumentException>{HdrDevelopMode.HDR.requirePlatform(api)}}
    @Test fun explicitHdrAcceptsSupportedPlatforms(){for(api in 34..36)HdrDevelopMode.HDR.requirePlatform(api)}
    @Test fun sdrDoesNotRequireHdrPlatform(){for(api in 26..36)HdrDevelopMode.SDR.requirePlatform(api)}
    @Test fun hdrCannotPublishMissingHdrOutput(){rejects<IllegalStateException>{HdrDevelopMode.HDR.requireOutput(false)}}
    @Test fun hdrAcceptsRealEncodedOutput(){HdrDevelopMode.HDR.requireOutput(true)}
    @Test fun diagnosticReceiverCanConsumeHdrWithoutEncoding(){HdrDevelopMode.HDR.requireOutput(false,true)}
    @Test fun sdrAcceptsOnlySdrOutput(){HdrDevelopMode.SDR.requireOutput(false);rejects<IllegalStateException>{HdrDevelopMode.SDR.requireOutput(true)};rejects<IllegalStateException>{HdrDevelopMode.SDR.requireOutput(false,true)}}
    @Test fun everyModeHasIndependentRenderIdentity(){assertEquals(3,HdrDevelopMode.entries.map{it.renderKey("same-source-and-parameters")}.toSet().size)}
    @Test fun sameModeHasStableRenderIdentity(){for(mode in HdrDevelopMode.entries)assertEquals(mode.renderKey("photo"),mode.renderKey("photo"))}
    @Test fun sdrSourceCannotRestoreHdrChoice(){for(value in listOf(null,"HDR","SDR","AUTO","bad"))assertSame(HdrDevelopMode.SDR,HdrDevelopMode.restore(value,false))}
    @Test fun hdrSourceRestoresExplicitSdr(){assertSame(HdrDevelopMode.SDR,HdrDevelopMode.restore("SDR",true))}
    @Test fun hdrSourceRestoresExplicitHdr(){assertSame(HdrDevelopMode.HDR,HdrDevelopMode.restore("HDR",true))}
    @Test fun oldHdrSessionDefaultsToHdr(){assertSame(HdrDevelopMode.HDR,HdrDevelopMode.restore(null,true))}
    @Test fun editorNeverRestoresAuto(){for(value in listOf("AUTO","bad",""))assertSame(HdrDevelopMode.HDR,HdrDevelopMode.restore(value,true))}
    @Test fun splitOnlyForHdrDevelopWorkflow(){assertTrue(HdrDisplayPolicy.splitActions(true,false));assertFalse(HdrDisplayPolicy.splitActions(false,false));assertFalse(HdrDisplayPolicy.splitActions(true,true));assertFalse(HdrDisplayPolicy.splitActions(false,true))}
    @Test fun sdrKeepsThreeEqualButtons(){assertEquals(listOf(1f,1f,1f),HdrDisplayPolicy.actionWeights(false))}
    @Test fun hdrImportAndExportEachUseHalfFormerShare(){val w=HdrDisplayPolicy.actionWeights(true);assertEquals(listOf(.5f,1f,1f,.5f),w);assertEquals(1f/6,w.first()/w.sum(),.000001f);assertEquals(1f/6,w.last()/w.sum(),.000001f)}
    @Test fun twoHdrDevelopButtonsEachKeepFormerShare(){val w=HdrDisplayPolicy.actionWeights(true);assertEquals(1f/3,w[1]/w.sum(),.000001f);assertEquals(1f/3,w[2]/w.sum(),.000001f)}
    @Test fun compositionCanReuseOnlyMatchingReadyResult(){for(mode in HdrDevelopMode.entries)for(hdr in listOf(false,true))for(compose in listOf(false,true))for(hasResult in listOf(false,true))for(stale in listOf(false,true)){
        val expected=compose && hasResult && !stale && (mode==HdrDevelopMode.HDR && hdr || mode==HdrDevelopMode.SDR && !hdr)
        assertEquals("$mode/$hdr/$compose/$hasResult/$stale",expected,HdrDisplayPolicy.reuseForComposition(compose,hasResult,stale,mode,hdr))
    }}
    @Test fun switchToSdrCannotJustReuseHdrComposition(){assertFalse(HdrDisplayPolicy.reuseForComposition(true,true,false,HdrDevelopMode.SDR,true))}
    @Test fun switchToHdrCannotJustReuseSdrComposition(){assertFalse(HdrDisplayPolicy.reuseForComposition(true,true,false,HdrDevelopMode.HDR,false))}
    @Test fun hdrWindowRequiresVisibleActualGainmap(){for(api in 26..36)for(visible in listOf(false,true))for(os in listOf(false,true))for(rs in listOf(false,true))for(og in listOf(false,true))for(rg in listOf(false,true)){
        val expected=api>=34 && visible && (os && og || rs && rg)
        assertEquals("$api/$visible/$os/$rs/$og/$rg",expected,HdrDisplayPolicy.windowHdr(api,visible,os,rs,og,rg))
    }}
    @Test fun sdrResultDoesNotTurnHdrWindowOnForHiddenOriginal(){assertFalse(HdrDisplayPolicy.windowHdr(35,true,false,true,true,false))}
    @Test fun comparisonCanDisplayHdrOriginalBesideSdrResult(){assertTrue(HdrDisplayPolicy.windowHdr(35,true,true,true,true,false))}
    @Test fun hdrSideCannotBeCoveredWithSdrNativeTiles(){assertFalse(HdrDisplayPolicy.nativeDetailAllowed(true));assertTrue(HdrDisplayPolicy.nativeDetailAllowed(false))}
    @Test fun hdrPreviewReadsEncodedCompanionNotSdrPng(){val sdr=File("result.png");val hdr=File("result.hdr.jpg");assertSame(hdr,HdrDisplayPolicy.resultFile(sdr,hdr,true));assertSame(sdr,HdrDisplayPolicy.resultFile(sdr,hdr,false))}
    @Test fun missingHdrCompanionIsNotSilentlyRoutedToSdr()=temp{dir->val sdr=File(dir,"result.png").apply{writeText("SDR")};val hdr=File(dir,"missing.hdr.jpg");assertSame(hdr,HdrDisplayPolicy.resultFile(sdr,hdr,true));assertFalse(hdr.exists());assertTrue(sdr.isFile)}
    @Test fun persistedChoiceRoundTripsWithoutChangingSource()=temp{dir->
        val input=File(dir,"source.jpg").apply{writeText("immutable imported source")};val s=EditSession(File(dir,"session"));s.importSource(input);val before=s.source()!!.readBytes()
        for(mode in listOf(HdrDevelopMode.HDR,HdrDevelopMode.SDR)){s.ui(mapOf("hdrSource" to "true","hdrDevelop" to mode.name));val restored=EditSession(File(dir,"session"));assertSame(mode,HdrDevelopMode.restore(restored.ui()["hdrDevelop"],true));assertArrayEquals(before,restored.source()!!.readBytes())}
        assertEquals("immutable imported source",input.readText())
    }
    @Test fun switchingEitherDirectionClearsIntermediateButPreservesLastResult()=temp{dir->
        for((from,to) in listOf(HdrDevelopMode.HDR to HdrDevelopMode.SDR,HdrDevelopMode.SDR to HdrDevelopMode.HDR)){
            val s=EditSession(File(dir,from.name));s.store.update(blobs=mapOf("source" to {it.writeText("original")},"result" to {it.writeText("last complete")},"result-pair" to {it.writeText("last hdr")},"result-jpeg" to {it.writeText("last jpeg")}))
            s.beginRender(from.renderKey("photo"));s.store.update(mapOf("render.hdrFallback" to "old"),blobs=mapOf("render.output" to {it.writeText("old rgb")},"render.hdr" to {it.writeText("old hdr")},"render.multiscale" to {it.writeText("old scales")}))
            s.beginRender(to.renderKey("photo"));assertNull(s.store.file("render.output"));assertNull(s.store.file("render.hdr"));assertNull(s.store.file("render.multiscale"));assertNull(s.store.get("render.hdrFallback"))
            assertEquals("original",s.source()!!.readText());assertEquals("last complete",s.store.file("result")!!.readText());assertEquals("last hdr",s.store.file("result-pair")!!.readText());assertEquals("last jpeg",s.store.file("result-jpeg")!!.readText());assertEquals("render",s.store.get("job.kind"))
        }
    }
    @Test fun interruptionInSameModeKeepsRealCheckpoint()=temp{dir->val s=EditSession(dir);val key=HdrDevelopMode.HDR.renderKey("photo");s.beginRender(key);s.store.update(blobs=mapOf("render.hdr" to {it.writeText("exact float checkpoint")}));val restored=EditSession(dir);restored.beginRender(key);assertEquals("exact float checkpoint",restored.store.file("render.hdr")!!.readText())}
    @Test fun changingModeMarksExistingResultStaleWithoutLosingIt(){val f=PhotoFlow();f.photoLoaded();f.begin(PhotoFlow.Operation.GENERATE);f.generated();f.finish();f.parametersChanged();assertTrue(f.hasResult);assertTrue(f.resultStale);assertFalse(f.saved)}
}
