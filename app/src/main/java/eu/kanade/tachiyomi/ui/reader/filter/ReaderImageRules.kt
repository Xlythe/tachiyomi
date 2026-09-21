package eu.kanade.tachiyomi.ui.reader.filter

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** User-authored rules are independent of sources and never overwrite chapter/download files. */
object ReaderImageRules {
    val revision = MutableStateFlow(0L)
    private lateinit var directory: File

    // Content providers can serve ADB calls before Application.onCreate installs Injekt.
    @Synchronized
    fun initialize(context: Context) {
        directory = File(context.filesDir, "reader-image-rules").apply { mkdirs() }
    }
    private val index get() = AtomicFile(File(directory, "rules.json"))
    private var loaded: MutableMap<String, JSONObject>? = null

    @Synchronized
    private fun rules(): MutableMap<String, JSONObject> {
        loaded?.let { return it }
        val entries = if (index.baseFile.exists() || File(directory, "rules.json.bak").exists()) {
            JSONArray(index.openRead().use { it.reader().readText() })
        } else {
            JSONArray()
        }
        return mutableMapOf<String, JSONObject>().also { result ->
            for (i in 0 until entries.length()) {
                val rule = entries.getJSONObject(i)
                result[rule.getString("sha256")] = rule
            }
            loaded = result
        }
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Synchronized
    fun find(hash: String): JSONObject? = rules()[hash]?.let { JSONObject(it.toString()) }

    @Synchronized
    fun export(): String = JSONArray(rules().values.toList()).toString(2)

    fun original(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return File(directory, "$hash.original")
    }

    private fun persist(updated: Map<String, JSONObject>) {
        val output = index.startWrite()
        try {
            output.write(JSONArray(updated.values.toList()).toString(2).toByteArray())
            index.finishWrite(output)
            loaded = updated.toMutableMap()
        } catch (error: Throwable) {
            index.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun save(bytes: ByteArray, top: Int, bottom: Int, metadata: JSONObject, replacingHash: String? = null) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        require(top >= 0 && bottom <= bounds.outHeight && top < bottom)
        val hide = top == 0 && bottom == bounds.outHeight
        val hash = hash(bytes)
        val previous = rules()[replacingHash ?: hash]
        val replacement = File(directory, "$hash-${UUID.randomUUID()}.png")
        val rule = JSONObject(metadata.toString()).apply {
            put("schemaVersion", 1)
            put("algorithm", "sha256-encoded-image-v1")
            put("sha256", hash)
            put("width", bounds.outWidth)
            put("height", bounds.outHeight)
            put("removeTop", top)
            put("removeBottom", bottom)
            put("hide", hide)
            put("replacementFile", replacement.name)
            put("createdAt", rules()[hash]?.optLong("createdAt") ?: System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
        }
        // Build the replacement before publishing the rule, so a failed crop cannot hide a page.
        val rendered = ReaderImageMask(bytes).use { image ->
            if (!hide) {
                val probe = image.probeOffset(top, bottom)
                rule.put("maskProbeOffset", probe)
                rule.put("maskProbeSha256", image.hashBand(top + probe, top + probe + 1))
                rule.put("maskPixelSha256", image.hashBand(top, bottom))
                rule.put("maskAlgorithm", "sha256-argb8888-big-endian-v1")
                rule.put("maskAnchor", if (top == 0) "top" else if (bottom == image.height) "bottom" else "offset")
            }
            image.render(top, bottom)
        }
        replacement.writeBytes(rendered)
        try {
            if (!original(hash).exists()) {
                val atomic = AtomicFile(original(hash))
                val output = atomic.startWrite()
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
            persist((rules() - (replacingHash ?: hash)) + (hash to rule))
        } catch (error: Throwable) {
            replacement.delete()
            throw error
        }
        previous?.let { File(directory, it.getString("replacementFile")).delete() }
        if (replacingHash != null && replacingHash != hash) original(replacingHash).delete()
        revision.value++
    }

    @Synchronized
    fun remove(hash: String): Boolean {
        val previous = rules()[hash] ?: return false
        persist(rules() - hash)
        original(hash).delete()
        File(directory, previous.getString("replacementFile")).delete()
        revision.value++
        return true
    }

    /** Called on the reader's IO dispatcher for online, cached, downloaded and local images. */
    @Synchronized
    fun open(onMatch: (Boolean) -> Unit = {}, original: () -> InputStream): InputStream {
        if (rules().isEmpty()) {
            onMatch(false)
            return original()
        }
        val bytes = original().use { it.readBytes() }
        val hash = hash(bytes)
        val rule = rules()[hash]
        val replacement = rule?.let { File(directory, it.getString("replacementFile")) }?.takeIf { it.exists() }
        if (replacement != null) {
            onMatch(rule?.optBoolean("hide") == true)
            return replacement.inputStream()
        }
        if (rules().values.any { it.has("maskPixelSha256") }) {
            try {
                ReaderImageMask(bytes).use { image ->
                    val match = matchingBand(image)
                    if (match != null) {
                        val start = match.getInt("removeTop")
                        val end = match.getInt("removeBottom")
                        val rendered = image.render(start, end)
                        onMatch(start == 0 && end == image.height)
                        return rendered.inputStream()
                    }
                }
            } catch (_: Exception) {
                // Optional filtering must not prevent unsupported images reaching the reader.
            }
        }
        onMatch(false)
        return bytes.inputStream()
    }

    @Synchronized
    fun selection(bytes: ByteArray): JSONObject? {
        find(hash(bytes))?.let { return it }
        if (rules().values.none { it.has("maskPixelSha256") }) return null
        return ReaderImageMask(bytes).use { matchingBand(it) }
    }

    private fun matchingBand(image: ReaderImageMask): JSONObject? {
        val partialRules = rules().values.filter { it.has("maskPixelSha256") }
        val hashes = mutableMapOf<Pair<Int, Int>, String>()
        for (candidate in partialRules) {
            if (candidate.getInt("width") != image.width) continue
            val bandHeight = candidate.getInt("removeBottom") - candidate.getInt("removeTop")
            val start = when (candidate.getString("maskAnchor")) {
                "top" -> 0
                "bottom" -> image.height - bandHeight
                else -> candidate.getInt("removeTop")
            }
            val end = start + bandHeight
            if (start < 0 || end > image.height) continue
            val probe = start + candidate.getInt("maskProbeOffset")
            val probeHash = hashes.getOrPut(probe to probe + 1) { image.hashBand(probe, probe + 1) }
            if (probeHash != candidate.getString("maskProbeSha256")) continue
            val fingerprint = hashes.getOrPut(start to end) { image.hashBand(start, end) }
            if (fingerprint != candidate.getString("maskPixelSha256")) continue
            return JSONObject(candidate.toString()).apply {
                put("removeTop", start)
                put("removeBottom", end)
            }
        }
        return null
    }
}
