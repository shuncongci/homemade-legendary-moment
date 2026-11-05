package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstantStyleTest {
    private val style=LeicaStyle.LEICA_INSTANT
    @Test fun exactNameAndStableId(){assertEquals("徕卡一瞬",style.label);assertEquals("leica-instant-v2",style.id);assertSame(style,LeicaStyle.fromId(style.id))}
    @Test fun thirdPublicStyle(){assertEquals(listOf(LeicaStyle.COOL,LeicaStyle.WARM,style),LeicaStyle.entries.toList())}
    @Test fun lutIsSeparateAndNotAModel(){assertEquals("looks/leica-instant-v2.bin",style.asset);assertFalse(style.asset.endsWith(".onnx"));assertNotEquals(LeicaStyle.COOL.sha256,style.sha256)}
    @Test fun switchingRetainsLastImageAndSave(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(style);assertTrue(f.resultStale);assertTrue(f.hasResult);assertTrue(f.canSave);assertEquals(PhotoFlow.Operation.IDLE,f.operation)}
    @Test fun savedImageIsNotSilentlyReprocessed(){val f=PhotoFlow();f.photoLoaded();f.generated();f.saved();f.selectStyle(style);assertTrue(f.saved);assertFalse(f.canSave);assertTrue(f.resultStale)}
    @Test fun stateRestoresNewStyleWithoutGenerating(){val f=PhotoFlow();f.photoLoaded();f.selectStyle(style);f.generated();val copy=PhotoFlow();copy.restore(f.snapshot());assertSame(style,copy.style);assertTrue(copy.hasResult);assertFalse(copy.resultStale);assertEquals(PhotoFlow.Operation.IDLE,copy.operation)}
    @Test fun busyGuardIncludesNewStyle(){for(op in PhotoFlow.Operation.entries.filter{it!=PhotoFlow.Operation.IDLE}){val f=PhotoFlow();f.begin(op);try{f.selectStyle(style);fail("busy style change")}catch(_:IllegalStateException){};assertSame(LeicaStyle.DEFAULT,f.style)}}
    @Test fun cancelKeepsSelectedStyleAndPriorResult(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(style);f.begin(PhotoFlow.Operation.GENERATE);f.cancel();f.finish();assertSame(style,f.style);assertTrue(f.hasResult);assertTrue(f.resultStale)}
    @Test fun compositionSnapshotCapturesActualStyle(){val a=CompositionAppearance(true,false,null,2.8f,style);val f=PhotoFlow();f.selectStyle(LeicaStyle.WARM);assertSame(style,a.style)}
    @Test fun onlyBlurDoesNotExecuteColorLambda(){var called=false;val out=ProcessingMode.BLUR_ONLY.selectColorPath(color={called=true;error("LUT must not load")},original={"source"});assertFalse(called);assertEquals("source",out)}
    @Test fun cacheKeyChangeInvalidatesRenderButKeepsSavedResult(){
        val dir=Files.createTempDirectory("instant-session").toFile()
        try{
            val s=EditSession(dir);val p=Planes(2,1)
            s.beginRender("251|cool|old-hash");s.putPlanes("render.fused",p,Cancel())
            s.store.update(blobs=mapOf("result" to {f:File->f.writeBytes(byteArrayOf(9,7))}))
            s.beginRender("251|${style.id}|${style.sha256}");assertNull(s.planes("render.fused",Cancel()));assertArrayEquals(byteArrayOf(9,7),s.store.file("result")!!.readBytes())
            s.putPlanes("render.fused",p,Cancel());s.beginRender("251|${style.id}|${style.sha256}");assertNotNull(s.planes("render.fused",Cancel()))
            s.beginRender("251|${style.id}|new-hash");assertNull(s.planes("render.fused",Cancel()))
        }finally{dir.deleteRecursively()}
    }
}
