package xyz.shuncongci.leica

/** Serialize cancellation with native-handle close. Volatile alone cannot prevent use-after-close. */
internal class ActiveNativeRun<T:AutoCloseable> {
    private var current:T?=null
    @Synchronized fun attach(value:T){check(current==null);current=value}
    @Synchronized fun cancel(action:(T)->Unit){current?.let(action)}
    @Synchronized fun release(value:T){
        check(current===value)
        current=null
        value.close()
    }
}

/** Cleanup all native resources, preserving the first error and all subsequent close failures. */
internal fun closeNativeResources(resources:Iterable<AutoCloseable>){
    var failure:Throwable?=null
    for(resource in resources)try { resource.close() } catch(t:Throwable){
        val first=failure
        if(first==null)failure=t else if(first!==t)first.addSuppressed(t)
    }
    failure?.let{throw it}
}
