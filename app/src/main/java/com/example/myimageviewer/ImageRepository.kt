package com.example.myimageviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException

/** Called only on an IO dispatcher. The original stays on disk; only a preview is decoded. */
internal class ImageRepository(context: Context) {
    private val resolver = context.contentResolver
    private val storage = ImageFileStorage(File(context.filesDir, "images"))

    fun load(): Bitmap? = if (storage.imageFile.exists()) decode(storage.imageFile) else null

    fun import(uri: Uri): Bitmap = storage.replace(
        openSource = { resolver.openInputStream(uri) ?: throw IOException("Cannot open image") },
        validate = ::decode,
    )

    private fun decode(file: File): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val sample = previewSampleSize(info.size.width, info.size.height)
                decoder.setTargetSampleSize(sample)
                // ImageDecoder applies EXIF orientation and rejects incomplete images by default.
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image")
        val options = BitmapFactory.Options().apply {
            inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Invalid image")
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL // Formats without EXIF can still be displayed.
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } finally {
            bitmap.recycle()
        }
    }
}

/** Powers of two also work with BitmapFactory on API 26/27. At most ~16 MiB for ARGB_8888. */
internal fun previewSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while ((maxOf(width, height).toLong() + sample - 1) / sample > 2048) sample *= 2
    return sample
}
