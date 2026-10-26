package eu.kanade.tachiyomi.ui.reader.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Disposable, bounded decisions. Original files and user-authored rules never enter this directory. */
internal class ReaderCheckedStore(
    private val directory: File,
    private val byteLimit: Long = 64L * 1024 * 1024,
    private val entryLimit: Int = 4096,
    private val payloadLimit: Int = 16 * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val availableBytes: () -> Long = { directory.usableSpace },
) {
    data class Key(val contentHash: String, val corpusVersion: String, val matcherVersion: String) {
        init {
            require(contentHash.matches(Regex("[0-9a-f]{64}")))
            require(corpusVersion.matches(Regex("[0-9a-f]{64}")))
            require(matcherVersion.isNotBlank() && matcherVersion.length <= 2048)
        }
        fun fileToken() = digest((contentHash + "|" + matcherVersion).toByteArray())
    }

    data class Decision(val hidden: Boolean = false, val rendered: ByteArray? = null) {
        val matched get() = rendered != null
        init { require(!hidden || rendered?.isEmpty() == true) }
    }

    sealed interface Outcome {
        data class Complete(val decision: Decision, val proofs: Map<String, String> = emptyMap()) : Outcome
        data object Incomplete : Outcome
        data object Failed : Outcome
        data object Cancelled : Outcome
    }

    data class Hit(val decision: Decision, val needsRecheck: Boolean)
    private val writer = Any()

    /** No read-side recovery/deletion: concurrent readers cannot remove an unfinished write. */
    fun read(key: Key, currentProofs: Map<String, String>): Hit? {
        val base = File(directory, "${key.fileToken()}.checked")
        for (file in listOf(base, File(directory, "${base.name}.bak"))) {
            val hit = runCatching {
                require(file.length() in 1..payloadLimit.toLong() + 256 * 1024)
                val hash = MessageDigest.getInstance("SHA-256")
                val hashing = DigestInputStream(file.inputStream().buffered(), hash)
                DataInputStream(hashing).use { input ->
                    require(input.readInt() == 0x52434431)
                    require(input.readUTF() == key.contentHash && input.readUTF() == key.matcherVersion)
                    val corpus = input.readUTF()
                    val count = input.readInt()
                    require(count in 0..1000)
                    val proofs = (0 until count).associate { input.readUTF() to input.readUTF() }
                    val hidden = input.readBoolean()
                    val size = input.readInt()
                    require(size in -1..payloadLimit)
                    val checksum = input.readUTF()
                    val bytes = if (size < 0) null else ByteArray(size).also { input.readFully(it) }
                    hashing.on(false)
                    val trailer = ByteArray(32).also { input.readFully(it) }
                    require(hash.digest().contentEquals(trailer) && input.read() == -1)
                    require(digest(bytes ?: byteArrayOf()) == checksum)
                    val decision = Decision(hidden, bytes)
                    val sameCorpus = corpus == key.corpusVersion
                    // Clean requires the whole applicable corpus. Positive evidence survives unrelated additions.
                    require(sameCorpus || decision.matched && proofs.isNotEmpty())
                    require(proofs.all { (hash, version) -> currentProofs[hash] == version })
                    file.setLastModified(clock())
                    Hit(decision, !sameCorpus && !hidden)
                }
            }.getOrNull()
            if (hit != null) return hit
        }
        return null
    }

    /** Failed/incomplete/cancelled checks can never become a persistent clean verdict. */
    fun write(key: Key, outcome: Outcome): Boolean {
        if (outcome !is Outcome.Complete) return false
        val result = outcome.decision
        if ((result.rendered?.size ?: 0) > payloadLimit || outcome.proofs.size > 1000) return false
        if (result.matched && outcome.proofs.isEmpty()) return false
        if (!outcome.proofs.all { (hash, version) ->
                hash.matches(Regex("[0-9a-f]{64}")) && version.matches(Regex("[0-9a-f]{64}"))
            }
        ) {
            return false
        }
        return synchronized(writer) {
            var temporary: File? = null
            runCatching {
                check(directory.isDirectory || directory.mkdirs())
                if (availableBytes() < (result.rendered?.size ?: 0) + 16L * 1024 * 1024) return@runCatching false
                val target = File(directory, "${key.fileToken()}.checked")
                val backup = File(directory, "${target.name}.bak")
                temporary = File.createTempFile("${key.fileToken()}.", ".pending", directory)
                val raw = FileOutputStream(temporary)
                val hash = MessageDigest.getInstance("SHA-256")
                DataOutputStream(DigestOutputStream(raw.buffered(), hash)).use { output ->
                    output.writeInt(0x52434431)
                    output.writeUTF(key.contentHash)
                    output.writeUTF(key.matcherVersion)
                    output.writeUTF(key.corpusVersion)
                    output.writeInt(outcome.proofs.size)
                    outcome.proofs.toSortedMap().forEach { (hash, version) ->
                        output.writeUTF(hash)
                        output.writeUTF(version)
                    }
                    output.writeBoolean(result.hidden)
                    output.writeInt(result.rendered?.size ?: -1)
                    output.writeUTF(digest(result.rendered ?: byteArrayOf()))
                    result.rendered?.let { output.write(it) }
                    output.flush()
                    raw.write(hash.digest())
                    raw.fd.sync()
                }
                if (requireNotNull(temporary).length() > byteLimit) return@runCatching false
                if (target.exists()) {
                    if (backup.exists() && !backup.delete()) return@runCatching false
                    if (!target.renameTo(backup)) return@runCatching false
                }
                if (!requireNotNull(temporary).renameTo(target)) {
                    if (!target.exists()) backup.renameTo(target)
                    return@runCatching false
                }
                target.setLastModified(clock())
                backup.delete()
                pruneLocked()
                target.isFile
            }.getOrDefault(false).also { temporary?.delete() }
        }
    }

    private fun pruneLocked() {
        val owned = directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("[0-9a-f]{64}\\.checked(\\.bak)?"))
        }.groupBy { it.name.substringBefore('.') }.values.sortedBy { group -> group.maxOf { it.lastModified() } }
        var bytes = owned.sumOf { group -> group.sumOf { it.length() } }
        var count = owned.size
        for (group in owned) {
            if (bytes <= byteLimit && count <= entryLimit) break
            for (file in group) {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
            if (group.none { it.exists() }) count--
        }
        directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("[0-9a-f]{64}\\.-?[0-9]+\\.pending"))
        }.forEach { it.delete() }
    }

    companion object {
        fun digest(bytes: ByteArray): String {
            val digits = "0123456789abcdef"
            return buildString(64) {
                for (b in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    val n = b.toInt() and 255
                    append(digits[n ushr 4])
                    append(digits[n and 15])
                }
            }
        }
    }
}
