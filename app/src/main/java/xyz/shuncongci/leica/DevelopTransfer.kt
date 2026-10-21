package xyz.shuncongci.leica

/** Holds a worker result until the UI explicitly adopts it. Cancellation on either side releases once. */
class DevelopTransfer<T:Any>(private val dispose:(T)->Unit):AutoCloseable {
    private var closed=false
    private var value:T?=null
    fun offer(next:T){
        val accepted=synchronized(this){check(value==null);if(closed)false else {value=next;true}}
        if(!accepted)dispose(next)
    }
    fun take():T?=synchronized(this){val result=value;value=null;result}
    override fun close(){
        val orphan=synchronized(this){closed=true;val result=value;value=null;result}
        orphan?.let(dispose)
    }
}
