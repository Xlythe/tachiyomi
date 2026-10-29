package eu.kanade.tachiyomi.ui.reader.filter

import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Derived local evidence only: no page-sized bitmaps, source edits or network requests. */
internal class ReaderRuleFingerprintCache(
    private val directory: File,
    private val diskLimit: Long = 128L * 1024 * 1024,
    private val provenance: String = Build.FINGERPRINT,
) {
    data class Entry(
        val signature: ReaderImageSignature,
        val probe: Int = -1,
        val probeHash: String = "",
        val pixelsHash: String = "",
    )

    private val writeLock = Any()

    fun prune(validKeys: Set<String>) = synchronized(writeLock) {
        val files = directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("[0-9a-f]{64}\\.bin(\\.bak|\\.new)?"))
        }
        files.filter { it.name.substringBefore('.') !in validKeys || it.name.endsWith(".new") }
            .forEach { it.delete() }
        val retained = files.filter { it.exists() }.groupBy { it.name.substringBefore('.') }.values
            .sortedBy { group -> group.maxOf { it.lastModified() } }
        var size = retained.sumOf { group -> group.sumOf { it.length() } }
        for (group in retained) {
            if (size <= diskLimit) break
            for (file in group) {
                val bytes = file.length()
                if (file.delete()) size -= bytes
            }
        }
    }

    fun contains(rule: JSONObject) = File(directory, "${key(rule)}.bin").isFile

    fun key(rule: JSONObject): String = ReaderImageRules.hash(
        listOf(
            "strip64-argb-v1-signature48-v1",
            provenance,
            rule.getString("sha256"),
            rule.getInt("width"),
            rule.getInt("height"),
            rule.getInt("removeTop"),
            rule.getInt("removeBottom"),
            rule.optBoolean("hide"),
            rule.has("watermark"),
        ).joinToString("|").toByteArray(),
    )

    fun read(rule: JSONObject): Entry? = runCatching {
        val key = key(rule)
        val file = File(directory, "$key.bin")
        require(listOf(file, File(directory, "$key.bin.bak")).all { !it.exists() || it.length() <= 1024 * 1024 })
        // AtomicFile.openRead removes .new files. A reader must never interfere with
        // background persistence; a concurrent incomplete file is simply a cache miss.
        val backup = File(directory, "$key.bin.bak")
        DataInputStream(GZIPInputStream((if (backup.exists()) backup else file).inputStream())).use { input ->
            require(input.readInt() == 0x52494631 && input.readUTF() == key)
            val rows = input.readInt()
            require(rows in 1..4096)
            val signature = ReaderImageSignature(IntArray(rows * 48) { input.readInt() })
            val probe = input.readInt()
            val probeHash = input.readUTF()
            val pixelsHash = input.readUTF()
            if (rule.optBoolean("hide")) {
                require(probe in 0 until rule.getInt("height"))
                require(probeHash.matches(Regex("[0-9a-f]{64}")) && pixelsHash.matches(Regex("[0-9a-f]{64}")))
            }
            require(input.read() == -1) // Also verifies the gzip CRC before trusting cached evidence.
            Entry(signature, probe, probeHash, pixelsHash)
        }
    }.getOrNull()

    fun prepare(rule: JSONObject, original: ByteArray): Entry {
        read(rule)?.let { return it }
        require(ReaderImageRules.hash(original) == rule.getString("sha256"))
        val entry = ReaderImageMask(original, 0).use { image ->
            require(image.width == rule.getInt("width") && image.height == rule.getInt("height"))
            require(image.canDecodeRegions || image.width.toLong() * image.height <= 4_000_000) {
                "Large samples need a bounded region decoder"
            }
            val signature = image.signature(rule.getInt("removeTop"), rule.getInt("removeBottom"))
            require(signature.height in 1..4096) { "Template signature exceeds the persisted evidence bound" }
            if (rule.optBoolean("hide")) {
                val probe = image.probeOffset(0, image.height)
                Entry(signature, probe, image.hashBand(probe, probe + 1), image.hashBand(0, image.height))
            } else {
                Entry(signature)
            }
        }
        synchronized(writeLock) {
            check(directory.isDirectory || directory.mkdirs())
            val key = key(rule)
            val atomic = AtomicFile(File(directory, "$key.bin"))
            val output = atomic.startWrite()
            try {
                val wrapper = object : FilterOutputStream(output) {
                    override fun close() { flush() }
                }
                DataOutputStream(GZIPOutputStream(wrapper)).use { data ->
                    data.writeInt(0x52494631)
                    data.writeUTF(key)
                    data.writeInt(entry.signature.height)
                    entry.signature.pixels.forEach(data::writeInt)
                    data.writeInt(entry.probe)
                    data.writeUTF(entry.probeHash)
                    data.writeUTF(entry.pixelsHash)
                }
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        }
        return entry
    }
}
