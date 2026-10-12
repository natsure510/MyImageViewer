package com.example.myimageviewer

import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.example.myimageviewer.ui.theme.MyImageViewerTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        // Also recover a missed update after process termination or a force-stop/relaunch.
        ImageWidgetProvider.requestUpdate(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this)[ImageViewerViewModel::class.java]
        setContent {
            MyImageViewerTheme { ImageViewer(viewModel) }
        }
    }
}

@Composable
private fun ImageViewer(viewModel: ImageViewerViewModel) {
    val state by viewModel.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        viewModel.selectImage(it)
    }
    val selectImage: () -> Unit = {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: RuntimeException) {
            viewModel.pickerFailed()
        }
    }
    ImageViewerContent(state, selectImage, viewModel::dismissError)
}

@Composable
internal fun ImageViewerContent(
    state: ImageViewerState,
    selectImage: () -> Unit,
    dismissError: () -> Unit,
) {
    val view = LocalView.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = state.image == null && !darkTheme
                isAppearanceLightNavigationBars = state.image == null && !darkTheme
            }
        }
    }
    val changeLabel = stringResource(R.string.change_image)
    Box(
        modifier = Modifier.fillMaxSize()
            .background(if (state.image != null) Color.Black else MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (state.image != null) {
            Image(
                bitmap = state.image.asImageBitmap(),
                contentDescription = stringResource(R.string.image_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().safeDrawingPadding().combinedClickable(
                    enabled = !state.busy,
                    onClick = {},
                    onLongClickLabel = changeLabel,
                    onLongClick = selectImage,
                ),
            )
        } else if (!state.busy) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(onClick = selectImage) { Text(stringResource(R.string.select_image)) }
                Text(stringResource(R.string.selection_hint), color = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (state.busy) {
            val loading = stringResource(R.string.loading_image)
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loading })
        }
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = dismissError,
            title = { Text(stringResource(R.string.image_error_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = dismissError) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
