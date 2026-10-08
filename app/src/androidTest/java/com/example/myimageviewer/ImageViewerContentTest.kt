package com.example.myimageviewer

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.myimageviewer.ui.theme.MyImageViewerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ImageViewerContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyScreenOpensSelection() {
        var selections = 0
        compose.setContent {
            MyImageViewerTheme {
                ImageViewerContent(ImageViewerState(busy = false), { selections++ }, {})
            }
        }
        compose.onNodeWithText("画像を選択").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun imageLongPressOpensReplacement() {
        var selections = 0
        val image = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MyImageViewerTheme {
                ImageViewerContent(ImageViewerState(image, busy = false), { selections++ }, {})
            }
        }
        compose.onNodeWithContentDescription("保存した画像。長押しで別の画像を選択できます。")
            .performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun failedReplacementShowsErrorAndKeepsImage() {
        val image = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)
        var dismissed = 0
        compose.setContent {
            MyImageViewerTheme {
                ImageViewerContent(ImageViewerState(image, busy = false, error = R.string.save_error),
                    {}, { dismissed++ })
            }
        }
        compose.onNodeWithText("画像を表示できませんでした").assertExists()
        compose.onNodeWithContentDescription("保存した画像。長押しで別の画像を選択できます。")
            .assertExists()
        compose.onNodeWithText("閉じる").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
