package com.example.myimageviewer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ImageFileStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = temporary.root
    private val storage get() = ImageFileStorage(directory)

    @Test fun replacementSurvivesNewStorageInstance() {
        storage.replace({ "first".byteInputStream() }) { it.readText() }
        val result = storage.replace({ "second".byteInputStream() }) { it.readText() }
        assertEquals("second", result)
        assertEquals("second", ImageFileStorage(directory).imageFile.readText())
        assertNoPendingFiles()
    }

    @Test fun invalidReplacementKeepsPreviousImage() {
        storage.imageFile.writeText("original")
        assertThrows(IOException::class.java) {
            storage.replace({ "invalid".byteInputStream() }) { throw IOException("Bad image") }
        }
        assertEquals("original", storage.imageFile.readText())
        assertNoPendingFiles()
    }

    @Test fun interruptedCopyKeepsPreviousImageAndClosesSource() {
        storage.imageFile.writeText("original")
        var closed = false
        val source = object : InputStream() {
            var reads = 0
            override fun read(): Int = error("Use bulk read")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("Provider interrupted")
                buffer[offset] = 1
                return 1
            }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) {
            storage.replace({ source }) { fail("Must not decode incomplete copy") }
        }
        assertTrue(closed)
        assertEquals("original", storage.imageFile.readText())
        assertNoPendingFiles()
    }

    @Test fun unavailableSourceKeepsPreviousImage() {
        storage.imageFile.writeText("original")
        assertThrows(SecurityException::class.java) {
            storage.replace({ throw SecurityException("Access denied") }) { it.readText() }
        }
        assertEquals("original", storage.imageFile.readText())
        assertNoPendingFiles()
    }

    @Test fun failedFirstImportLeavesNoSavedImage() {
        assertThrows(IOException::class.java) {
            storage.replace({ ByteArrayInputStream(byteArrayOf()) }) { throw IOException("Empty") }
        }
        assertFalse(storage.imageFile.exists())
        assertNoPendingFiles()
    }

    @Test fun oversizedSourceIsRejectedBeforeValidation() {
        storage.imageFile.writeText("original")
        val source = object : InputStream() {
            var remaining = ImageFileStorage.MAX_FILE_BYTES + 1
            override fun read(): Int = error("Use bulk read")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                remaining -= count
                return count
            }
        }
        assertThrows(IOException::class.java) {
            storage.replace({ source }) { fail("Oversized image must not be decoded") }
        }
        assertEquals("original", storage.imageFile.readText())
        assertNoPendingFiles()
    }

    @Test fun commitFailureCleansUpPendingFile() {
        assertTrue(storage.imageFile.mkdir())
        assertThrows(IOException::class.java) {
            storage.replace({ "image".byteInputStream() }) { it.readText() }
        }
        assertTrue(storage.imageFile.isDirectory)
        assertNoPendingFiles()
    }

    private fun assertNoPendingFiles() {
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".pending") })
    }
}
