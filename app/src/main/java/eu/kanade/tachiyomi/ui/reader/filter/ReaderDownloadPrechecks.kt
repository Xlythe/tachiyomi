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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

/** One worker, one <=16 MiB image at a time; bounded durable metadata for 128 known chapters. */
internal object ReaderDownloadPrechecks {
    private val gate = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val schedule = ReaderDownloadSchedule()
    private var worker: Job? = null
    private lateinit var context: Context
    private lateinit var index: AtomicFile
    private var indexHealthy = true

    fun initialize(app: Context) = synchronized(gate) {
        if (::context.isInitialized) return@synchronized
        context = app.applicationContext
        index = AtomicFile(File(context.filesDir, "reader-download-prechecks-v2.json"))
        // Migrate pending v1 work without modifying its original index or losing its cursor.
        val legacy = AtomicFile(File(context.filesDir, "reader-download-prechecks-v1.json"))
        val source = if (exists(index)) index else legacy
        runCatching {
            if (exists(source)) {
                require(maxOf(source.baseFile.length(), File(source.baseFile.path + ".bak").length()) <= 1024 * 1024)
                val data = JSONObject(source.openRead().use { it.reader().readText() })
                val version = data.getInt("version")
                require(version == 1 || version == 2)
                val array = data.getJSONArray("jobs")
                require(array.length() <= if (version == 1) 8 else 128)
                val restored = (0 until array.length()).map { i ->
                    val row = array.getJSONObject(i)
                    val uri = row.getString("uri")
                    require(validUri(uri))
                    val rulesVersion = row.optString("rulesVersion", "")
                    val checkedVersion = row.optString("checkedVersion", "")
                    require(listOf(rulesVersion, checkedVersion).all { it.isEmpty() || it.matches(Regex("[0-9a-f]{64}")) })
                    ReaderDownloadSchedule.Entry(
                        uri, row.getBoolean("directory"), UUID.randomUUID().toString(),
                        cursor = row.getInt("cursor").coerceIn(0, 10000), rulesVersion = rulesVersion,
                        checkedVersion = checkedVersion,
                    )
                }
                schedule.restore(restored)
            }
        }.onFailure {
            // Preserve malformed/unsupported state for diagnosis; do not overwrite it with an empty history.
            indexHealthy = false
        }
        ReaderDownloadIndexPrechecks.initialize(context)
        // Conflate bursts of edits off the UI thread. Work checks the actual current version per page.
        scope.launch {
            ReaderImageRules.revision.collectLatest {
                delay(250)
                synchronized(gate) { wake.trySend(Unit); startLocked() }
                ReaderDownloadIndexPrechecks.rulesChanged()
            }
        }
        startLocked()
    }

    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun validUri(value: String) = value.toByteArray().size <= 4096 && value.none { it < ' ' } &&
        Uri.parse(value).scheme in setOf("file", "content")

    fun enqueue(uri: Uri, directory: Boolean) = synchronized(gate) {
        val value = uri.toString()
        if (!::context.isInitialized || !validUri(value)) return@synchronized
        schedule.committed(value, directory, UUID.randomUUID().toString())
        persistLocked()
        wake.trySend(Unit)
        startLocked()
    }

    /** Called by the existing background download inventory; no image reads or new tree scans. */
    fun rememberExisting(uri: Uri, directory: Boolean) = synchronized(gate) {
        val value = uri.toString()
        if (!::context.isInitialized || !validUri(value)) return@synchronized
        if (schedule.discovered(value, directory, UUID.randomUUID().toString())) {
            wake.trySend(Unit)
            startLocked()
        }
    }

    /** Forget only scheduling metadata for a removed chapter; original bytes/rules/decisions are untouched. */
    fun forget(uri: Uri) = synchronized(gate) {
        schedule.forget(uri.toString())
        persistLocked()
        wake.trySend(Unit)
    }

    fun traversalTicket(uri: Uri, directory: Boolean): ReaderDownloadSchedule.Entry = synchronized(gate) {
        require(validUri(uri.toString()))
        val ticket = schedule.traversalTicket(uri.toString(), directory, UUID.randomUUID().toString())
        persistLocked()
        wake.trySend(Unit)
        startLocked()
        ticket
    }
    fun traversalProgress(ticket: ReaderDownloadSchedule.Entry, version: String) = synchronized(gate) {
        schedule.progress(ticket, version)
    }
    fun releaseTraversalTicket(uri: String) = synchronized(gate) { schedule.releaseTraversalTicket(uri) }

    private fun persistLocked() = runCatching {
        if (!indexHealthy) return@runCatching
        val rows = JSONArray(schedule.snapshot().map { item ->
            JSONObject().put("uri", item.uri).put("directory", item.directory)
                .put("cursor", item.cursor).put("rulesVersion", item.rulesVersion)
                .put("checkedVersion", item.checkedVersion)
        })
        val bytes = JSONObject().put("version", 2).put("jobs", rows).toString().toByteArray()
        require(bytes.size <= 1024 * 1024)
        val output = index.startWrite()
        try { output.write(bytes); index.finishWrite(output) }
        catch (error: Throwable) { index.failWrite(output); throw error }
    }

    private fun startLocked() {
        if (worker?.isActive == true || schedule.snapshot().isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var finishedNormally = false
            var lastVersion = ""
            try {
                while (true) {
                    // Never take the rules monitor while holding the scheduler gate.
                    val version = ReaderImageRules.precheckVersion()
                    lastVersion = version
                    val next = synchronized(gate) {
                        schedule.next(version, System.currentTimeMillis()).also { if (it != null) persistLocked() }
                    }
                    if (next == null) {
                        val finished = synchronized(gate) {
                            !schedule.hasPending(version)
                        }
                        if (finished) { finishedNormally = true; break }
                        withTimeoutOrNull(10_000) { wake.receive() }
                        continue
                    }
                    val completed = try { process(next) } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        false
                    }
                    val latest = ReaderImageRules.precheckVersion()
                    synchronized(gate) {
                        if (completed) schedule.complete(next, latest)
                        else if (latest == next.rulesVersion) schedule.failed(next, System.currentTimeMillis())
                        persistLocked()
                    }
                }
            } finally {
                synchronized(gate) {
                    worker = null
                    persistLocked()
                    // Preserve a rule-change/commit wake arriving after the final empty check.
                    // Comparing only lastVersion would lose a new rule when every old job was complete.
                    if (finishedNormally &&
                        (schedule.hasPending(lastVersion) || wake.tryReceive().isSuccess)
                    ) startLocked()
                }
            }
        }
        worker = job
        job.start()
    }

    private fun stillQueued(item: ReaderDownloadSchedule.Entry) = synchronized(gate) { schedule.current(item) }
    private fun advance(item: ReaderDownloadSchedule.Entry, next: Int) = synchronized(gate) {
        if (schedule.advance(item, next) && next % 16 == 0) persistLocked()
    }

    private suspend fun process(item: ReaderDownloadSchedule.Entry): Boolean {
        val version = item.rulesVersion
        val cursor = item.cursor
        val root = UniFile.fromUri(context, Uri.parse(item.uri)) ?: return false
        var incomplete = false
        if (item.directory) {
            val listed = root.listFiles() ?: return false
            incomplete = listed.any { it.name.orEmpty().endsWith(".tmp", true) }
            val files = listed.filter { !it.isDirectory && eligible(it.name.orEmpty()) }.sortedBy { it.name }
            if (files.isEmpty() || files.size > 10000 || cursor > files.size) return false
            for (i in cursor until files.size) {
                if (!stillQueued(item) || ReaderImageRules.precheckVersion() != version) return false
                val bytes = files[i].openInputStream().use { boundedBytes(it) }
                if (bytes == null || !ReaderImageRules.precheckDownloaded(bytes)) return false
                // Oversized/empty inputs remain unknown and retry; never certify the chapter as checked.
                if (ReaderImageRules.precheckVersion() != version) return false
                advance(item, i + 1)
            }
        } else {
            // Seek through a read-only descriptor: resuming never reinflates prior entries or extracts paths.
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r") ?: return false
            descriptor.use { fd ->
                ZipFile(File("/proc/self/fd/${fd.fd}")).use { zip ->
                    val entries = zip.entries().asSequence().take(10001).toList()
                    if (entries.size > 10000 || cursor > entries.size ||
                        entries.none { !it.isDirectory && eligible(it.name) }) return false
                    incomplete = entries.any { it.name.endsWith(".tmp", true) }
                    for (i in cursor until entries.size) {
                        if (!stillQueued(item) || ReaderImageRules.precheckVersion() != version) return false
                        val entry = entries[i]
                        if (!entry.isDirectory && eligible(entry.name)) {
                            if (entry.size !in 1..16L * 1024 * 1024) return false
                            val bytes = zip.getInputStream(entry).use { boundedBytes(it) }
                            if (bytes == null || !ReaderImageRules.precheckDownloaded(bytes)) return false
                        }
                        if (ReaderImageRules.precheckVersion() != version) return false
                        advance(item, i + 1)
                    }
                }
            }
        }
        if (incomplete) {
            // Check finalized siblings but never certify a partial chapter; retry from the first entry.
            advance(item, 0)
            return false
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
