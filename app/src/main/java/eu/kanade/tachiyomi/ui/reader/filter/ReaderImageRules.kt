package eu.kanade.tachiyomi.ui.reader.filter

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

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
    private val signatures = LinkedHashMap<String, ReaderImageSignature>(128, 0.75f, true)

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

    /** Portable, local-only corpus. No generated replacements or executable paths are imported. */
    @Synchronized
    fun exportArchive(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("format.json", "{\"format\":\"tachiyomi-reader-image-rules\",\"version\":1}".toByteArray())
            entry("rules.json", export().toByteArray())
            for (hash in rules().keys) entry("$hash.original", original(hash).readBytes())
        }
    }

    @Synchronized
    fun importArchive(input: InputStream): Int {
        val staging = File(directory, "import-${UUID.randomUUID()}").apply { check(mkdir()) }
        return try {
            importStagedArchive(input, staging)
        } finally {
            staging.listFiles()?.forEach { it.delete() }
            staging.delete()
        }
    }

    private fun importStagedArchive(input: InputStream, staging: File): Int {
        val entries = mutableMapOf<String, File>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(
                    entry.name == "rules.json" || entry.name == "format.json" ||
                        entry.name.matches(Regex("[0-9a-f]{64}\\.original")),
                ) {
                    "Unsupported archive entry"
                }
                require(
                    !entries.containsKey(entry.name) && entries.size < 1002,
                ) { "Duplicate or excessive archive entries" }
                val file = File(staging, entry.name)
                var size = 0L
                val buffer = ByteArray(8192)
                file.outputStream().use { output ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        size += count
                        require(total <= 512L * 1024 * 1024 && size <= 32 * 1024 * 1024) { "Archive too large" }
                        output.write(buffer, 0, count)
                    }
                }
                entries[entry.name] = file
            }
        }
        val format = JSONObject(requireNotNull(entries["format.json"]).readText())
        require(format.getString("format") == "tachiyomi-reader-image-rules" && format.getInt("version") == 1) {
            "Unsupported corpus format"
        }
        val records = JSONArray(requireNotNull(entries["rules.json"]).readText())
        require(records.length() <= 1000) { "Too many rules" }
        val updated = rules().toMutableMap()
        val created = mutableListOf<File>()
        var imported = 0
        try {
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                require(record.getInt("schemaVersion") == 1) { "Unsupported rule version" }
                val digest = record.getString("sha256")
                require(digest.matches(Regex("[0-9a-f]{64}")))
                val raw = requireNotNull(entries["$digest.original"]) { "Missing original image" }.readBytes()
                require(hash(raw) == digest) { "Original image checksum mismatch" }
                // Import is additive: keep existing local choices on a duplicate image.
                if (updated.containsKey(digest)) continue
                val rule = JSONObject(record.toString())
                val top = rule.getInt("removeTop")
                val bottom = rule.getInt("removeBottom")
                val replacement = File(directory, "$digest-${UUID.randomUUID()}.png")
                val rendered = ReaderImageMask(raw).use { image ->
                    require(image.width.toLong() * image.height <= 40_000_000) { "Image too large" }
                    require(top >= 0 && top < bottom && bottom <= image.height)
                    rule.put("width", image.width).put("height", image.height)
                    rule.put("hide", !rule.has("watermark") && top == 0 && bottom == image.height)
                    listOf(
                        "maskProbeOffset",
                        "maskProbeSha256",
                        "maskPixelSha256",
                        "maskAlgorithm",
                        "maskAnchor",
                    ).forEach {
                        rule.remove(it)
                    }
                    if (rule.has("watermark")) {
                        val area = rule.getJSONArray("watermark")
                        require(
                            area.length() == 4 &&
                                rule.getString("watermarkMethod") == "ellipse-horizontal-interpolation-v1",
                        )
                        image.renderWatermark(Rect(area.getInt(0), area.getInt(1), area.getInt(2), area.getInt(3)))
                    } else {
                        if (!rule.getBoolean("hide")) {
                            val probe = image.probeOffset(top, bottom)
                            rule.put("maskProbeOffset", probe)
                            rule.put("maskProbeSha256", image.hashBand(top + probe, top + probe + 1))
                            rule.put("maskPixelSha256", image.hashBand(top, bottom))
                            rule.put("maskAlgorithm", "sha256-argb8888-big-endian-v1")
                            rule.put(
                                "maskAnchor",
                                if (top == 0) "top" else if (bottom == image.height) "bottom" else "offset",
                            )
                        }
                        image.render(top, bottom)
                    }
                }
                rule.put("replacementFile", replacement.name)
                created += replacement
                replacement.writeBytes(rendered)
                val original = original(digest)
                if (!original.exists()) {
                    created += original
                    original.writeBytes(raw)
                }
                updated[digest] = rule
                imported++
            }
            // Publish once: an interrupted or invalid import never installs a partial index.
            if (imported > 0) {
                persist(updated)
                signatures.clear()
                revision.value++
            }
        } catch (error: Throwable) {
            created.forEach { it.delete() }
            throw error
        }
        return imported
    }

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
    fun save(
        bytes: ByteArray,
        top: Int,
        bottom: Int,
        metadata: JSONObject,
        replacingHash: String? = null,
        watermark: Rect? = null,
    ) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        require(top >= 0 && bottom <= bounds.outHeight && top < bottom)
        val hide = watermark == null && top == 0 && bottom == bounds.outHeight
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
            if (watermark != null) {
                put("watermark", JSONArray(listOf(watermark.left, watermark.top, watermark.right, watermark.bottom)))
                put("watermarkMethod", "ellipse-horizontal-interpolation-v1")
            }
            put("replacementFile", replacement.name)
            put("createdAt", rules()[hash]?.optLong("createdAt") ?: System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
        }
        // Build the replacement before publishing the rule, so a failed crop cannot hide a page.
        val rendered = ReaderImageMask(bytes).use { image ->
            if (watermark != null) return@use image.renderWatermark(watermark)
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
        signatures.clear()
    }

    @Synchronized
    fun remove(hash: String): Boolean {
        val previous = rules()[hash] ?: return false
        persist(rules() - hash)
        original(hash).delete()
        File(directory, previous.getString("replacementFile")).delete()
        revision.value++
        signatures.clear()
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
        if (replacement != null && rule != null && (rule.optBoolean("hide") || rule.has("watermark"))) {
            onMatch(rule.optBoolean("hide"))
            return replacement.inputStream()
        }
        if (rules().values.any { !it.has("watermark") }) {
            try {
                ReaderImageMask(bytes).use { image ->
                    val matches = matchingBands(image, rule?.takeUnless { it.has("watermark") })
                    if (matches.isNotEmpty()) {
                        val bands = ReaderImageSignature.merge(
                            matches.map { it.getInt("removeTop") until it.getInt("removeBottom") },
                        )
                        val rendered = image.render(bands)
                        onMatch(bands.size == 1 && bands[0].first == 0 && bands[0].last == image.height - 1)
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
        return ReaderImageMask(bytes).use { matchingBands(it).firstOrNull() }
    }

    private fun matchingBands(image: ReaderImageMask, exact: JSONObject? = null): List<JSONObject> {
        val matches = mutableListOf<JSONObject>()
        if (exact != null) matches += JSONObject(exact.toString())
        val partialRules = rules().values.filter { it.has("maskPixelSha256") }
        val hashes = mutableMapOf<Pair<Int, Int>, String>()
        for (candidate in partialRules) {
            if (matches.any { it.getString("sha256") == candidate.getString("sha256") }) continue
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
            matches += JSONObject(candidate.toString()).apply {
                put("removeTop", start)
                put("removeBottom", end)
            }
        }
        // Old rules participate without migration: derive signatures from the preserved originals.
        val target = runCatching { image.signature() }.getOrNull() ?: return matches
        val edges by lazy { image.contrastEdges().map { (it * image.height).roundToInt() } }
        for (candidate in rules().values.filter { !it.has("watermark") }) {
            if (matches.any { it.getString("sha256") == candidate.getString("sha256") }) continue
            val sample = runCatching {
                signatures.getOrPut(candidate.getString("sha256")) {
                    ReaderImageMask(original(candidate.getString("sha256")).readBytes()).use {
                        it.signature(candidate.getInt("removeTop"), candidate.getInt("removeBottom"))
                    }
                }
            }.getOrNull() ?: continue
            // A corpus of small banners can stay warm without retaining decoded full-page bitmaps.
            while (signatures.values.sumOf { it.pixels.size.toLong() * 4 } > 16L * 1024 * 1024) {
                signatures.remove(signatures.keys.first())
            }
            val bands = mutableListOf<IntRange>()
            val scale = image.width.toFloat() / candidate.getInt("width")
            val start = (candidate.getInt("removeTop") * scale * target.height / image.height).roundToInt()
            if (candidate.optString("maskAnchor") == "offset" && target.agrees(sample, start, 0, sample.height)) {
                bands += start until start + sample.height
            } else {
                // A full sample can be a standalone spam page or span adjacent page boundaries.
                bands += target.edgeMatches(sample)
            }
            for (band in bands) {
                val estimatedTop = (band.first.toFloat() * image.height / target.height).roundToInt()
                val estimatedBottom = ((band.last + 1f) * image.height / target.height).roundToInt()
                val top = if (estimatedTop == 0) {
                    0
                } else {
                    ReaderImageSignature.refineBoundary(estimatedTop, image.height, target.height, edges) ?: continue
                }
                val bottom = if (estimatedBottom == image.height) {
                    image.height
                } else {
                    ReaderImageSignature.refineBoundary(estimatedBottom, image.height, target.height, edges) ?: continue
                }
                if (top >= bottom) continue
                matches += JSONObject(candidate.toString()).apply {
                    put("removeTop", top)
                    put("removeBottom", bottom)
                }
            }
        }
        return matches
    }
}
