package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

class LatestCheckpointTest {
    private fun temp(test:(File)->Unit){val dir=Files.createTempDirectory("latest-checkpoint-").toFile();try{test(dir)}finally{dir.deleteRecursively()}}
    private fun reject(block:()->Unit){var failed=false;try{block()}catch(e:Exception){failed=true};assertTrue("must reject",failed)}
    @Test fun restartReadsLatestAndDoesNotKeepHistory()=temp{dir->
        val s=LatestCheckpoint(dir);repeat(8){i->s.update(mapOf("step" to "$i"),blobs=mapOf("pixels" to {it.writeText("pixels-$i")}))}
        val reopened=LatestCheckpoint(dir);assertEquals("7",reopened.get("step"));assertEquals("pixels-7",reopened.file("pixels")!!.readText());assertEquals(2,dir.listFiles()!!.size)
    }
    @Test fun beforeCommitFailurePreservesPrevious()=temp{dir->
        LatestCheckpoint(dir).update(mapOf("step" to "old"),blobs=mapOf("pixels" to {it.writeText("old")}))
        val s=LatestCheckpoint(dir){if(it=="before-manifest")error("kill")}
        reject{s.update(mapOf("step" to "new"),blobs=mapOf("pixels" to {it.writeText("new")}))}
        val reopened=LatestCheckpoint(dir);assertEquals("old",reopened.get("step"));assertEquals("old",reopened.file("pixels")!!.readText());assertEquals(2,dir.listFiles()!!.size)
    }
    @Test fun afterCommitFailureRetainsNewCompleteState()=temp{dir->
        LatestCheckpoint(dir).update(blobs=mapOf("pixels" to {it.writeText("old")}))
        val s=LatestCheckpoint(dir){if(it=="after-manifest")error("kill")}
        reject{s.update(blobs=mapOf("pixels" to {it.writeText("new")}))}
        assertEquals("new",LatestCheckpoint(dir).file("pixels")!!.readText());assertEquals(2,dir.listFiles()!!.size)
    }
    @Test fun partialBlobFailureDoesNotCommit()=temp{dir->
        val s=LatestCheckpoint(dir);s.update(blobs=mapOf("pixels" to {it.writeText("old")}))
        reject{s.update(blobs=mapOf("pixels" to {it.writeText("partial");error("disk full")}))}
        assertEquals("old",LatestCheckpoint(dir).file("pixels")!!.readText())
    }
    @Test fun independentInstancesMergeMetadataInsteadOfRollingBack()=temp{dir->
        val a=LatestCheckpoint(dir);val b=LatestCheckpoint(dir);a.update(mapOf("stage" to "4"));b.update(mapOf("ui" to "new"));assertEquals("4",b.get("stage"));assertEquals("new",a.get("ui"))
    }
    @Test fun sameLengthCorruptionIsDetectedOnReopen()=temp{dir->
        val s=LatestCheckpoint(dir);s.update(blobs=mapOf("pixels" to {it.writeText("old")}));s.file("pixels")!!.writeText("bad");reject{LatestCheckpoint(dir).file("pixels")}
    }
    @Test fun corruptManifestIsKeptUntilExplicitImport()=temp{dir->
        val s=LatestCheckpoint(dir);s.update(blobs=mapOf("source" to {it.writeText("old")}));val old=s.file("source")!!
        File(dir,"latest.properties").appendText("\njob.kind=forged\n")
        val reopened=LatestCheckpoint(dir);reject{reopened.properties()};assertTrue(old.exists())
        reopened.reset(mapOf("job.kind" to ""),mapOf("source" to {it.writeText("new")}));assertEquals("new",reopened.file("source")!!.readText());assertFalse(old.exists())
    }
    @Test fun pruningDoesNotDeleteUnownedFiles()=temp{dir->
        val external=File(dir,"user-photo.png").apply{writeText("keep")};LatestCheckpoint(dir).update(mapOf("step" to "1"));assertTrue(external.exists())
    }
    @Test fun floatCheckpointIsBitExact()=temp{dir->
        val f=Frame(3,2,1,floatArrayOf(0f,-0f,Float.MIN_VALUE,.12345678f,1f,1000f));val path=File(dir,"float")
        CheckpointPixels.frame(path,f,Cancel());val read=CheckpointPixels.frame(path,Cancel());assertArrayEquals(f.data.map{it.toRawBits()}.toIntArray(),read.data.map{it.toRawBits()}.toIntArray())
    }
    @Test fun onlyUnfinishedSubjectPointsComputeAfterRestart()=temp{dir->
        val a=Point(.2f,.5f);val b=Point(.8f,.5f);val points=listOf(a,b)
        fun cache(s:EditSession)=SubjectPointCache(load={s.frame(s.subjectKey(it),Cancel())},save={p,f->s.putFrame(s.subjectKey(p),f,Cancel())})
        val s=EditSession(dir);s.selectSubjects(points,2048,Point(.5f,.5f))
        reject{cache(s).resolve(true,"photo",points,Cancel()){_,commit->commit(a,Frame(9,9,1,FloatArray(81){1f}));error("killed before b")}}
        val next=EditSession(dir);val c=cache(next);var calls=0
        c.resolve(true,"photo",points,Cancel()){missing,commit->assertEquals(listOf(b),missing);calls++;commit(b,Frame(9,9,1,FloatArray(81){1f}))}
        assertEquals(1,calls);assertEquals(points,c.completed("photo",points))
        next.selectSubjects(listOf(b),2048,Point(.5f,.5f));assertEquals(setOf(next.subjectKey(b)),next.store.keys())
        next.selectSubjects(listOf(b),720,Point(.5f,.5f));assertTrue(next.store.keys().isEmpty())
    }
    @Test fun failedPersistenceNeverAdvertisesSubjectCompletion(){
        val p=Point(.5f,.5f);val c=SubjectPointCache(save={_,_->error("disk full")})
        reject{c.resolve(true,"photo",listOf(p),Cancel()){_,commit->commit(p,Frame(3,3,1,FloatArray(9){1f}))}}
        assertTrue(c.completed("photo",listOf(p)).isEmpty())
    }
    @Test fun resolutionsNeverUpscale(){
        for(r in SubjectResolution.entries){val size=SubjectProtectionMath.analysisSize(4000,3000,r.longEdge);assertEquals(r.longEdge,size.first);assertEquals(100 to 80,SubjectProtectionMath.analysisSize(100,80,r.longEdge))}
        assertEquals(2048,SubjectResolution.HIGH.longEdge)
    }
    @Test fun restoreDoesNotUnlockUiBeforeOperationFinishes(){
        val original=PhotoFlow().apply{photoLoaded();selectSubjectResolution(SubjectResolution.LOW);generated()}
        val restored=PhotoFlow();restored.begin(PhotoFlow.Operation.PREVIEW);restored.restore(original.snapshot())
        assertTrue(restored.busy);assertTrue(restored.hasResult);assertEquals(SubjectResolution.LOW,restored.subjectResolution);restored.finish();assertFalse(restored.busy)
    }
    @Test fun removedAndReaddedPointIsNotFalselyCompleted(){
        val p=Point(.5f,.5f);val flow=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);picked(p);toggleSubject();chooseSubjects();addSubjectPoint(p);endSubjectPicking();subjectCompleted()}
        assertTrue(flow.subjectReady);flow.removeSubjectPoint(p);flow.chooseSubjects();flow.addSubjectPoint(p);flow.endSubjectPicking();assertFalse(flow.subjectReady);assertEquals(1,flow.subjectPendingCount)
    }
    @Test fun multiscaleResumesWithoutRepeatingCompletedInference()=temp{dir->
        val w=41;val h=27;val p=Planes(w,h,ByteArray(w*h){30},ByteArray(w*h){40},ByteArray(w*h){50})
        val coc=Frame(w,h,1,FloatArray(w*h){20f});val scales=floatArrayOf(1f,2f,3f);var baselineCalls=0
        fun infer(rgb:Frame)=Frame(rgb.w,rgb.h,3,FloatArray(rgb.data.size){.2f})
        val expected=LegendaryMath.renderCoc(p,coc,2.8f,Cancel(),globalScales=scales){rgb,_->baselineCalls++;infer(rgb)}
        val session=EditSession(dir);var firstCalls=0
        reject{LegendaryMath.renderCoc(p,coc,2.8f,Cancel(),globalScales=scales,checkpoint=session.renderCheckpoint(),onPass={error("process killed after saved pass")}){rgb,_->firstCalls++;infer(rgb)}}
        var resumedCalls=0
        val actual=LegendaryMath.renderCoc(p,coc,2.8f,Cancel(),globalScales=scales,checkpoint=EditSession(dir).renderCheckpoint()){rgb,_->resumedCalls++;infer(rgb)}
        assertEquals(3,baselineCalls);assertEquals(1,firstCalls);assertEquals(2,resumedCalls);for(ch in 0..2)assertArrayEquals(expected.plane(ch),actual.plane(ch))
    }
}
