package eu.kanade.tachiyomi.ui.reader.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Explicit choices are private, content-addressed and chapter-scoped; never evict a user's override. */
internal class ReaderChapterReviewStore(private val file: File, private val limit: Int = 4096) {
    enum class Choice { KEEP, HIDE }
    private var loaded: MutableMap<String, Choice>? = null

    @Synchronized fun get(chapter: String, hash: String): Choice? = entries()[key(chapter, hash)]

    @Synchronized fun set(chapter: String, hash: String, choice: Choice?) {
        val key = key(chapter, hash)
        val current = entries()
        if (current[key] == choice) return
        val next = current.toMutableMap().apply { if (choice == null) remove(key) else put(key, choice) }
        check(next.size <= limit) { "Chapter review storage is full; existing choices were preserved" }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pending = file.parentFile!!.listFiles().orEmpty().filter {
            it.name.startsWith("chapter-review-") && it.name.endsWith(".pending")
        }
        check(pending.size < 4 && pending.sumOf { it.length() } + next.size * 80L + 8 <= 1024 * 1024) {
            "Interrupted chapter choices need recovery; existing choices were preserved"
        }
        val temporary = File.createTempFile("chapter-review-", ".pending", file.parentFile)
        try {
            val stream = FileOutputStream(temporary)
            DataOutputStream(stream.buffered()).use { output ->
                output.writeInt(0x52435231)
                output.writeInt(next.size)
                next.toSortedMap().forEach { (id, value) ->
                    output.writeUTF(id)
                    output.writeByte(value.ordinal)
                }
                output.flush()
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            loaded = next
        } finally {
            temporary.delete()
        }
    }

    private fun entries(): MutableMap<String, Choice> {
        loaded?.let { return it }
        val result = mutableMapOf<String, Choice>()
        if (file.exists()) {
            require(file.length() in 8..(8L + limit * 80L)) { "Chapter review storage is damaged" }
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == 0x52435231)
                val count = input.readInt().also { require(it in 0..limit) }
                repeat(count) {
                    val key = input.readUTF().also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                    val choice = input.readUnsignedByte().also { require(it in Choice.entries.indices) }
                    require(result.put(key, Choice.entries[choice]) == null)
                }
                require(input.read() == -1)
            }
        }
        return result.also { loaded = it }
    }

    private fun key(chapter: String, hash: String): String {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return MessageDigest.getInstance("SHA-256").digest((chapter + ":" + hash).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
