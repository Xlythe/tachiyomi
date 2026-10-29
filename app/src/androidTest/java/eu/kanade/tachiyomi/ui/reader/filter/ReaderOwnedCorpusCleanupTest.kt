package eu.kanade.tachiyomi.ui.reader.filter

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Only the isolated, explicitly supplied test corpus may be removed after an interrupted audit. */
class ReaderOwnedCorpusCleanupTest {
    @Test
    fun removeOnlyOwnedTestRules() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("readerCorpusCleanup") == "true")
        val owned = ZipFile(File(requireNotNull(args.getString("readerCorpus")))).use { zip ->
            val array = JSONArray(zip.getInputStream(zip.getEntry("rules.json")).bufferedReader().use { it.readText() })
            (0 until array.length()).map { array.getJSONObject(it).getString("sha256") }.toSet()
        }
        val current = JSONArray(ReaderImageRules.export())
        val hashes = (0 until current.length()).map { current.getJSONObject(it).getString("sha256") }
        assertTrue("Foreign rules must be preserved; inspect before cleanup", hashes.all { it in owned })
        hashes.forEach { ReaderImageRules.remove(it) }
        assertTrue(JSONArray(ReaderImageRules.export()).length() == 0)
    }
}
