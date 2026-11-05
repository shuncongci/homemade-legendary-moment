package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class VideoFrameWorkersTest {
    private fun rejects(block:()->Unit) { try { block();fail("Expected rejection") } catch(e:IllegalArgumentException){} }
    private fun cover(height:Int,workers:Int=8) {
        val bands=VideoFrameWorkers.partition(height,workers)
        assertEquals(minOf(workers,height/2),bands.size)
        var next=0
        for(b in bands){assertEquals(next,b.start);assertEquals(0,b.start%2);assertEquals(0,b.end%2);assertTrue(b.end>b.start);next=b.end}
        assertEquals(height,next)
        assertTrue(bands.maxOf{it.end-it.start}-bands.minOf{it.end-it.start}<=2)
    }
    @Test fun twoRows()=cover(2)
    @Test fun fourRows()=cover(4)
    @Test fun fourteenRows()=cover(14)
    @Test fun sixteenRows()=cover(16)
    @Test fun eighteenRows()=cover(18)
    @Test fun fullHd()=cover(1080)
    @Test fun fourK()=cover(2160)
    @Test fun largeNoIntegerOverflow()=cover(Int.MAX_VALUE-1)
    @Test fun allWorkerCounts(){for(w in 1..8)for(h in 2..260 step 2)cover(h,w)}
    @Test fun zeroHeight()=rejects{VideoFrameWorkers.partition(0)}
    @Test fun negativeHeight()=rejects{VideoFrameWorkers.partition(-2)}
    @Test fun oddHeight()=rejects{VideoFrameWorkers.partition(17)}
    @Test fun zeroWorkers()=rejects{VideoFrameWorkers.partition(16,0)}
    @Test fun tooManyWorkers()=rejects{VideoFrameWorkers(9)}
    @Test fun defaultEight(){VideoFrameWorkers().use{assertEquals(8,it.parallelism)}}
    @Test fun stripeLimitIsEven(){assertEquals(16,VideoFrameWorkers.STRIPE_ROWS)}

    @Test(timeout=15000) fun eightSimultaneousDisjointLanes() {
        val entered=CountDownLatch(8);val visits=AtomicIntegerArray(2160);val names=ConcurrentHashMap.newKeySet<Long>()
        VideoFrameWorkers().use{p->p.frame(2160,{}){_,start,rows->
            names.add(Thread.currentThread().id);entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(rows in 2..16 && rows%2==0 && start%2==0)
            for(y in start until start+rows)assertEquals(1,visits.incrementAndGet(y))
        }}
        assertEquals(8,names.size);for(y in 0 until 2160)assertEquals(1,visits.get(y))
    }
    @Test fun smallFrameUsesTwoLanes() {
        val lanes=ConcurrentHashMap.newKeySet<Int>()
        VideoFrameWorkers().use{p->p.frame(4,{}){lane,_,_->lanes.add(lane)}}
        assertEquals(setOf(0,1),lanes)
    }
    @Test fun sequentialFramesRetainOrderAndReusePool() {
        val counts=IntArray(25);val ids=ConcurrentHashMap.newKeySet<Long>()
        VideoFrameWorkers().use{p->for(frame in counts.indices){val visits=AtomicInteger(0);p.frame(258,{}){_,_,rows->ids.add(Thread.currentThread().id);visits.addAndGet(rows)};counts[frame]=visits.get()}}
        assertTrue(counts.all{it==258});assertTrue(ids.size in 1..8)
    }
    @Test fun preCancelledDoesNoWork() {
        val c=Cancel().apply{cancelled=true};val hit=AtomicInteger()
        VideoFrameWorkers().use{p->try{p.frame(64,c::check){_,_,_->hit.incrementAndGet()};fail()}catch(e:CancellationException){}}
        assertEquals(0,hit.get())
    }
    @Test fun invalidFrameDoesNoWork() {
        val hit=AtomicInteger();VideoFrameWorkers().use{p->rejects{p.frame(3,{}){_,_,_->hit.incrementAndGet()}}};assertEquals(0,hit.get())
    }
    @Test fun workerErrorRetainsIdentity() {
        val error=IllegalStateException("synthetic native failure")
        VideoFrameWorkers().use{p->try{p.frame(16,{}){_,_,_->throw error};fail()}catch(t:Throwable){assertSame(error,t)}}
    }
    @Test fun fatalWorkerErrorAlsoJoins() {
        val error=AssertionError("synthetic fatal")
        VideoFrameWorkers().use{p->try{p.frame(64,{}){_,_,_->throw error};fail()}catch(t:Throwable){assertSame(error,t)}}
    }
    @Test fun failureDoesNotPoisonNextFrame() {
        VideoFrameWorkers().use{p->try{p.frame(32,{}){_,_,_->error("fixture")}}catch(e:IllegalStateException){};val rows=AtomicInteger();p.frame(32,{}){_,_,n->rows.addAndGet(n)};assertEquals(32,rows.get())}
    }
    @Test fun closeIsIdempotentAndRejectsFurtherFrames() {
        val p=VideoFrameWorkers();p.close();p.close();try{p.frame(16,{}){_,_,_->fail()};fail()}catch(e:IllegalStateException){}
    }
    @Test fun suppliedPoolShutsDownAfterUse() {
        val executor=Executors.newFixedThreadPool(8)
        VideoFrameWorkers(executor=executor).use{it.frame(32,{}){_,_,_->}}
        assertTrue(executor.isTerminated)
    }
    @Test fun cancellationChecksAfterFinalStripe() {
        val c=Cancel()
        VideoFrameWorkers(1).use{p->try{p.frame(2,c::check){_,_,_->c.cancelled=true};fail()}catch(e:CancellationException){}}
    }
    private fun barrierScenario(mode:String) {
        val p=VideoFrameWorkers();val entered=CountDownLatch(8);val release=CountDownLatch(1);val signalled=CountDownLatch(1)
        val done=CountDownLatch(1);val active=AtomicInteger();val error=AtomicReference<Throwable?>();val interrupted=AtomicBoolean()
        val c=Cancel();val marker=IllegalStateException("native fixture")
        val parent=Thread{
            try{p.frame(512,c::check){lane,_,_->
                active.incrementAndGet()
                try{entered.countDown();check(entered.await(5,TimeUnit.SECONDS));if(mode=="error" && lane==0){signalled.countDown();throw marker};check(release.await(5,TimeUnit.SECONDS))}
                finally{active.decrementAndGet()}
            }}catch(t:Throwable){error.set(t)}finally{interrupted.set(Thread.currentThread().isInterrupted);done.countDown()}
        }
        parent.start()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            when(mode){"cancel"->c.cancelled=true;"interrupt"->parent.interrupt();else->assertTrue(signalled.await(5,TimeUnit.SECONDS))}
            assertFalse("must retain Image/LUT while JNI is still in flight",done.await(100,TimeUnit.MILLISECONDS))
            assertTrue(active.get()>0)
        } finally { release.countDown();parent.join(6000);p.close() }
        assertEquals(0,active.get());assertEquals(0L,done.count)
        when(mode){"cancel"->assertTrue(error.get() is CancellationException);"interrupt"->{assertTrue(error.get() is InterruptedException);assertTrue(interrupted.get())};else->assertSame(marker,error.get())}
    }
    @Test(timeout=15000) fun cancellationWaitsForNativeExit()=barrierScenario("cancel")
    @Test(timeout=15000) fun errorWaitsForOtherNativeCalls()=barrierScenario("error")
    @Test(timeout=15000) fun interruptWaitsAndRestoresFlag()=barrierScenario("interrupt")

    @Test(timeout=15000) fun partialSubmissionFailureJoinsAcceptedTasks() {
        val entered=CountDownLatch(3);val reject=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1);val result=AtomicReference<Throwable?>()
        val executor=object:ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>()) {
            var accepted=0
            override fun execute(r:Runnable){if(accepted==3){check(entered.await(5,TimeUnit.SECONDS));reject.countDown();throw RejectedExecutionException("fixture")};super.execute(r);accepted++}
        }
        val p=VideoFrameWorkers(executor=executor)
        val parent=Thread{try{p.frame(128,{}){_,_,_->entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}catch(t:Throwable){result.set(t)}finally{done.countDown()}}
        parent.start()
        try{assertTrue(reject.await(5,TimeUnit.SECONDS));assertFalse(done.await(100,TimeUnit.MILLISECONDS))}
        finally{release.countDown();parent.join(6000);p.close()}
        assertEquals(0L,done.count);assertTrue(result.get() is RejectedExecutionException);assertTrue(executor.isTerminated)
    }
    @Test fun completeSubmissionFailurePropagates() {
        val executor=Executors.newFixedThreadPool(8).apply{shutdown()}
        VideoFrameWorkers(executor=executor).use{p->try{p.frame(16,{}){_,_,_->fail()};fail()}catch(e:RejectedExecutionException){}}
    }
    @Test fun bufferViewsKeepPositionsIndependent() {
        val base=java.nio.ByteBuffer.allocateDirect(64);base.position(7)
        val views=(0 until 8).map{base.duplicate()}
        VideoFrameWorkers().use{p->p.frame(16,{}){lane,_,_->views[lane].position(lane);views[lane].put(lane,lane.toByte())}}
        assertEquals(7,base.position());for(i in 0..7){assertEquals(i,views[i].position());assertEquals(i.toByte(),base.get(i))}
    }
}
