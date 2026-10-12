package com.example.myimageviewer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.scale
import java.util.concurrent.Executors

/** The host lays out a fitCenter ImageView at any size; no URI is shared with the launcher. */
class ImageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateAsync(context, intArrayOf(appWidgetId))
    }

    private fun updateAsync(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            var bitmap: Bitmap? = null
            try {
                bitmap = loadWidgetImage(app)
                AppWidgetManager.getInstance(app).updateAppWidget(ids, imageWidgetViews(app, bitmap))
            } catch (_: Exception) {
                showError(app, ids)
            } catch (_: OutOfMemoryError) {
                showError(app, ids)
            } finally {
                bitmap?.recycle()
                pending.finish()
            }
        }
    }

    private fun showError(context: Context, ids: IntArray) {
        try {
            AppWidgetManager.getInstance(context).updateAppWidget(
                ids, imageWidgetViews(context, null, R.string.widget_load_error),
            )
        } catch (_: RuntimeException) {
            // A removed/unavailable host must not crash the app or leave the broadcast pending.
            Log.w("ImageWidget", "Widget host could not be updated")
        }
    }

    companion object {
        // Serialize reads and publications so a resize cannot publish an older image last.
        private val worker = Executors.newSingleThreadExecutor()

        internal fun requestUpdate(context: Context) {
            try {
                val component = ComponentName(context, ImageWidgetProvider::class.java)
                val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                        this.component = component
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    })
                }
            } catch (_: RuntimeException) {
                // The image has already been saved; do not report it as a failed import.
                Log.w("ImageWidget", "Widget refresh could not be requested")
            }
        }
    }
}

/** Runs on the worker; the existing decoder also handles EXIF and caps its preview at 2048px. */
internal fun loadWidgetImage(context: Context): Bitmap? {
    val source = ImageRepository(context).load() ?: return null
    var scaled: Bitmap? = null
    var result: Bitmap? = null
    try {
        val display = context.resources.displayMetrics
        val size = widgetImageSize(source.width, source.height,
            maxOf(1, display.widthPixels), maxOf(1, display.heightPixels))
        scaled = source.scale(size.width, size.height)
        // Keep the RemoteViews memory budget predictable, including HDR source images.
        result = if (scaled.config == Bitmap.Config.ARGB_8888) scaled else
            checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false))
        return result
    } finally {
        if (source !== result) source.recycle()
        if (scaled !== source && scaled !== result) scaled?.recycle()
    }
}

internal fun imageWidgetViews(
    context: Context,
    bitmap: Bitmap?,
    message: Int = R.string.widget_select_image,
): RemoteViews = RemoteViews(context.packageName, R.layout.image_widget).apply {
    setImageViewBitmap(R.id.widget_image, bitmap)
    setViewVisibility(R.id.widget_image, if (bitmap == null) View.GONE else View.VISIBLE)
    setViewVisibility(R.id.widget_message, if (bitmap == null) View.VISIBLE else View.GONE)
    setTextViewText(R.id.widget_message, context.getString(message))
    setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ))
}
