package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Real manifests/files reopened after interruption, with the production natural SDR/HDR renderer.
 * Synthetic pixels only. No percentages or fake completion counters stand in for saved output. */
class RenderStepRecoveryTest {
    private val key="270-test-photo-aperture-focus-style-subject"
    private fun <T> temp(body:(File)->T):T {val d=Files.createTempDirectory("render-step-test-").toFile();return try{body(d)}finally{check(d.deleteRecursively())}}
    private inline fun <reified T:Throwable> fails(body:()->Unit){try{body();fail("expected ${T::class.java.simpleName}")}catch(t:Throwable){if(t !is T)throw t}}
    private fun start(d:File):EditSession=EditSession(d).also{s->s.store.update(blobs=mapOf("source" to {it.writeText("immutable original")},"result" to {it.writeText("previous full result")}));s.beginRender(key)}
    private fun cache(s:EditSession,hdr:Boolean=false)=RenderStepCache(s.store,key,if(hdr)"hdr"else "sdr")
    private fun photo(w:Int=101,h:Int=77)=Planes(w,h).also{p->
        for(c in 0..2)for(i in 0 until p.size)p.plane(c)[i]=(25+(i*13+c*17)%95).toByte()
        for(y in h/2-1..h/2+1)for(x in w/2-1..w/2+1)for(c in 0..2)p.plane(c)[y*w+x]=(255-c*7).toByte()
    }
    private fun depth()=Frame(23,17,1,FloatArray(391){(.5+.46*sin(it%23*.23+it/23*.31)).toFloat()})
    private fun subject()=Frame(31,23,1,FloatArray(713){if(it%31 in 11..19)1f else 0f})
    private fun sharp(p:Planes)=Frame(p.width,p.height,3,FloatArray(p.size*3){i->HdrMath.linearByte(p.plane(i/p.size)[i%p.size].toInt()and 255)*(if(i%p.size%p.width<p.width/2)4f else .15f)})
    private data class Output(val p:Planes,val hdr:ByteArray?,val stages:List<String>,val layers:List<BokehLayerProgress>)
    private fun render(d:File,s:EditSession?,hdr:Boolean,split:Boolean=true,stop:String?=null,stopPart:Int=0,p:Planes=photo(),dep:Frame=depth(),mask:Frame?=subject()):Output {
        val work=File(d,"scratch").apply{mkdirs()};val target=if(hdr)File(d,"target-${java.util.UUID.randomUUID()}.f32")else null
        val c=Cancel();val stages=arrayListOf<String>();val layers=arrayListOf<BokehLayerProgress>()
        try{
            val out=FourTileBokeh.renderNatural(p,dep,.38f,.95f,c,work,subjectMask=mask,sharp=if(hdr)HdrPixels.of(sharp(p))else null,hdrTarget=target,split=split,checkpoint=s?.let{cache(it,hdr)},
                progress={stages+=it;if(it==stop)c.cancelled=true},onLayer={layers+=it},onPart={n,_->if(n==stopPart)c.cancelled=true})
            return Output(out,target?.readBytes(),stages,layers)
        }finally{target?.delete();assertTrue("UUID scratch must be cleaned on success/cancel/failure",work.listFiles()!!.isEmpty())}
    }
    private fun equal(a:Output,b:Output){for(c in 0..2)assertArrayEquals(a.p.plane(c),b.p.plane(c));if(a.hdr==null)assertNull(b.hdr)else assertArrayEquals(a.hdr,b.hdr)}
    private fun protected(s:EditSession){assertEquals("immutable original",s.source()!!.readText());assertEquals("previous full result",s.store.file("result")!!.readText())}
    private fun scenarios(hdr:Boolean,split:Boolean)=temp{d->
        val reference=render(d,null,hdr,split)
        val stages=listOf("准备 · 光学参数","SDR${if(hdr)"/HDR光能"else "高光"} · 共用光学散射","合成 · SDR画面")+(if(hdr)listOf("合成 · HDR画面")else emptyList())+"合成 · 主体边缘"
        for((i,stop) in stages.withIndex()){
            val s=start(File(d,"saved-$i"));fails<CancellationException>{render(d,s,hdr,split,stop)};protected(s)
            val next=EditSession(s.store.directory);val resumed=render(d,next,hdr,split);equal(reference,resumed);protected(next)
            assertFalse(next.store.directory.listFiles()!!.any{it.name.endsWith(".part")})
            if(stop=="合成 · 主体边缘"){
                assertTrue(resumed.layers.isEmpty());assertTrue(resumed.stages.any{it.endsWith("复用已保存全局散射")})
                assertFalse(resumed.stages.any{it.endsWith("提取光能") || it.contains("全局散射 ")})
                assertTrue(resumed.stages.any{it.endsWith("恢复已完成SDR画面")})
                if(hdr)assertTrue(resumed.stages.any{it.endsWith("恢复已完成HDR画面")})
            }
        }
    }
    @Test fun sdrFourRegionStepRestartsAreBitExact(){scenarios(false,true)}
    @Test fun hdrFourRegionStepRestartsAreBitExact(){scenarios(true,true)}
    @Test fun sdrSingleRegionStepRestartsAreBitExact(){scenarios(false,false)}
    @Test fun hdrSingleRegionStepRestartsAreBitExact(){scenarios(true,false)}
    @Test fun eachCompletedRegionSurvivesRestartAndDoesNotReplayLayerEvents()=temp{d->
        val reference=render(d,null,true)
        for(done in 1..4){val s=start(File(d,"saved-$done"));fails<CancellationException>{render(d,s,true,stopPart=done)}
            val next=EditSession(s.store.directory);val resumed=render(d,next,true);equal(reference,resumed)
            assertTrue(resumed.layers.none{it.part<=done});assertEquals(4-done,resumed.layers.map{it.part}.distinct().size)
            assertEquals(done,resumed.stages.count{it.contains("复用基础区域")});protected(next)
        }
    }
    @Test fun interruptedExtractionReusesOnlyPreparationAndBase()=temp{d->
        val s=start(File(d,"saved"));fails<CancellationException>{render(d,s,true,stop="SDR/HDR光能 · 提取光能")}
        assertNull(cache(s,true).load("light-source",Cancel()));val out=render(d,EditSession(s.store.directory),true)
        assertTrue(out.layers.isEmpty());assertEquals(1,out.stages.count{it.endsWith("提取光能")})
    }
    @Test fun interruptedScatterReusesCompletedExtractionNotPartialScatter()=temp{d->
        val s=start(File(d,"saved"));val p=photo(101,177)
        fails<CancellationException>{render(d,s,true,stop="SDR/HDR光能 · 全局散射 128/177行",p=p)}
        assertNotNull(cache(s,true).load("light-source",Cancel()));assertNull(cache(s,true).load("light-scatter",Cancel()))
        val resumed=render(d,EditSession(s.store.directory),true,p=p);equal(render(d,null,true,p=p),resumed)
        assertFalse(resumed.stages.any{it.endsWith("提取光能")});assertTrue(resumed.stages.any{it.endsWith("共用光学散射")})
    }
    @Test fun cancelledGroupCannotPublishHalfAndKeepsPreviousGroup()=temp{d->
        val s=start(d);val c=cache(s);c.save("sample",mapOf("a" to {it.writeText("old a")},"b" to {it.writeText("old b")}),Cancel())
        val cancel=Cancel();fails<CancellationException>{c.save("sample",mapOf("a" to {it.writeText("new a")},"b" to {it.writeText("partial");cancel.cancelled=true}),cancel)}
        val saved=cache(EditSession(d)).load("sample",Cancel())!!;assertEquals("old a",saved.files.getValue("a").readText());assertEquals("old b",saved.files.getValue("b").readText());protected(s)
        assertFalse(d.listFiles()!!.any{it.name.endsWith(".part")})
    }
    @Test fun ioFailureCannotReplaceCompleteGroup()=temp{d->
        val s=start(d);val c=cache(s);c.save("sample",mapOf("a" to {it.writeText("old")}),Cancel())
        fails<IOException>{c.save("sample",mapOf("a" to {it.writeText("partial");throw IOException("simulated disk full")}),Cancel())}
        assertEquals("old",cache(EditSession(d)).load("sample",Cancel())!!.files.getValue("a").readText());protected(s)
    }
    @Test fun preManifestFailureLeavesPreviousStepAfterReopen()=temp{d->
        val s=start(d);cache(s).save("sample",mapOf("a" to {it.writeText("old")}),Cancel())
        val faulty=LatestCheckpoint(d){if(it=="before-manifest")throw IOException("simulated death before manifest")}
        fails<IOException>{RenderStepCache(faulty,key,"sdr").save("sample",mapOf("a" to {it.writeText("new")}),Cancel())}
        assertEquals("old",cache(EditSession(d)).load("sample",Cancel())!!.files.getValue("a").readText())
    }
    @Test fun postManifestFailureKeepsWholeNewGroupAfterReopen()=temp{d->
        start(d);val faulty=LatestCheckpoint(d){if(it=="after-manifest")throw IOException("simulated death after manifest")}
        fails<IOException>{RenderStepCache(faulty,key,"sdr").save("sample",mapOf("a" to {it.writeText("new a")},"b" to {it.writeText("new b")}),Cancel())}
        val saved=cache(EditSession(d)).load("sample",Cancel())!!;assertEquals("new a",saved.files.getValue("a").readText());assertEquals("new b",saved.files.getValue("b").readText())
    }
    @Test fun sameLengthCorruptionIsRejectedOnReopenNotSilentlyReused()=temp{d->
        val s=start(d);cache(s).save("sample",mapOf("a" to {it.writeText("valid")}),Cancel());cache(s).load("sample",Cancel())!!.files.getValue("a").writeText("BROKE")
        fails<IllegalArgumentException>{cache(EditSession(d)).load("sample",Cancel())};protected(s)
    }
    @Test fun truncatedFileIsRejectedOnReopen()=temp{d->
        val s=start(d);cache(s).save("sample",mapOf("a" to {it.writeText("valid")}),Cancel());cache(s).load("sample",Cancel())!!.files.getValue("a").writeText("x")
        fails<IllegalArgumentException>{cache(EditSession(d)).load("sample",Cancel())}
    }
    @Test fun changedPhotoParametersOrAlgorithmCannotReuseOrCommitOldSteps()=temp{d->
        val s=start(d)
        for(newKey in listOf("new photo","new aperture","new focus","new style","new size","new subject","new algorithm")){
            s.beginRender(key);val old=cache(s);old.save("sample",mapOf("a" to {it.writeText("old")}),Cancel());s.beginRender(newKey)
            fails<CancellationException>{old.load("sample",Cancel())};fails<CancellationException>{old.save("sample",mapOf("a" to {it.writeText("stale")}),Cancel())}
            assertNull(RenderStepCache(s.store,newKey,"sdr").load("sample",Cancel()));protected(s)
        }
    }
    @Test fun newImportDiscardsOldStepsButOldWorkerCannotPublish()=temp{d->
        val s=start(File(d,"saved"));val old=cache(s);old.save("sample",mapOf("a" to {it.writeText("old")}),Cancel());val source=File(d,"new-source").apply{writeText("new original")};s.importSource(source)
        fails<CancellationException>{old.load("sample",Cancel())};fails<CancellationException>{old.save("sample",mapOf("a" to {it.writeText("stale")}),Cancel())};assertEquals("new original",s.source()!!.readText())
    }
    @Test fun sdrAndHdrVariantsCannotConsumeEachOthersSteps()=temp{d->
        val s=start(d);cache(s,false).save("sample",mapOf("a" to {it.writeText("sdr")}),Cancel());assertNull(cache(s,true).load("sample",Cancel()))
        cache(s,true).save("sample",mapOf("a" to {it.writeText("hdr")}),Cancel());assertEquals("sdr",cache(s,false).load("sample",Cancel())!!.files.getValue("a").readText())
    }
    @Test fun bytePlanesSurviveReopenWithoutQuantisation()=temp{d->
        val s=start(d);val p=photo();cache(s).savePlanes("pixels",p,Cancel());val q=cache(EditSession(d)).planes("pixels",Cancel())!!
        for(c in 0..2)assertArrayEquals(p.plane(c),q.plane(c))
    }
    @Test fun resumedHdrMustNotOverwriteExistingTarget()=temp{d->
        val s=start(File(d,"saved"));render(d,s,true);val target=File(d,"keep").apply{writeText("KEEP")}
        fails<IllegalArgumentException>{FourTileBokeh.renderNatural(photo(),depth(),.38f,.95f,Cancel(),d,subjectMask=subject(),sharp=HdrPixels.of(sharp(photo())),hdrTarget=target,checkpoint=cache(EditSession(s.store.directory),true))}
        assertEquals("KEEP",target.readText());protected(s)
    }
    @Test fun completeResultAtomicallyClearsDescriptorsAsWellAsBlobs()=temp{d->
        val s=start(d);cache(s).save("sample",mapOf("a" to {it.writeText("saved step")}),Cancel())
        s.completeRender(key,mapOf("job.kind" to ""),mapOf("result" to {it.writeText("new complete result")}),Cancel())
        assertEquals("new complete result",s.store.file("result")!!.readText());assertEquals("immutable original",s.source()!!.readText());assertTrue(s.store.properties().keys.none{it.startsWith("render.")});assertTrue(s.store.keys().none{it.startsWith("render.")})
        s.beginRender(key);assertNull(cache(s).load("sample",Cancel()))
    }
    @Test fun cancelledResultCommitKeepsOriginalResultAndRecoverableSteps()=temp{d->
        val s=start(d);cache(s).save("sample",mapOf("a" to {it.writeText("saved step")}),Cancel());val c=Cancel()
        fails<CancellationException>{s.completeRender(key,emptyMap(),mapOf("result" to {it.writeText("partial");c.cancelled=true}),c)}
        protected(s);assertNotNull(cache(EditSession(d)).load("sample",Cancel()))
    }
    @Test fun staleResultCannotReplaceNewerJob()=temp{d->val s=start(d);s.beginRender("new-key");fails<CancellationException>{s.completeRender(key,emptyMap(),mapOf("result" to {it.writeText("stale")}),Cancel())};protected(s);assertEquals("new-key",s.store.get("render.key"))}
    @Test fun explicitRestartKeepsSourceAndPreviousFullResult()=temp{d->val s=start(d);cache(s).save("sample",mapOf("a" to {it.writeText("saved step")}),Cancel());s.clearRender();protected(s);assertTrue(s.store.properties().keys.none{it.startsWith("render.")});assertTrue(s.store.keys().none{it.startsWith("render.")})}
    @Test fun storagePreflightFailsRatherThanChangingOutputOrHdr()=temp{d->val s=start(d);fails<IOException>{HdrFloatFile.requireSpace(File(d,"space"),Long.MAX_VALUE)};protected(s)}
    @Test fun focusedAndDarkNoLightPathsAlsoRemainRecoverable()=temp{d->
        for(hdr in listOf(false,true))for(focused in listOf(false,true)){
            val p=photo();for(c in 0..2)p.plane(c).fill(45);val dep=if(focused)Frame(3,3,1,FloatArray(9){.38f})else depth();val s=start(File(d,"saved-$hdr-$focused"))
            val a=render(d,null,hdr,p=p,dep=dep,mask=null);val b=render(d,s,hdr,p=p,dep=dep,mask=null);val c=render(d,EditSession(s.store.directory),hdr,p=p,dep=dep,mask=null);equal(a,b);equal(a,c)
        }
    }
}
