package com.example.myimageviewer

import org.junit.Assert.*
import org.junit.Test

class WidgetImageSizingTest {
    @Test fun smallImageIsNotUpscaled() {
        assertEquals(WidgetImageSize(40, 80), widgetImageSize(40, 80, 1080, 2400))
    }

    @Test fun largeImagesFitMemoryBudgetAndKeepAspectRatio() {
        for ((width, height) in listOf(8000 to 6000, 6000 to 8000, 4096 to 4096, 10000 to 100)) {
            val size = widgetImageSize(width, height, 1080, 2400)
            assertTrue(size.width.toLong() * size.height <= 1_048_576)
            assertTrue(maxOf(size.width, size.height) <= 2048)
            assertEquals(width.toDouble() / height, size.width.toDouble() / size.height,
                (width.toDouble() / height) * 0.03)
        }
    }

    @Test fun smallDisplayLimitsBitmapBelowHostBudget() {
        val size = widgetImageSize(2048, 2048, 320, 480)
        assertTrue(size.width * size.height <= 320 * 480)
    }

    @Test fun extremeDimensionsDoNotOverflowOrProduceZero() {
        val size = widgetImageSize(Int.MAX_VALUE, 1, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(WidgetImageSize(2048, 1), size)
    }

    @Test fun tinyDisplayAndThinImageStillRespectBudget() {
        assertEquals(WidgetImageSize(1, 1), widgetImageSize(2048, 1, 1, 1))
    }

    @Test fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { widgetImageSize(0, 10, 1080, 2400) }
    }
}
