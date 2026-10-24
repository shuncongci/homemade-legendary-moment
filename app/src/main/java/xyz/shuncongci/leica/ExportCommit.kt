package xyz.shuncongci.leica

/** A pending destination becomes visible only after encode + cancellation check + publish succeed.
 * Fatal errors still propagate, but the newly created destination is cleaned up on every failure. */
object ExportCommit {
    fun <T> run(cancel:Cancel,create:()->T,write:(T)->Unit,publish:(T)->Unit,rollback:(T)->Unit):T {
        cancel.check();val item=create()
        try {cancel.check();write(item);cancel.check();publish(item);return item}
        catch(t:Throwable){try{rollback(item)}catch(cleanup:Throwable){if(cleanup!==t)t.addSuppressed(cleanup)};throw t}
    }
}
