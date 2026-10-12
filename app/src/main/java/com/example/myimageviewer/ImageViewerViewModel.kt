package com.example.myimageviewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ImageViewerState(
    val image: Bitmap? = null,
    val busy: Boolean = true,
    val error: Int? = null,
)

internal class ImageViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ImageRepository(application)
    private val mutableState = MutableStateFlow(ImageViewerState())
    val state = mutableState.asStateFlow()

    private val initialLoad = viewModelScope.launch {
        readImage(R.string.load_error) { repository.load() }
    }
    private var importing = false

    fun selectImage(uri: Uri?) {
        // A dismissed picker must not clear either the on-screen or stored image.
        if (uri == null || importing) return
        importing = true
        viewModelScope.launch {
            // A picker result restored after process death may arrive during the initial read.
            initialLoad.join()
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                readImage(R.string.save_error) {
                    repository.import(uri).also { ImageWidgetProvider.requestUpdate(getApplication()) }
                }
            } finally {
                importing = false
            }
        }
    }

    fun dismissError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun pickerFailed() {
        mutableState.value = mutableState.value.copy(error = R.string.picker_error)
    }

    private suspend fun readImage(errorMessage: Int, action: () -> Bitmap?) {
        try {
            val image = withContext(Dispatchers.IO) { action() }
            mutableState.value = ImageViewerState(image = image, busy = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(busy = false, error = errorMessage)
        } catch (_: OutOfMemoryError) {
            mutableState.value = mutableState.value.copy(busy = false, error = R.string.memory_error)
        }
    }
}
