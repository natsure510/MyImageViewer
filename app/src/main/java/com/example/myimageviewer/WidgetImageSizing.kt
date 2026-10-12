package com.example.myimageviewer

import kotlin.math.min
import kotlin.math.sqrt

internal data class WidgetImageSize(val width: Int, val height: Int)

/** One ARGB_8888 bitmap, at most 4 MiB and below the widget host's 1.5-screen budget. */
internal fun widgetImageSize(
    width: Int,
    height: Int,
    displayWidth: Int,
    displayHeight: Int,
): WidgetImageSize {
    require(width > 0 && height > 0 && displayWidth > 0 && displayHeight > 0)
    val pixelBudget = min(displayWidth.toLong() * displayHeight, 1_048_576L)
    val scale = min(1.0, min(min(2048.0, pixelBudget.toDouble()) / maxOf(width, height),
        sqrt(pixelBudget.toDouble() / (width.toLong() * height))))
    return WidgetImageSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
}
