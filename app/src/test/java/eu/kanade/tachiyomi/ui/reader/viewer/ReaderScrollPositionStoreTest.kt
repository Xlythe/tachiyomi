package eu.kanade.tachiyomi.ui.reader.viewer

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReaderScrollPositionStoreTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var store: ReaderScrollPositionStore
    private var time = 1000L

    @Before fun setup() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
            "scroll-position-store-tests",
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        store = ReaderScrollPositionStore(preferences, limit = 2, clock = { time++ })
    }

    @Test fun roundTripRetainsSourcePageSplitFractionAndLegacyProgressStampSeparately() {
        store.write(chapter(47), position(47))
        val saved = requireNotNull(ReaderScrollPositionStore(preferences).read(chapter(47)))
        assertEquals(ReaderScrollAnchor.Key(47, 2, 1), saved.anchor.key)
        assertEquals(0.5, saved.anchor.fraction, 0.0)
        assertEquals(4, saved.expectedPage) // Last end-visible page can differ from the top visible anchor.
        assertTrue(saved.matches(4, 10, false, "/page/2"))
    }

    @Test fun changingChapterMangaOrSourceIdentityCannotReuseAnotherBookmark() {
        store.write(chapter(47), position(47))
        assertNull(store.read(chapter(48)))
        assertNull(store.read(chapter(47).copy(mangaId = 73)))
        assertNull(store.read(chapter(47).copy(url = "/chapter/replaced")))
    }

    @Test fun changedProgressReadStatePageCountAndKnownPageUrlInvalidateTheCheckpoint() {
        store.write(chapter(47), position(47))
        val saved = requireNotNull(store.read(chapter(47)))
        assertFalse(saved.matches(5, 10, false, "/page/2"))
        assertFalse(saved.matches(4, 11, false, "/page/2"))
        assertFalse(saved.matches(4, 10, true, "/page/2"))
        assertFalse(saved.matches(4, 10, false, "/page/new"))
        assertTrue(saved.matches(4, 10, false, "")) // Downloaded metadata may omit an online page URL.
    }

    @Test fun downloadedPagesWithNoLocatorAndPreciseFractionsRoundTrip() {
        val point = position(47).copy(
            anchor = ReaderScrollAnchor(ReaderScrollAnchor.Key(47, 2), 0.1234567890123, 0.0),
            pageUrlHash = null,
        )
        store.write(chapter(47), point)
        val saved = requireNotNull(store.read(chapter(47)))
        assertEquals(point.anchor, saved.anchor)
        assertNull(saved.pageUrlHash)
    }

    @Test fun evictionIsBoundedAndDoesNotModifyForeignPreferences() {
        preferences.edit().putString("unrelated-setting", "preserved").commit()
        store.write(chapter(47), position(47))
        store.write(chapter(48), position(48))
        store.write(chapter(47), position(47).copy(wasRead = true))
        store.write(chapter(49), position(49))
        assertEquals(2, preferences.all.keys.count { it.startsWith("chapter:") })
        assertNotNull(store.read(chapter(47)))
        assertNull(store.read(chapter(48)))
        assertNotNull(store.read(chapter(49)))
        assertEquals("preserved", preferences.getString("unrelated-setting", null))
    }

    @Test fun clocksMovingBackwardsDoNotEvictTheMostRecentlyWrittenPoint() {
        store.write(chapter(47), position(47))
        store.write(chapter(48), position(48))
        time = 1
        store.write(chapter(47), position(47))
        store.write(chapter(49), position(49))
        assertNotNull(store.read(chapter(47)))
        assertNull(store.read(chapter(48)))
    }

    @Test fun malformedOrFutureRecordsFailSoftlyWithoutClearingOtherPositions() {
        store.write(chapter(47), position(47))
        val key = preferences.all.keys.single()
        store.write(chapter(48), position(48))
        for (value in listOf("broken", "2|future", "1|2|1|NaN|0.0|4|10|0|1000|", "1|2|9|0.5|0|4|10|0|1000|")) {
            preferences.edit().putString(key, value).commit()
            assertNull(store.read(chapter(47)))
            assertNotNull(store.read(chapter(48)))
        }
    }

    @Test fun invalidScopeAndPageRangesCannotBeWritten() {
        assertThrows(IllegalArgumentException::class.java) { store.write(chapter(48), position(47)) }
        assertThrows(IllegalArgumentException::class.java) { position(47).copy(expectedPage = 10) }
        assertThrows(IllegalArgumentException::class.java) { position(47).copy(pageCount = 2) }
        assertThrows(IllegalArgumentException::class.java) { ReaderScrollPositionStore(preferences, limit = 0) }
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun removingOneBookmarkLeavesOtherChapterAndLegacyDataUntouched() {
        store.write(chapter(47), position(47))
        store.write(chapter(48), position(48))
        store.remove(chapter(47))
        assertNull(store.read(chapter(47)))
        assertNotNull(store.read(chapter(48)))
    }

    @Test fun separateWritersStillRespectTheSharedBound() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.invokeAll(
                (47L..66L).map { id ->
                    Callable { ReaderScrollPositionStore(preferences, limit = 2).write(chapter(id), position(id)) }
                },
            ).forEach { it.get() }
            assertEquals(2, preferences.all.keys.count { it.startsWith("chapter:") })
        } finally { executor.shutdown() }
    }

    @Test fun aColdAndroidPreferenceInstanceReadsTheCheckpointFromItsActualXmlFile() {
        store.write(chapter(47), position(47))
        ReflectionHelpers.callStaticMethod<Void>(Class.forName("android.app.QueuedWork"), "waitToFinish")
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.applicationInfo.dataDir, "shared_prefs/scroll-position-store-tests.xml")
        assertTrue("A real persisted XML file is required, not merely a warm preferences cache", file.isFile)
        @Suppress("UNCHECKED_CAST")
        val implementation = Class.forName("android.app.SharedPreferencesImpl") as Class<SharedPreferences>
        val cold = ReflectionHelpers.callConstructor(
            implementation,
            ClassParameter.from(File::class.java, file),
            ClassParameter.from(Int::class.javaPrimitiveType!!, Context.MODE_PRIVATE),
        )
        assertEquals(
            requireNotNull(store.read(chapter(47))).anchor,
            ReaderScrollPositionStore(cold).read(chapter(47))?.anchor,
        )
    }

    private fun chapter(id: Long) = ReaderScrollPositionStore.Chapter(72, id, "/chapter/$id")

    private fun position(id: Long) = ReaderScrollPositionStore.Position(
        ReaderScrollAnchor(ReaderScrollAnchor.Key(id, 2, 1), 0.5, 0.0),
        expectedPage = 4,
        pageCount = 10,
        wasRead = false,
        pageUrlHash = ReaderScrollPositionStore.hashPageUrl("/page/2"),
    )
}
