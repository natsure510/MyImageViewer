package com.example.myimageviewer

import org.junit.Assert.*
import org.junit.Test

class PreviewSampleSizeTest {
    @Test fun smallImageIsNotDownsampled() {
        assertEquals(1, previewSampleSize(1200, 1800))
        assertEquals(1, previewSampleSize(2048, 2048))
    }

    @Test fun largePortraitAndLandscapeFitMemoryBudget() {
        for ((width, height) in listOf(8000 to 6000, 6000 to 8000, 2049 to 1, Int.MAX_VALUE to 1)) {
            val sample = previewSampleSize(width, height)
            assertTrue((width.toLong() + sample - 1) / sample <= 2048)
            assertTrue((height.toLong() + sample - 1) / sample <= 2048)
            assertEquals(0, sample and (sample - 1))
        }
    }

    @Test fun invalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { previewSampleSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { previewSampleSize(100, -1) }
    }
}
