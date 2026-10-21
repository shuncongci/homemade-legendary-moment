package xyz.shuncongci.leica

import java.util.Locale

/** API29 is required for excluding NNAPI's CPU/reference device. */
object HardwarePolicy {
    /** ORT 1.28 CPU contrib Gelu is float-only; keep depth's original Erf expression.
     * Transformer names are version-specific (GeluFusionL1/L2, NOT GeluFusion).
     * Apply on every session creation, including a shape change; never change model precision.
     */
    fun geluSessionConfig(preserveGeluPrimitives:Boolean):Map<String,String> =
        if(preserveGeluPrimitives)mapOf(
            "optimization.disable_specified_optimizers" to "GeluFusionL1;GeluFusionL2"
        ) else emptyMap()

    fun requestNnapi(api:Int,forceCpu:Boolean=false)=!forceCpu && api>=29
    fun dimensions(w:Int,h:Int):Map<String,Long>{
        require(w>0 && h>0)
        return linkedMapOf("height" to h.toLong(),"width" to w.toLong())
    }
    /** Only hardware/provider compatibility errors permit CPU creation; never memory/cancellation errors. */
    fun compatibilityFailure(message:String?):Boolean {
        val m=message?.lowercase(Locale.ROOT) ?: return false
        if(listOf("out of memory","out_of_memory","bad_alloc","failed to allocate","memory allocation","resource exhausted","cancel","terminated","interrupted").any{it in m})return false
        return listOf("nnapi","neural networks","execution provider","not implemented","not supported","cannot run using the current set of target devices").any{it in m}
    }
}

/** Exactly one CPU retry; the second failure, fatal causes and cancellation always escape. */
internal fun <T> withCpuRetry(attempt: () -> T, shouldRetry: (Exception) -> Boolean, beforeRetry: () -> Unit): T {
    try { return attempt() } catch (e: Exception) {
        propagateFatalCause(e)
        if (!shouldRetry(e)) throw e
        beforeRetry()
    }
    return attempt()
}
