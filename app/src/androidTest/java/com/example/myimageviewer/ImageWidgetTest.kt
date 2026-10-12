package com.example.myimageviewer

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageWidgetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)
    private val context get() = object : ContextWrapper(instrumentation.targetContext) {
        override fun getFilesDir(): File = temporary.root
    }

    @Test fun emptyAndErrorStatesHaveGuidanceAndCanBeTapped() {
        assertNull(loadWidgetImage(context))
        instrumentation.runOnMainSync {
            for (message in listOf(R.string.widget_select_image, R.string.widget_load_error)) {
                val view = imageWidgetViews(context, null, message).apply(context, FrameLayout(context))
                assertEquals(View.GONE, view.findViewById<ImageView>(R.id.widget_image).visibility)
                val text = view.findViewById<TextView>(R.id.widget_message)
                assertEquals(View.VISIBLE, text.visibility)
                assertEquals(context.getString(message), text.text)
                assertTrue(view.hasOnClickListeners())
            }
        }
    }

    @Test fun imageFitsWithoutCroppingAfterResizeAndAcrossWidgetInstances() {
        for ((sourceWidth, sourceHeight) in listOf(400 to 800, 800 to 400, 600 to 600)) {
            assertFitsAtDifferentSizes(sourceWidth, sourceHeight)
        }
    }

    private fun assertFitsAtDifferentSizes(sourceWidth: Int, sourceHeight: Int) {
        val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                // Two independent hosts must both lay out the entire same image.
                repeat(2) {
                    val root = imageWidgetViews(context, bitmap).apply(context, FrameLayout(context))
                    val image = root.findViewById<ImageView>(R.id.widget_image)
                    assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_message).visibility)
                    assertTrue(root.hasOnClickListeners())
                    for ((width, height) in listOf(120 to 120, 1000 to 300, 1080 to 2200)) {
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        assertEquals(width, image.width)
                        assertEquals(height, image.height)
                        assertEquals(0, image.paddingLeft + image.paddingRight + image.paddingTop + image.paddingBottom)
                        val drawable = image.drawable
                        val displayed = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
                        image.imageMatrix.mapRect(displayed)
                        assertTrue(displayed.left >= -0.1f && displayed.top >= -0.1f)
                        assertTrue(displayed.right <= width + 0.1f && displayed.bottom <= height + 0.1f)
                        assertEquals(sourceWidth.toFloat() / sourceHeight, displayed.width() / displayed.height(), 0.001f)
                        assertTrue(kotlin.math.abs(displayed.width() - width) < 1f ||
                            kotlin.math.abs(displayed.height() - height) < 1f)
                        assertEquals(width / 2f, displayed.centerX(), 0.6f)
                        assertEquals(height / 2f, displayed.centerY(), 0.6f)
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun widgetReloadsInternalImageAfterReplacementAndSourceDeletion() {
        for (color in listOf(Color.RED, Color.BLUE)) {
            val file = temporary.newFile()
            val original = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888)
            try {
                original.eraseColor(color)
                file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                original.recycle()
            }
            ImageRepository(context).import(Uri.fromFile(file)).recycle()
            assertTrue(file.delete())
            val widget = loadWidgetImage(context)!!
            try {
                assertFalse(widget.isRecycled)
                assertEquals(Bitmap.Config.ARGB_8888, widget.config)
                assertTrue(widget.allocationByteCount <= 4 * 1_048_576)
                assertEquals(2f / 3f, widget.width.toFloat() / widget.height, 0.01f)
                assertEquals(color, widget.getPixel(widget.width / 2, widget.height / 2))
            } finally {
                widget.recycle()
            }
        }
    }
}
