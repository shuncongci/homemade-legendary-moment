package xyz.shuncongci.leica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One frame in flight, <=8 lanes, even row ownership. A failed/cancelled frame still joins
 * EVERY accepted runnable before returning: Future.cancel/isDone cannot prove native exit.
 * No codec, Image, LUT lifetime or ByteBuffer position is owned by a worker.
 */
internal class VideoFrameWorkers(
    val parallelism:Int=MAX_WORKERS,
    executor:ExecutorService?=null
):AutoCloseable {
    init { require(parallelism in 1..MAX_WORKERS) }
    private val poolValue=lazy { executor ?: Executors.newFixedThreadPool(parallelism) { r ->
        Thread(r,"video-grade").apply { isDaemon=true }
    } }
    private var closed=false

    @Synchronized
    fun frame(height:Int,check:()->Unit,render:(lane:Int,start:Int,rows:Int)->Unit) {
        kotlin.check(!closed) { "Video workers already closed" }
        val bands=partition(height,parallelism)
        check()
        val stopped=AtomicBoolean(false)
        val failure=AtomicReference<Throwable?>(null)
        val exits=CountDownLatch(bands.size)
        fun fail(t:Throwable) { failure.compareAndSet(null,t);stopped.set(true) }
        var accepted=0
        try {
            for((lane,band) in bands.withIndex()) {
                poolValue.value.execute {
                    try {
                        var row=band.start
                        while(row<band.end && !stopped.get()) {
                            check()
                            if(stopped.get())break
                            val rows=minOf(STRIPE_ROWS,band.end-row)
                            render(lane,row,rows)
                            check()
                            row+=rows
                        }
                    } catch(t:Throwable) { fail(t) }
                    finally { exits.countDown() }
                }
                accepted++
            }
        } catch(t:Throwable) {
            fail(t)
            repeat(bands.size-accepted) { exits.countDown() }
        }
        var interrupted=false
        // Cancellation/interrupt signals stop future stripes, not the lifetime barrier.
        while(exits.count>0) {
            try {
                if(!exits.await(20,TimeUnit.MILLISECONDS)) {
                    try { check() } catch(t:Throwable) { fail(t) }
                }
            } catch(t:InterruptedException) { interrupted=true;fail(t) }
        }
        if(interrupted)Thread.currentThread().interrupt()
        failure.get()?.let { throw it }
        check()
    }

    @Synchronized
    override fun close() {
        if(closed)return
        closed=true
        if(!poolValue.isInitialized())return
        val pool=poolValue.value
        pool.shutdown() // never interrupt a JNI operation or discard a latch-owning runnable
        var interrupted=false
        while(!pool.isTerminated) {
            try { pool.awaitTermination(1,TimeUnit.DAYS) }
            catch(t:InterruptedException) { interrupted=true }
        }
        if(interrupted)Thread.currentThread().interrupt()
    }

    data class Band(val start:Int,val end:Int)
    companion object {
        const val MAX_WORKERS=8
        const val STRIPE_ROWS=16
        fun partition(height:Int,workers:Int=MAX_WORKERS):List<Band> {
            require(height>=2 && height%2==0)
            require(workers in 1..MAX_WORKERS)
            val pairs=height/2
            val count=minOf(workers,pairs)
            return (0 until count).map { i ->
                Band((pairs.toLong()*i/count*2).toInt(),(pairs.toLong()*(i+1)/count*2).toInt())
            }
        }
    }
}
