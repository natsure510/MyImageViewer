package com.example.myimageviewer

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImageRepositoryTest {
    @get:Rule val temporary = TemporaryFolder(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
    )
    private val context get() = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        override fun getFilesDir(): File = temporary.root
    }

    @Test fun savedImageLoadsAfterSourceIsDeletedAndRepositoryIsRecreated() {
        val source = createImage(40, 80)
        ImageRepository(context).import(Uri.fromFile(source)).recycle()
        assertTrue(source.delete())
        val restored = ImageRepository(context).load()!!
        assertEquals(40, restored.width)
        assertEquals(80, restored.height)
        assertEquals(Color.RED, restored.getPixel(0, 0))
        restored.recycle()
    }

    @Test fun corruptReplacementPreservesSavedImage() {
        ImageRepository(context).import(Uri.fromFile(createImage(40, 80))).recycle()
        val invalid = temporary.newFile().apply { writeText("not an image") }
        assertThrows(IOException::class.java) { ImageRepository(context).import(Uri.fromFile(invalid)) }
        val restored = ImageRepository(context).load()!!
        assertEquals(40, restored.width)
        restored.recycle()
    }

    @Test fun largeImageIsDownsampled() {
        val bitmap = ImageRepository(context).import(Uri.fromFile(createImage(4096, 3072)))
        assertTrue(bitmap.width <= 2048)
        assertTrue(bitmap.height <= 2048)
        assertEquals(4f / 3f, bitmap.width.toFloat() / bitmap.height, 0.01f)
        bitmap.recycle()
    }

    @Test fun exifRotationIsApplied() {
        val source = createImage(40, 80, Bitmap.CompressFormat.JPEG)
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bitmap = ImageRepository(context).import(Uri.fromFile(source))
        assertEquals(80, bitmap.width)
        assertEquals(40, bitmap.height)
        bitmap.recycle()
    }

    private fun createImage(width: Int, height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val file = temporary.newFile()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
