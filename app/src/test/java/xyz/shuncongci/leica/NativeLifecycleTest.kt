package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeLifecycleTest {
    private class Handle:AutoCloseable {
        var closed=false;var cancels=0
        fun cancel(){check(!closed);cancels++}
        override fun close(){check(!closed);closed=true}
    }
    @Test fun cancelOnlyTouchesLiveHandle(){
        val slot=ActiveNativeRun<Handle>();val h=Handle()
        slot.cancel{fail()};slot.attach(h);slot.cancel{it.cancel()};slot.release(h)
        slot.cancel{fail("closed handle must be detached")}
        assertTrue(h.closed);assertEquals(1,h.cancels)
    }
    @Test fun nextRunDoesNotReuseOldHandle(){
        val slot=ActiveNativeRun<Handle>();val a=Handle();val b=Handle()
        slot.attach(a);slot.release(a);slot.attach(b);slot.cancel{it.cancel()};slot.release(b)
        assertEquals(0,a.cancels);assertEquals(1,b.cancels)
    }
    @Test fun closeWaitsUntilCancellationReturns(){
        val slot=ActiveNativeRun<Handle>();val h=Handle();slot.attach(h)
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val closing=CountDownLatch(1);val closed=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val cancel=Thread { try {slot.cancel{entered.countDown();check(proceed.await(5,TimeUnit.SECONDS));it.cancel()}}catch(t:Throwable){failure.set(t)} }
        val release=Thread { try {closing.countDown();slot.release(h);closed.countDown()}catch(t:Throwable){failure.set(t)} }
        cancel.start()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));release.start();assertTrue(closing.await(5,TimeUnit.SECONDS))
            assertFalse(closed.await(100,TimeUnit.MILLISECONDS))
        }finally{proceed.countDown();cancel.join(5000);if(release.state!=Thread.State.NEW)release.join(5000)}
        assertFalse(cancel.isAlive);assertFalse(release.isAlive);assertNull(failure.get());assertTrue(h.closed)
    }
    @Test fun failedCloseStillDetachesHandle(){
        val slot=ActiveNativeRun<AutoCloseable>();val failure=IllegalStateException("close")
        val h=AutoCloseable{throw failure};slot.attach(h)
        try{slot.release(h);fail()}catch(t:IllegalStateException){assertSame(failure,t)}
        slot.cancel{fail()}
    }
    @Test fun cleanupAttemptsEveryResourceAndPreservesFailures(){
        val first=IllegalStateException("first");val second=IllegalArgumentException("second");var closes=0
        try{closeNativeResources(listOf(AutoCloseable{closes++;throw first},AutoCloseable{closes++;throw second},AutoCloseable{closes++}));fail()}
        catch(t:IllegalStateException){assertSame(first,t);assertArrayEquals(arrayOf<Throwable>(second),t.suppressed)}
        assertEquals(3,closes)
    }
    @Test fun fatalCleanupErrorIsNotSwallowed(){
        val oom=OutOfMemoryError("test");var closed=false
        try{closeNativeResources(listOf(AutoCloseable{throw oom},AutoCloseable{closed=true}));fail()}
        catch(t:OutOfMemoryError){assertSame(oom,t)}
        assertTrue(closed)
    }
}
