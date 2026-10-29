package eu.kanade.tachiyomi.ui.reader

import android.util.AtomicFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.filter.ReaderImageRules
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual ViewModel/domain write wiring to an isolated durable fixture repository, never the user's DB. */
class ReaderCompletionPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun runCase(hidden: Set<Int>, pending: Set<Int>, end: Int, expected: Int?) {
        val root = File(context.cacheDir, "reader-completion-fixture-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val file = AtomicFile(File(root, "progress.json"))
        val written = CountDownLatch(1)
        val repo = Proxy.newProxyInstance(
            ChapterRepository::class.java.classLoader,
            arrayOf(ChapterRepository::class.java),
        ) { _, method, args ->
            check(method.name == "update") { "Unexpected repository call: " + method.name }
            val update = args!![0] as ChapterUpdate
            val output = file.startWrite()
            try {
                output.write(
                    JSONObject().put("id", update.id).put("read", update.read)
                        .put("lastPageRead", update.lastPageRead).toString().toByteArray(),
                )
                file.finishWrite(output)
            } catch (e: Throwable) {
                file.failWrite(output)
                throw e
            }
            written.countDown()
            Unit
        } as ChapterRepository
        val store = ViewModelStore()
        val readerChapter = ReaderChapter(
            ChapterImpl().apply {
                id = 471L
                manga_id = 72L
                name = "Fixture"
            },
        )
        readerChapter.state = ReaderChapter.State.Loaded(
            (0..3).map { index ->
                ReaderPage(index).apply {
                    this.chapter = readerChapter
                    status = if (index in pending) Page.State.QUEUE else Page.State.READY
                    applyRuleVisibility(index in hidden)
                    if (index !in pending) markWebtoonImageReady(ReaderImageRules.revision.value)
                }
            },
        )
        try {
            instrumentation.runOnMainSync {
                val preferences = InMemoryPreferenceStore()
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(
                        SavedStateHandle(),
                        preferences = BasePreferences(context, preferences),
                        readerPreferences = ReaderPreferences(preferences),
                        downloadPreferences = DownloadPreferences(preferences),
                        updateChapter = UpdateChapter(repo),
                    ) as T
                }
                val vm = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
                vm.onVisibleChapterEnd(readerChapter, end)
            }
            instrumentation.waitForIdleSync()
            if (expected == null) {
                assertFalse("Pending/unreached content must not be marked read", readerChapter.chapter.read)
                assertFalse(file.baseFile.exists())
            } else {
                assertTrue(
                    "Completion must reach the actual domain persistence call",
                    written.await(2, TimeUnit.SECONDS),
                )
                assertTrue(readerChapter.chapter.read)
                assertEquals(expected, readerChapter.chapter.last_page_read)
                val reopened = JSONObject(file.openRead().use { it.reader().readText() })
                assertEquals(471L, reopened.getLong("id"))
                assertTrue(reopened.getBoolean("read"))
                assertEquals(expected.toLong(), reopened.getLong("lastPageRead"))
                assertEquals("Completion must not synthesize a selected UI page", 0, readerChapter.requestedPage)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            check(
                root.canonicalPath.startsWith(
                    context.cacheDir.canonicalPath + File.separator + "reader-completion-fixture-",
                ),
            )
            root.deleteRecursively()
        }
    }

    @Test fun croppedFinalPagePersistsReadAtItsOriginalIndexAndReopens() = runCase(emptySet(), emptySet(), 3, 3)

    @Test fun hiddenTrailingPagesPersistTheLastVisibleOriginalIndex() = runCase(setOf(2, 3), emptySet(), 1, 1)

    @Test fun allHiddenConfirmedPagesCanCompleteAtTheLastOriginalIndex() = runCase(setOf(0, 1, 2, 3), emptySet(), 3, 3)

    @Test fun pendingHiddenSuffixCannotComplete() = runCase(setOf(2, 3), setOf(3), 1, null)

    @Test fun ordinaryScrollingBeforeTheFinalVisiblePageCannotComplete() = runCase(emptySet(), emptySet(), 2, null)
}
