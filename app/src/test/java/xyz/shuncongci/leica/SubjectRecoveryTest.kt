package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

/** Real durable blobs + UI state, not a saved completion-count shortcut. */
class SubjectRecoveryTest {
    private val a=Point(.2f,.4f);private val b=Point(.5f,.4f);private val d=Point(.8f,.4f)
    private val focus=Point(.5f,.6f);private val points=listOf(a,b,d)
    private val identity=EditSession.subjectIdentity(2048,focus)
    private fun flow()=PhotoFlow().apply{photoLoaded();selectProcessingMode(ProcessingMode.BLUR_ONLY);picked(this@SubjectRecoveryTest.focus);toggleSubject();chooseSubjects();points.forEach{addSubjectPoint(it)};endSubjectPicking()}
    private fun mask(p:Point,w:Int=31,h:Int=23)=Frame(w,h,1,FloatArray(w*h){i->val k=SubjectSelectionMath.index(p,w,h);if(kotlin.math.abs(i%w-k%w)<=2 && kotlin.math.abs(i/w-k/w)<=2)1f else 0f})
    private fun cache(s:EditSession)=SubjectPointCache(load={s.frame(s.subjectKey(it),Cancel())},save={p,f->s.putFrame(s.subjectKey(p),f,Cancel())})
    private fun select(s:EditSession,c:SubjectPointCache,ps:List<Point> = points,id:String=identity){s.selectSubjects(ps,2048,focus);c.select("photo",ps,id)}
    private fun complete(c:SubjectPointCache,ps:List<Point> = points,calls:MutableList<Point> = mutableListOf())=c.resolve(true,"photo",ps,Cancel()){missing,commit->missing.forEach{calls+=it;commit(it,mask(it))}}
    private fun <T> temp(body:(File)->T):T {val dir=Files.createTempDirectory("subject-recovery-").toFile();try{return body(dir)}finally{check(dir.deleteRecursively())}}
    private inline fun <reified T:Throwable> fails(body:()->Unit){try{body();fail("expected ${T::class.java.simpleName}")}catch(t:Throwable){if(t !is T)throw t}}
    @Test fun threePointsExitContinueCompletesOnlyMissingAndUnlocksGenerate()=temp{dir->
        val s=EditSession(dir);s.store.update(blobs=mapOf("source" to {it.writeText("immutable source")}));val f=flow();s.ui(f.snapshot());val c=cache(s);select(s,c);s.job("subject")
        fails<CancellationException>{c.resolve(true,"photo",points,Cancel()){_,commit->commit(a,mask(a));throw CancellationException("left during second point")}}
        val next=EditSession(dir);val restored=PhotoFlow().apply{restore(next.ui())};val read=cache(next);select(next,read)
        val ticket=restored.subjectTicket();restored.acceptSubjectCompletion(ticket,read.completed("photo",points));assertEquals(1,restored.subjectCompletedCount);assertFalse(restored.canGenerate)
        assertFalse(next.finishSubjects(points,identity,read.completed("photo",points)));assertEquals("subject",next.store.get("job.kind"))
        val calls=mutableListOf<Point>();complete(read,calls=calls);assertEquals(listOf(b,d),calls)
        val done=read.completed("photo",points);assertTrue(next.finishSubjects(points,identity,done));assertEquals("",next.store.get("job.kind"))
        restored.acceptSubjectCompletion(ticket,done);val work=SubjectPreviewWork();work.request(ticket);work.publish(ticket,done,read.cached("photo",points,Cancel())!=null)
        assertTrue(restored.subjectReady);assertEquals(3,restored.subjectCompletedCount);assertTrue(restored.canGenerate);assertFalse(work.needs(ticket));assertTrue(work.isPresented(ticket))
    }
    @Test fun deathAfterAllCommitsBeforeUiCallbackNeedsNoInference()=temp{dir->
        val s=EditSession(dir);val c=cache(s);select(s,c);s.job("subject");complete(c)
        val next=EditSession(dir);val restored=cache(next);select(next,restored)
        restored.resolve(true,"photo",points,Cancel()){_,_->error("must use persisted alphas")}
        assertTrue(next.finishSubjects(points,identity,restored.completed("photo",points)));assertEquals("",next.store.get("job.kind"))
    }
    @Test fun presentationFailureDoesNotUndoDurableCompletion()=temp{dir->
        val s=EditSession(dir);val c=cache(s);select(s,c);s.job("subject");complete(c)
        val f=flow();val t=f.subjectTicket();val work=SubjectPreviewWork();work.request(t);f.acceptSubjectCompletion(t,c.completed("photo",points));work.abort()
        assertTrue(f.subjectReady);assertFalse(work.isPresented(t));assertTrue(s.finishSubjects(points,identity,c.completed("photo",points)));assertTrue(f.canGenerate)
    }
    @Test fun staleCacheOnlyReadCannotPruneNewSelection()=temp{dir->
        val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);val files=s.store.keys();val before=c.completed("photo",points)
        assertFalse(c.matches("photo",listOf(a),identity));assertEquals(files,s.store.keys());assertEquals(before,c.completed("photo",points));assertTrue(c.matches("photo",points,identity))
    }
    @Test fun staleFocusReadCannotSwitchCurrentContext()=temp{dir->val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);assertFalse(c.matches("photo",points,EditSession.subjectIdentity(2048,a)));assertEquals(points,c.completed("photo",points))}
    @Test fun staleResolutionReadCannotSwitchCurrentContext()=temp{dir->val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);assertFalse(c.matches("photo",points,EditSession.subjectIdentity(720,focus)));assertEquals(points,c.completed("photo",points))}
    @Test fun focusChangeDropsMemoryAndDiskBeforeReady()=temp{dir->
        val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);s.selectSubjects(points,2048,a);c.select("photo",points,EditSession.subjectIdentity(2048,a));assertTrue(c.completed("photo",points).isEmpty());assertTrue(s.store.keys().isEmpty())
    }
    @Test fun resolutionChangeDropsMemoryAndDiskBeforeReady()=temp{dir->
        val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);s.selectSubjects(points,720,focus);c.select("photo",points,EditSession.subjectIdentity(720,focus));assertTrue(c.completed("photo",points).isEmpty());assertFalse(c.contains("photo",points))
    }
    @Test fun movedPointOnlyComputesReplacementAfterRestart()=temp{dir->
        val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);val moved=Point(.9f,.7f);val nextPoints=listOf(a,moved,d);val next=EditSession(dir);val read=cache(next);select(next,read,nextPoints)
        val calls=mutableListOf<Point>();complete(read,nextPoints,calls);assertEquals(listOf(moved),calls);assertNull(next.store.file(next.subjectKey(b)))
    }
    @Test fun arbitraryRemovalDoesNotInferAfterRestart()=temp{dir->val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);val next=EditSession(dir);val read=cache(next);select(next,read,listOf(a,d));complete(read,listOf(a,d));assertEquals(listOf(a,d),read.completed("photo",listOf(a,d)));assertNull(next.store.file(next.subjectKey(b)))}
    @Test fun staleSubsetCannotClearNewerPointJob()=temp{dir->val s=EditSession(dir);s.selectSubjects(points,2048,focus);s.job("subject");assertFalse(s.finishSubjects(listOf(a),identity,listOf(a)));assertEquals("subject",s.store.get("job.kind"))}
    @Test fun staleIdentityCannotClearNewerFocusJob()=temp{dir->val s=EditSession(dir);s.selectSubjects(points,2048,a);s.job("subject");assertFalse(s.finishSubjects(points,identity,points));assertEquals("subject",s.store.get("job.kind"))}
    @Test fun subjectCompletionCannotClearRenderJob()=temp{dir->val s=EditSession(dir);s.selectSubjects(points,2048,focus);s.job("render");assertFalse(s.finishSubjects(points,identity,points));assertEquals("render",s.store.get("job.kind"))}
    @Test fun partialCompletionCannotSettleJob()=temp{dir->val s=EditSession(dir);s.selectSubjects(points,2048,focus);s.job("subject");assertFalse(s.finishSubjects(points,identity,listOf(a,b)));assertEquals("subject",s.store.get("job.kind"))}
    @Test fun emptySelectionCannotSettleJob()=temp{dir->val s=EditSession(dir);s.selectSubjects(emptyList(),2048,focus);s.job("subject");assertFalse(s.finishSubjects(emptyList(),identity,emptyList()));assertEquals("subject",s.store.get("job.kind"))}
    @Test fun conditionalCommitUsesLatestManifestAcrossInstances()=temp{dir->val old=LatestCheckpoint(dir);old.update(mapOf("job.kind" to "subject"));val next=LatestCheckpoint(dir);next.update(mapOf("job.kind" to "render"));assertFalse(old.updateIf(mapOf("job.kind" to "subject"),mapOf("job.kind" to "")));assertEquals("render",next.get("job.kind"))}
    @Test fun mixedPersistedDimensionsAreNotAdvertisedAsComplete(){val c=SubjectPointCache(load={mask(it,if(it==a)31 else 33)});fails<IllegalArgumentException>{c.hydrate("photo",listOf(a,b))};assertEquals(listOf(a),c.completed("photo",listOf(a,b)));assertFalse(c.contains("photo",listOf(a,b)))}
    @Test fun clearedCacheHasNoMatchingSelection(){val c=SubjectPointCache();c.select("photo",points,identity);c.clear();assertFalse(c.matches("photo",points,identity));assertNull(c.selectionIdentity("photo",points))}
    @Test fun uiSnapshotAloneNeverClaimsModelsCompleted(){val f=flow();f.subjectCompleted();val restored=PhotoFlow().apply{restore(f.snapshot())};assertFalse(restored.subjectReady);assertEquals(0,restored.subjectCompletedCount);assertFalse(restored.canGenerate)}
    @Test fun disablingAndReopeningDoesNotErasePersistedMasks()=temp{dir->val s=EditSession(dir);val c=cache(s);select(s,c);complete(c);val f=flow();f.acceptSubjectCompletion(f.subjectTicket(),c.completed("photo",points));f.toggleSubject();f.toggleSubject();assertTrue(f.subjectReady);val next=cache(EditSession(dir));next.select("photo",points,identity);assertEquals(points,next.completed("photo",points))}
}
