package xyz.shuncongci.leica

import java.util.Locale

/** Display units only. No admission control, budget, size limit or automatic abort. */
object MemoryMetrics {
    const val MIB = 1024L * 1024L
    fun mib(bytes: Long): String = String.format(Locale.US,"%.0f",bytes.toDouble()/MIB)
    fun rssBytes(statusLine: String?): Long = statusLine?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024) ?: -1L
}
