package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class DevelopTransferTest {
    @Test fun normalAdoptionDoesNotReleaseUiResult(){var releases=0;val t=DevelopTransfer<Any>{releases++};val value=Any();t.offer(value);assertSame(value,t.take());t.close();assertEquals(0,releases)}
    @Test fun cancelledAfterWorkerReturnsReleasesOnce(){var releases=0;val t=DevelopTransfer<Any>{releases++};t.offer(Any());t.close();t.close();assertNull(t.take());assertEquals(1,releases)}
    @Test fun cancelledBeforeWorkerReturnsRejectsAndReleases(){var releases=0;val t=DevelopTransfer<Any>{releases++};t.close();t.offer(Any());assertNull(t.take());assertEquals(1,releases)}
    @Test fun failureWithoutResultIsHarmless(){var releases=0;val t=DevelopTransfer<Any>{releases++};t.close();assertEquals(0,releases)}
    @Test fun racingCancelAndWorkerNeverLeaksOrDoubleReleases(){repeat(100){val n=AtomicInteger();val t=DevelopTransfer<Any>{n.incrementAndGet()};val gate=CountDownLatch(1);val worker=Thread{gate.await();t.offer(Any())};worker.start();gate.countDown();t.close();worker.join();t.close();assertNull(t.take());assertEquals(1,n.get())}}
    @Test(expected=IllegalStateException::class) fun unadoptedResultCannotBeOverwritten(){val t=DevelopTransfer<Any>{};t.offer(Any());try{t.offer(Any())}finally{t.close()}}
}
