package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.R
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.xmlpull.v1.XmlPullParser

/** Uses the container declared by ReaderActivity's production layout, without source/database IO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReaderViewerContainerStateTest {
    @After fun cleanup() {
        unmockkAll()
        WebtoonRotationPositionTest.Host.width = 720
        WebtoonRotationPositionTest.Host.height = 1280
    }

    @Test fun chapterArrivingAfterActivityRestorationStillReceivesItsSavedImagePosition() {
        val original = Robolectric.buildActivity(WebtoonRotationPositionTest.Host::class.java).setup()
        val old = original.get()
        val originalContainer = productionContainer(old)
        (old.recycler.parent as ViewGroup).removeView(old.recycler)
        originalContainer.addView(old.recycler)
        old.setContentView(originalContainer)
        old.layoutReader()
        (old.recycler.layoutManager as WebtoonLayoutManager).scrollToPositionWithOffset(1, -6000)
        old.layoutReader()
        assertEquals(0.5, old.fraction(), 0.001)
        val saved = Bundle()
        original.pause().saveInstanceState(saved).stop().destroy()

        WebtoonRotationPositionTest.Host.width = 1280
        WebtoonRotationPositionTest.Host.height = 720
        val recreated = Robolectric.buildActivity(WebtoonRotationPositionTest.Host::class.java).create(saved).start()
        try {
            val host = recreated.get()
            (host.recycler.parent as ViewGroup).removeView(host.recycler)
            val emptyContainer = productionContainer(host)
            host.setContentView(emptyContainer)
            recreated.restoreInstanceState(saved).resume().visible()
            assertEquals(0, emptyContainer.childCount)
            // View-model initialization can finish after Android's view-state restoration callback.
            emptyContainer.addView(host.recycler)
            host.layoutReader()
            assertEquals("Late chapter data must not restart a long image", 0.5, host.fraction(), 0.001)
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun anotherRecreationWhileDataIsStillLoadingKeepsPendingState() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val initial = productionContainer(context)
        initial.addView(StatefulViewer(context).apply { value = 37 })
        val saved = SparseArray<Parcelable>()
        initial.saveHierarchyState(saved)
        val waiting = productionContainer(context)
        waiting.restoreHierarchyState(saved)
        val savedAgain = SparseArray<Parcelable>()
        waiting.saveHierarchyState(savedAgain)
        val resumed = productionContainer(context)
        resumed.restoreHierarchyState(savedAgain)
        val viewer = StatefulViewer(context)
        resumed.addView(viewer)
        assertEquals(37, viewer.value)
    }

    @Test fun anUnrelatedLoadingViewDoesNotConsumePendingViewerState() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val initial = productionContainer(context)
        initial.addView(StatefulViewer(context).apply { value = 37 })
        val saved = SparseArray<Parcelable>()
        initial.saveHierarchyState(saved)
        val resumed = productionContainer(context)
        resumed.restoreHierarchyState(saved)
        resumed.addView(View(context))
        val viewer = StatefulViewer(context)
        resumed.addView(viewer)
        assertEquals(37, viewer.value)
    }

    @Test fun switchingViewersLaterDoesNotReplayAConsumedSavedPosition() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val initial = productionContainer(context)
        initial.addView(StatefulViewer(context).apply { value = 37 })
        val saved = SparseArray<Parcelable>()
        initial.saveHierarchyState(saved)
        val resumed = productionContainer(context)
        resumed.restoreHierarchyState(saved)
        val first = StatefulViewer(context)
        resumed.addView(first)
        assertEquals(37, first.value)
        resumed.removeView(first)
        val next = StatefulViewer(context).apply { value = 99 }
        resumed.addView(next)
        assertEquals(99, next.value)
    }

    @Test fun anotherReaderModeDiscardsInapplicablePendingState() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val initial = productionContainer(context)
        initial.addView(StatefulViewer(context).apply { value = 37 })
        val saved = SparseArray<Parcelable>()
        initial.saveHierarchyState(saved)
        val resumed = productionContainer(context)
        resumed.restoreHierarchyState(saved)
        val pager = View(context).apply { id = R.id.reader_pager }
        resumed.addView(pager)
        resumed.removeView(pager)
        val next = StatefulViewer(context).apply { value = 99 }
        resumed.addView(next)
        assertEquals(99, next.value)
    }

    private class StatefulViewer(context: Context) : View(context) {
        var value = 0
        init { id = R.id.reader_webtoon }
        override fun onSaveInstanceState(): Parcelable {
            val parent = super.onSaveInstanceState()
            return Bundle().apply {
                putParcelable("parent", parent)
                putInt("value", value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onRestoreInstanceState(state: Parcelable?) {
            if (state is Bundle) {
                super.onRestoreInstanceState(state.getParcelable<Parcelable>("parent"))
                value = state.getInt("value")
            } else {
                super.onRestoreInstanceState(state)
            }
        }
    }

    private fun productionContainer(context: Context): FrameLayout {
        context.resources.getXml(R.layout.reader_activity).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val id = parser.getAttributeResourceValue(
                    "http://schemas.android.com/apk/res/android",
                    "id",
                    View.NO_ID,
                )
                if (id != R.id.viewer_container) continue
                val name = parser.name.let { if ('.' in it) it else "android.widget.$it" }
                return (Class.forName(name).getConstructor(Context::class.java).newInstance(context) as FrameLayout)
                    .apply { this.id = id }
            }
        }
        error("ReaderActivity must declare its viewer container")
    }
}
