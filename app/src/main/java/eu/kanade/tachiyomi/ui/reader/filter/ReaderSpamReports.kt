package eu.kanade.tachiyomi.ui.reader.filter

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** No default endpoint or credential. No activity until explicitly provisioned by the operator. */
internal object ReaderSpamReports {
    private lateinit var context: Context
    private lateinit var outbox: ReaderSpamOutbox
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun initialize(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        outbox = ReaderSpamOutbox(File(context.filesDir, "reader-explicit-spam-outbox-v1"))
        scope.launch {
            while (true) {
                val config = context.getSharedPreferences("reader_report_transport", Context.MODE_PRIVATE)
                val endpoint = config.getString("endpoint", null)
                val credential = config.getString("credential", null)
                if (!endpoint.isNullOrBlank() && !credential.isNullOrBlank()) {
                    val item = runCatching { outbox.ready() }.getOrNull()
                    if (item != null) {
                        if (send(endpoint, credential, item.payload)) outbox.acknowledge(item) else outbox.retry(item)
                    }
                }
                delay(10_000)
            }
        }
    }

    /** Only called after an explicit hide/crop commits. Imports, restores and automatic matches never call this. */
    fun enqueueExplicit(hash: String, metadata: JSONObject): Boolean = runCatching {
        val title = metadata.getString("seriesName")
        val chapter = metadata.getDouble("chapterNumber")
        outbox.enqueue(ReaderSpamReport(hash, title, chapter))
    }.getOrDefault(false)

    private fun send(endpoint: String, credential: String, payload: ByteArray): Boolean = runCatching {
        val url = URL(endpoint)
        require(url.protocol == "https" && url.userInfo == null && url.query == null && url.ref == null)
        require(url.path == "/v1/spam-reports" && credential.length in 1..1024)
        require(credential.all { it.code in 33..126 })
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $credential")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "ReaderSpamReporter/1")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode != 200) return@runCatching false
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(8193)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= 8192)
                buffer.copyOf(count)
            }
            val response = JSONObject(String(bytes, Charsets.UTF_8))
            response.optBoolean("accepted") && response.optString("receipt").matches(Regex("[0-9a-f]{64}"))
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
