package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderPageImageViewTest {
    @Test
    fun outOfOrderDecodeSuccessAndErrorCannotAffectReboundOrRecycledImage() {
        val bitmap = Bitmap.createBitmap(24, 48, Bitmap.Config.ARGB_8888)
        val raw = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ReaderPageImageView(InstrumentationRegistry.getInstrumentation().targetContext)
            var loaded = 0
            var errors = 0
            view.onImageLoaded = { loaded++ }
            view.onImageLoadError = { errors++ }
            val config = ReaderPageImageView.Config(zoomDuration = 0)
            try {
                view.setImage(raw.inputStream(), false, config)
                val old = listener(view)
                view.setImage(raw.inputStream(), false, config)
                val current = listener(view)
                old.onReady()
                old.onImageLoadError(IllegalStateException("Delayed previous image"))
                assertEquals(0, loaded)
                assertEquals(0, errors)
                current.onImageLoadError(IllegalStateException("Current error"))
                assertEquals(1, errors)
                view.recycle()
                current.onReady()
                current.onImageLoadError(IllegalStateException("Delayed after recycle"))
                assertEquals(0, loaded)
                assertEquals(1, errors)
            } catch (error: Throwable) {
                failure = error
            } finally {
                view.recycle()
            }
        }
        failure?.let { throw AssertionError("Stale image callback affected current image", it) }
    }

    private fun listener(view: ReaderPageImageView): SubsamplingScaleImageView.OnImageEventListener {
        val child = ReaderPageImageView::class.java.getDeclaredField("pageView").apply { isAccessible = true }.get(view)
        val field = SubsamplingScaleImageView::class.java.declaredFields.first {
            SubsamplingScaleImageView.OnImageEventListener::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        return field.get(child) as SubsamplingScaleImageView.OnImageEventListener
    }
}
