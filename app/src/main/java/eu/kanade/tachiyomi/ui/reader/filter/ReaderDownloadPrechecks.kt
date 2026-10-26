package eu.kanade.tachiyomi.ui.reader.filter

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

/** Completed local chapters only. Eight resumable cursors, one worker, one <=16 MiB page at a time. */
internal object ReaderDownloadPrechecks {
    private data class Item(
        val uri: String,
        val directory: Boolean,
        var cursor: Int = 0,
        var failures: Int = 0,
        var retryAt: Long = 0,
        var rulesVersion: String = "",
        val generation: String = UUID.randomUUID().toString(),
    )
    private val gate = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = LinkedHashMap<String, Item>()
    private var worker: Job? = null
    private lateinit var context: Context
    private lateinit var index: AtomicFile

    fun initialize(app: Context) = synchronized(gate) {
        if (::context.isInitialized) return@synchronized
        context = app.applicationContext
        index = AtomicFile(File(context.filesDir, "reader-download-prechecks-v1.json"))
        runCatching {
            if (index.baseFile.exists() || File(index.baseFile.path + ".bak").exists()) {
                require(maxOf(index.baseFile.length(), File(index.baseFile.path + ".bak").length()) <= 64 * 1024)
                val data = JSONObject(index.openRead().use { it.reader().readText() })
                require(data.getInt("version") == 1)
                val array = data.getJSONArray("jobs")
                require(array.length() <= 8)
                val restored = (0 until array.length()).map { i ->
                    val row = array.getJSONObject(i)
                    val uri = row.getString("uri")
                    require(uri.length <= 4096 && Uri.parse(uri).scheme in setOf("file", "content"))
                    val version = row.optString("rulesVersion", "")
                    require(version.isEmpty() || version.matches(Regex("[0-9a-f]{64}")))
                    Item(uri, row.getBoolean("directory"), row.getInt("cursor").coerceIn(0, 10000), rulesVersion = version)
                }
                restored.forEach { jobs[it.uri] = it }
            }
        }
        startLocked()
    }

    fun enqueue(uri: Uri, directory: Boolean) = synchronized(gate) {
        if (!::context.isInitialized || uri.scheme !in setOf("file", "content")) return@synchronized
        val value = uri.toString()
        if (value.toByteArray().size > 4096) return@synchronized
        // A new final chapter commit supersedes an older cursor at the same URI.
        jobs[value] = Item(value, directory)
        while (jobs.size > 8) jobs.remove(jobs.keys.first())
        persistLocked()
        wake.trySend(Unit)
        startLocked()
    }

    private fun persistLocked() = runCatching {
        val rows = JSONArray(
            jobs.values.map { item ->
                JSONObject().put("uri", item.uri)
                    .put("directory", item.directory).put("cursor", item.cursor).put("rulesVersion", item.rulesVersion)
            },
        )
        val output = index.startWrite()
        try {
            output.write(JSONObject().put("version", 1).put("jobs", rows).toString().toByteArray())
            index.finishWrite(output)
        } catch (error: Throwable) {
            index.failWrite(output)
            throw error
        }
    }

    private fun startLocked() {
        if (worker?.isActive == true || jobs.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val next = synchronized(gate) {
                    jobs.values.firstOrNull { it.retryAt <= System.currentTimeMillis() }?.copy()
                }
                if (next == null) {
                    val finished = synchronized(gate) {
                        if (jobs.isEmpty()) {
                            worker = null
                            true
                        } else {
                            false
                        }
                    }
                    if (finished) break
                    withTimeoutOrNull(10_000) { wake.receive() }
                    continue
                }
                val completed = try { process(next) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    false
                }
                synchronized(gate) {
                    val current = jobs[next.uri]?.takeIf { it.generation == next.generation }
                    if (completed && current != null) {
                        jobs.remove(next.uri)
                    } else if (current != null) {
                        current.failures = (current.failures + 1).coerceAtMost(10)
                        current.retryAt = System.currentTimeMillis() +
                            minOf(600_000L, 5_000L * (1L shl current.failures))
                    }
                    persistLocked()
                }
            }
        }
        worker = job
        job.start()
    }

    private fun stillQueued(item: Item) = synchronized(gate) { jobs[item.uri]?.generation == item.generation }
    private fun advance(item: Item, next: Int) = synchronized(gate) {
        jobs[item.uri]?.takeIf { it.generation == item.generation }?.cursor = next
        persistLocked()
    }

    private suspend fun process(item: Item): Boolean {
        val version = ReaderImageRules.precheckVersion()
        val cursor = synchronized(gate) {
            val current = jobs[item.uri]?.takeIf { it.generation == item.generation } ?: return false
            if (current.rulesVersion != version) {
                current.cursor = 0
                current.rulesVersion = version
                persistLocked()
            }
            current.cursor
        }
        val root = UniFile.fromUri(context, Uri.parse(item.uri)) ?: return false
        if (item.directory) {
            val files = (root.listFiles() ?: return false).filter { !it.isDirectory && eligible(it.name.orEmpty()) }
                .sortedBy { it.name }
            if (files.size > 10000) return false
            for (i in cursor until files.size) {
                if (!stillQueued(item) || ReaderImageRules.precheckVersion() != version) return false
                val bytes = files[i].openInputStream().use { boundedBytes(it) }
                if (bytes != null && !ReaderImageRules.precheckDownloaded(bytes)) return false
                // Oversized inputs remain unknown, not a false clean decision.
                if (ReaderImageRules.precheckVersion() != version) return false
                advance(item, i + 1)
            }
        } else {
            // Seek through a read-only descriptor: resuming never reinflates prior entries or extracts paths.
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r") ?: return false
            descriptor.use { fd ->
                ZipFile(File("/proc/self/fd/${fd.fd}")).use { zip ->
                    val entries = zip.entries().asSequence().take(10001).toList()
                    if (entries.size > 10000) return false
                    for (i in cursor until entries.size) {
                        if (!stillQueued(item) || ReaderImageRules.precheckVersion() != version) return false
                        val entry = entries[i]
                        if (!entry.isDirectory && eligible(entry.name) && entry.size in 1..16L * 1024 * 1024) {
                            val bytes = zip.getInputStream(entry).use { boundedBytes(it) }
                            if (bytes != null && !ReaderImageRules.precheckDownloaded(bytes)) return false
                        }
                        if (ReaderImageRules.precheckVersion() != version) return false
                advance(item, i + 1)
                    }
                }
            }
        }
        return ReaderImageRules.precheckVersion() == version
    }

    private fun eligible(name: String): Boolean = !name.endsWith(".tmp", true) &&
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")

    private fun boundedBytes(input: InputStream): ByteArray? {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return result.toByteArray().takeIf { it.isNotEmpty() }
            if (result.size() + count > 16 * 1024 * 1024) return null
            result.write(buffer, 0, count)
        }
    }
}
