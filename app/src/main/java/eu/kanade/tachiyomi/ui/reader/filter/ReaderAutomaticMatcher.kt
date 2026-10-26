package eu.kanade.tachiyomi.ui.reader.filter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Optional comparisons cannot own a display stream or an unbounded chapter queue. */
internal class ReaderAutomaticMatcher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val byteLimit: Int = 16 * 1024 * 1024,
    private val isCurrent: (String) -> Boolean,
    private val onChanged: () -> Unit,
    private val evaluate: (ByteArray) -> Result,
) {
    data class Result(val rendered: ByteArray? = null, val hidden: Boolean = false)

    private val lock = Any()
    private val pending = LinkedHashMap<String, ByteArray>()
    private val results = LinkedHashMap<String, Result>(64, 0.75f, true)
    private var active: String? = null
    private var worker: Job? = null

    fun ready(key: String): Result? = synchronized(lock) { results[key] }

    fun enqueue(key: String, bytes: ByteArray) = synchronized(lock) {
        if (key in results || key == active || key in pending || bytes.size > byteLimit) return@synchronized
        while (
            pending.isNotEmpty() &&
            (pending.size >= 2 || pending.values.sumOf { it.size } + bytes.size > byteLimit)
        ) {
            pending.remove(pending.keys.first())
        }
        pending[key] = bytes
        if (worker?.isActive == true) return@synchronized
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val next = synchronized(lock) {
                    val entry = pending.entries.firstOrNull()
                    if (entry == null) {
                        active = null
                        worker = null
                        null
                    } else {
                        active = entry.key
                        (entry.key to entry.value).also { pending.remove(entry.key) }
                    }
                } ?: break
                val result = if (isCurrent(next.first)) {
                    runCatching { evaluate(next.second) }.getOrElse { Result() }
                } else {
                    Result()
                }
                var changed = false
                synchronized(lock) {
                    active = null
                    if (isCurrent(next.first)) {
                        val bounded = if ((result.rendered?.size ?: 0) <= byteLimit) result else Result()
                        results[next.first] = bounded
                        while (
                            results.size > 64 ||
                            results.values.sumOf { it.rendered?.size?.toLong() ?: 0 } > byteLimit
                        ) {
                            results.remove(results.keys.first())
                        }
                        changed = bounded.rendered != null
                    }
                }
                if (changed) onChanged()
            }
        }
        worker = job
        job.start()
    }
}
