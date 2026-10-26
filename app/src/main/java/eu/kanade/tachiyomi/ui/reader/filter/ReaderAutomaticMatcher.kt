package eu.kanade.tachiyomi.ui.reader.filter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    data class Result(val rendered: ByteArray? = null, val hidden: Boolean = false, val complete: Boolean = true)

    private val lock = Any()
    private data class Request(val bytes: ByteArray, val foreground: Boolean)
    private val pending = LinkedHashMap<String, Request>()
    private val results = LinkedHashMap<String, Result>(64, 0.75f, true)
    private var active: String? = null
    private var worker: Job? = null

    fun ready(key: String): Result? = synchronized(lock) { results[key] }

    fun isBusy(key: String): Boolean = synchronized(lock) { key == active || key in pending }

    fun remember(key: String, result: Result) = synchronized(lock) {
        if (result.complete && (result.rendered?.size ?: 0) <= byteLimit && isCurrent(key)) {
            results[key] = result
            trimResults()
        }
    }

    private fun trimResults() {
        while (results.size > 64 || results.values.sumOf { it.rendered?.size?.toLong() ?: 0 } > byteLimit) {
            results.remove(results.keys.first())
        }
    }

    fun enqueue(key: String, bytes: ByteArray, foreground: Boolean = true) = synchronized(lock) {
        if (key in results || key == active || bytes.size > byteLimit) return@synchronized
        pending[key]?.let {
            if (foreground && !it.foreground) pending[key] = it.copy(foreground = true)
            return@synchronized
        }
        // Download prechecks cannot evict or jump ahead of waiting visible pages.
        if (!foreground && pending.values.any { it.foreground }) return@synchronized
        while (
            pending.isNotEmpty() &&
            (pending.size >= 2 || pending.values.sumOf { it.bytes.size } + bytes.size > byteLimit)
        ) {
            val oldestBackground = pending.entries.firstOrNull { !it.value.foreground }?.key
            if (!foreground && oldestBackground == null) return@synchronized
            pending.remove(oldestBackground ?: pending.keys.first())
        }
        pending[key] = Request(bytes, foreground)
        if (worker?.isActive == true) return@synchronized
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                val next = synchronized(lock) {
                    val entry = pending.entries.firstOrNull { it.value.foreground } ?: pending.entries.firstOrNull()
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
                    try { evaluate(next.second.bytes) } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Result(complete = false)
                    }
                } else {
                    Result(complete = false)
                }
                currentCoroutineContext().ensureActive()
                var changed = false
                synchronized(lock) {
                    active = null
                    if (isCurrent(next.first) && result.complete) {
                        val bounded = if ((result.rendered?.size ?: 0) <= byteLimit) {
                            result
                        } else {
                            Result(
                                complete = false,
                            )
                        }
                        if (bounded.complete) {
                            results[next.first] = bounded
                            trimResults()
                            changed = bounded.rendered != null
                        }
                    }
                }
                if (changed) onChanged()
                }
            } finally {
                synchronized(lock) {
                    if (worker === owner) {
                        active = null
                        worker = null
                        pending.clear()
                    }
                }
            }
        }
        worker = job
        job.start()
    }
}
