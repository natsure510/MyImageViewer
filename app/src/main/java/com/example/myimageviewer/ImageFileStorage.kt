package com.example.myimageviewer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keeps the previous image intact until the complete replacement has been validated. */
internal class ImageFileStorage(private val directory: File) {
    val imageFile: File get() = File(directory, "selected-image")

    fun <T> replace(openSource: () -> InputStream, validate: (File) -> T): T {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create image directory")
        }
        val pending = File.createTempFile("image-", ".pending", directory)
        try {
            openSource().use { input ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_FILE_BYTES) throw IOException("Image exceeds size limit")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val image = validate(pending)
            // Both paths are in the same internal directory. No delete-before-replace window.
            Files.move(pending.toPath(), imageFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return image
        } finally {
            pending.delete()
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}
