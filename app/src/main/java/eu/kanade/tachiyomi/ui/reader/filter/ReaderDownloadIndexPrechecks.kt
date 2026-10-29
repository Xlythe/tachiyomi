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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Metadata feeder only. All page reads/matching stay in the single download-precheck worker. */
internal object ReaderDownloadIndexPrechecks {
    private data class Binding(
        val root: String,
        val page: suspend (ReaderDownloadTraversal.Key?, Int) -> List<ReaderDownloadTraversal.Chapter>,
    )
    private val gate = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val traversal = ReaderDownloadTraversal()
    private lateinit var context: Context
    private lateinit var index: AtomicFile
    private var healthy = true
    private var binding: Binding? = null
    private var worker: Job? = null
    private var signals = 0L

    fun initialize(app: Context) = synchronized(gate) {
        if (::context.isInitialized) return@synchronized
        context = app.applicationContext
        index = AtomicFile(File(context.filesDir, "reader-download-traversal-v1.json"))
        runCatching {
            if (index.baseFile.exists() || File(index.baseFile.path + ".bak").exists()) {
                require(maxOf(index.baseFile.length(), File(index.baseFile.path + ".bak").length()) <= 128 * 1024)
                val data = JSONObject(index.openRead().use { it.reader().readText() })
                require(data.getInt("schema") == 1)
                val version = data.getString("version")
                require(version.isEmpty() || version.matches(Regex("[0-9a-f]{64}")))
                val root = data.getString("root")
                require(root.isEmpty() || validUri(root))
                val rows = data.getJSONArray("pending")
                require(rows.length() <= 8)
                val pending = (0 until rows.length()).map { i ->
                    val row = rows.getJSONObject(i)
                    val uri = row.getString("mangaUri")
                    require(validUri(uri))
                    ReaderDownloadTraversal.Chapter(readKey(row.getJSONObject("key")), uri)
                }
                traversal.restore(
                    ReaderDownloadTraversal.State(
                        ReaderDownloadTraversal.Token(version, root),
                        data.optJSONObject("after")?.let(::readKey),
                        pending,
                        data.getBoolean("done"),
                        data.optLong("checkedChapters"),
                        data.optLong("unknownChapters"),
                    ),
                )
            }
        }.onFailure { healthy = false } // Preserve unsupported/corrupt evidence instead of overwriting it.
    }

    internal fun snapshot() = synchronized(gate) { traversal.snapshot() }
    internal fun isRunning() = synchronized(gate) { worker?.isActive == true }

    private fun validUri(uri: String) = uri.toByteArray().size <= 4096 && uri.none { it < ' ' } &&
        Uri.parse(uri).scheme in setOf("file", "content")
    private fun readKey(row: JSONObject): ReaderDownloadTraversal.Key {
        val manga = row.getString("manga")
        val chapter = row.getString("chapter")
        require(manga.length <= 1024 && chapter.length <= 1024)
        return ReaderDownloadTraversal.Key(row.getLong("source"), manga, chapter)
    }
    private fun keyJson(key: ReaderDownloadTraversal.Key) = JSONObject().put("source", key.source)
        .put("manga", key.manga).put("chapter", key.chapter)

    private fun persistLocked() = runCatching {
        if (!healthy) return@runCatching
        val state = traversal.snapshot()
        val rows =
            JSONArray(state.pending.map { JSONObject().put("key", keyJson(it.key)).put("mangaUri", it.mangaUri) })
        val data = JSONObject().put("schema", 1).put("version", state.token.version).put("root", state.token.root)
            .put("done", state.done).put("pending", rows)
            .put("checkedChapters", state.checkedChapters).put("unknownChapters", state.unknownChapters)
        state.after?.let { data.put("after", keyJson(it)) }
        val bytes = data.toString().toByteArray()
        require(bytes.size <= 128 * 1024)
        val stream = index.startWrite()
        try {
            stream.write(bytes)
            index.finishWrite(stream)
        } catch (e: Throwable) {
            index.failWrite(stream)
            throw e
        }
    }

    /** Installed only after the existing background inventory has finished with initialized extensions. */
    fun installIndex(
        root: String,
        page: suspend (ReaderDownloadTraversal.Key?, Int) -> List<ReaderDownloadTraversal.Chapter>,
    ) = synchronized(gate) {
        if (!::context.isInitialized || !validUri(root)) return@synchronized
        binding = Binding(root, page)
        signals++
        startLocked()
    }

    /** An incomplete/failed metadata inventory must not masquerade as an empty finished library. */
    fun indexUnavailable() = synchronized(gate) {
        binding = null
        signals++
    }

    fun rulesChanged() = synchronized(gate) {
        signals++
        startLocked()
    }

    private fun startLocked() {
        if (!::context.isInitialized || !healthy || binding == null || worker?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var observedSignal = -1L
            try {
                while (true) {
                    val bound = synchronized(gate) {
                        observedSignal = signals
                        binding
                    } ?: break
                    val token = ReaderDownloadTraversal.Token(ReaderImageRules.precheckVersion(), bound.root)
                    val state = synchronized(gate) { traversal.begin(token).also { persistLocked() } }
                    if (state.done) break
                    if (state.pending.isEmpty()) {
                        val rows = try { bound.page(state.after, 8) } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            delay(10_000)
                            continue // Unavailable index is never an empty/completed index.
                        }
                        synchronized(gate) {
                            require(
                                rows.all {
                                    validUri(it.mangaUri) &&
                                        it.key.manga.length <= 1024 &&
                                        it.key.chapter.length <= 1024
                                },
                            )
                            if (binding === bound && traversal.offer(token, rows)) persistLocked()
                        }
                        continue
                    }
                    val chapter = state.pending.first()
                    val root = try {
                        val manga = UniFile.fromUri(context, Uri.parse(chapter.mangaUri))
                        manga?.findFile(chapter.key.chapter) ?: manga?.findFile(chapter.key.chapter + ".cbz")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    if (root == null || !validUri(root.uri.toString())) {
                        // Removed/unavailable files remain unknown; skip metadata without writing a page verdict.
                        synchronized(
                            gate,
                        ) {
                            if (binding === bound &&
                                traversal.finish(
                                    token, chapter,
                                    checked =
                                    false,
                                )
                            ) {
                                persistLocked()
                            }
                        }
                        continue
                    }
                    val ticket = ReaderDownloadPrechecks.traversalTicket(root.uri, root.isDirectory)
                    try {
                        while (ReaderImageRules.precheckVersion() == token.version &&
                            synchronized(gate) { binding === bound }
                        ) {
                            val progress = ReaderDownloadPrechecks.traversalProgress(ticket, token.version)
                            if (progress == ReaderDownloadSchedule.Progress.SUPERSEDED) break
                            if (progress != ReaderDownloadSchedule.Progress.WAITING) {
                                // Failed work stays unknown in the bounded retry history; it cannot starve later chapters.

                                synchronized(gate) {
                                    if (binding === bound && traversal.finish(
                                            token, chapter,
                                            checked = progress == ReaderDownloadSchedule.Progress.COMPLETE,
                                        )
                                    ) {
                                        persistLocked()
                                    }
                                }
                                break
                            }
                            delay(50)
                        }
                    } finally { ReaderDownloadPrechecks.releaseTraversalTicket(ticket.uri) }
                }
            } finally {
                synchronized(gate) {
                    worker = null
                    persistLocked()
                    if (signals != observedSignal) startLocked()
                }
            }
        }
        worker = job
        job.start()
    }
}
