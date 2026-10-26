package eu.kanade.tachiyomi.ui.reader.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer

/** Exactly the three authorized application fields. Never serialize reader metadata wholesale. */
internal data class ReaderSpamReport(val imageHash: String, val seriesName: String, val chapterNumber: Double) {
    init {
        require(imageHash.matches(Regex("[0-9a-f]{64}")))
        require(seriesName.isNotBlank() && seriesName.length <= 512)
        require(chapterNumber.isFinite() && kotlin.math.abs(chapterNumber) <= 10_000_000)
    }

    fun json(): String = "{\"image_hash\":${quote(imageHash)}," +
        "\"series_name\":${quote(Normalizer.normalize(seriesName, Normalizer.Form.NFC))}," +
        "\"chapter_number\":${if (chapterNumber == 0.0) 0.0 else chapterNumber}}"

    companion object {
        private fun quote(value: String): String = buildString {
            append('"')
            for (character in value) when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u" + character.code.toString(16).padStart(4, '0'))
                else -> append(character)
            }
            append('"')
        }
    }
}

/** Durable user reports are never evicted to make room. Capacity failure is reported to the caller. */
internal class ReaderSpamOutbox(
    private val directory: File,
    private val entryLimit: Int = 1000,
    private val byteLimit: Long = 8L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Pending(val id: String, val payload: ByteArray, val attempts: Int, val retryAt: Long)
    private val lock = Any()
    private fun files() = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}\\.report")) }
    private fun ownedFiles() = directory.listFiles().orEmpty().filter {
        it.name.matches(Regex("[0-9a-f]{64}(\\.report|\\.-?[0-9]+\\.pending)"))
    }

    fun enqueue(report: ReaderSpamReport): Boolean = synchronized(lock) {
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val bytes = report.json().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 8192)
            val id = ReaderCheckedStore.digest(bytes)
            val target = File(directory, "$id.report")
            if (target.exists()) return@runCatching read(target)?.payload?.contentEquals(bytes) == true
            val existing = files()
            if (existing.size >= entryLimit || existing.sumOf { it.length() } + bytes.size + 128 > byteLimit) {
                return@runCatching false
            }
            persist(Pending(id, bytes, 0, 0))
        }.getOrDefault(false)
    }

    fun ready(): Pending? = synchronized(lock) {
        files().mapNotNull(::read).filter { it.retryAt <= clock() }.minByOrNull { it.retryAt }
    }

    /** Call only after a validated collector acknowledgement. A cancelled request leaves the file intact. */
    fun acknowledge(item: Pending): Boolean = synchronized(lock) {
        val file = File(directory, "${item.id}.report")
        read(file)?.payload?.contentEquals(item.payload) == true && file.delete()
    }

    fun retry(item: Pending): Boolean = synchronized(lock) {
        val current = read(File(directory, "${item.id}.report")) ?: return@synchronized false
        val attempts = (current.attempts + 1).coerceAtMost(16)
        persist(current.copy(attempts = attempts, retryAt = clock() + minOf(3_600_000L, 5_000L * (1L shl attempts))))
    }

    private fun persist(item: Pending): Boolean {
        var temporary: File? = null
        return runCatching {
            // Interrupted staging files count toward the bound, and remain held for diagnosis.
            val owned = ownedFiles()
            if (owned.size >= entryLimit * 2 || owned.sumOf { it.length() } + item.payload.size + 20 > byteLimit) {
                return@runCatching false
            }
            temporary = File.createTempFile("${item.id}.", ".pending", directory)
            val stream = FileOutputStream(temporary)
            DataOutputStream(stream.buffered()).use { output ->
                output.writeInt(0x52535031)
                output.writeInt(item.attempts)
                output.writeLong(item.retryAt)
                output.writeInt(item.payload.size)
                output.write(item.payload)
                output.flush()
                stream.fd.sync()
            }
            val source = requireNotNull(temporary).toPath()
            val target = File(directory, "${item.id}.report").toPath()
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Preserve the durable old record on filesystems lacking atomic replacement.
                return@runCatching false
            }
            true
        }.getOrDefault(false).also { temporary?.delete() }
    }

    private fun read(file: File): Pending? = runCatching {
        require(file.length() in 20..8300)
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x52535031)
            val attempts = input.readInt().also { require(it in 0..16) }
            val retryAt = input.readLong().also { require(it >= 0) }
            val size = input.readInt().also { require(it in 1..8192) }
            val bytes = ByteArray(size).also { input.readFully(it) }
            require(input.read() == -1)
            val id = file.name.substringBefore('.')
            require(ReaderCheckedStore.digest(bytes) == id)
            Pending(id, bytes, attempts, retryAt)
        }
    }.getOrNull()
}
