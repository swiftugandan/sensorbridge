package dev.sensorbridge.ui.camera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sensorbridge.R
import dev.sensorbridge.domain.vision.Lens
import dev.sensorbridge.ui.components.permissionLabel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateUp: () -> Unit,
    viewModel: CameraViewModel = viewModel(factory = CameraViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val flash = remember { Animatable(0f) }
    val inkAndPaper = inkAndPaper()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionsChanged()
    }
    val save = { viewModel.saveSketch(inkAndPaper.first, inkAndPaper.second) }
    // Android 8–9 only: writing to Pictures needs the storage permission; later versions need none.
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save()
    }
    val requestSave = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save()
        }
    }

    val savedMessage = stringResource(R.string.camera_saved, "%s")
    val failedMessage = stringResource(R.string.camera_save_failed, "%s")
    val viewLabel = stringResource(R.string.camera_view)
    val noViewer = stringResource(R.string.camera_no_viewer)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SaveEvent.Saved -> {
                    launch {
                        // Shutter feedback: a quick white flash over the canvas.
                        flash.snapTo(0.8f)
                        flash.animateTo(0f, tween(FLASH_MS))
                    }
                    val result = snackbar.showSnackbar(savedMessage.format(event.location), actionLabel = viewLabel, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(event.uri, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(view)
                        } catch (_: ActivityNotFoundException) {
                            snackbar.showSnackbar(noViewer)
                        }
                    }
                }
                is SaveEvent.Failed -> snackbar.showSnackbar(failedMessage.format(event.message))
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionsChanged()
        onPauseOrDispose { }
    }
    val live = state.live

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.camera_title))
                        Text(
                            subtitle(state.lens, (live as? CameraLive.Streaming)?.fps, state.paused),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    if (state.lenses.size > 1) {
                        IconButton(onClick = viewModel::switchLens) {
                            Icon(Icons.Outlined.Cameraswitch, contentDescription = stringResource(R.string.camera_switch))
                        }
                    }
                    IconButton(onClick = viewModel::togglePaused) {
                        Icon(
                            if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = stringResource(if (state.paused) R.string.resume else R.string.pause),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // No scrolling: the sketch takes whatever height the controls leave, so everything fits on one screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (live) {
                is CameraLive.NeedsPermission -> PermissionCard(live.permission) { permissionLauncher.launch(live.permission) }
                is CameraLive.Failed -> ErrorCard(stringResource(R.string.sensor_error, live.message))
                else -> Unit
            }
            SketchCanvas(live, inkAndPaper, flash.value, Modifier.weight(1f).fillMaxWidth())
            ControlsCard(
                detail = state.params.detail,
                lineWeight = state.params.lineWeight,
                onDetailChange = viewModel::setDetail,
                onLineWeightChange = viewModel::setLineWeight,
                canSave = live is CameraLive.Streaming && !state.saving,
                saving = state.saving,
                onSave = requestSave,
            )
        }
    }
}

/** Graphite on warm paper by day; chalk on slate by night. */
@Composable
private fun inkAndPaper(): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) DarkInk to DarkPaper else LightInk to LightPaper
}

@Composable
private fun SketchCanvas(live: CameraLive, inkAndPaper: Pair<Color, Color>, flash: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (ink, paper) = inkAndPaper
    val tint = remember(ink, paper) { ColorFilter.colorMatrix(ColorMatrix(inkAndPaperMatrix(ink, paper))) }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (live) {
            is CameraLive.Streaming -> {
                // The largest box with the frame's shape that fits the space, so the drawing fills it edge to edge.
                Image(
                    bitmap = live.image,
                    contentDescription = stringResource(R.string.camera_canvas_description),
                    contentScale = ContentScale.Fit,
                    colorFilter = tint,
                    modifier = Modifier
                        .aspectRatio(live.image.width.toFloat() / live.image.height)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
                        .drawWithContent {
                            drawContent()
                            if (flash > 0f) drawRect(Color.White.copy(alpha = flash))
                        },
                )
            }
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(paper)
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (live == CameraLive.Waiting) {
                    Text(stringResource(R.string.camera_starting), style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun ControlsCard(
    detail: Float,
    lineWeight: Float,
    onDetailChange: (Float) -> Unit,
    onLineWeightChange: (Float) -> Unit,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            LabeledSlider(stringResource(R.string.camera_detail), detail, onDetailChange)
            LabeledSlider(stringResource(R.string.camera_line_weight), lineWeight, onLineWeightChange)
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                // Same width either way, so the button never resizes mid-save.
                Text(stringResource(if (saving) R.string.camera_saving else R.string.camera_save))
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermissionCard(permission: String, onGrant: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.permission_needed_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.permission_needed_body, permissionLabel(permission)), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.action_grant)) }
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun subtitle(lens: Lens, fps: Float?, paused: Boolean): String {
    val camera = stringResource(if (lens == Lens.BACK) R.string.camera_back else R.string.camera_front)
    return when {
        paused -> stringResource(R.string.camera_subtitle_paused, camera)
        fps != null -> stringResource(R.string.camera_subtitle_fps, camera, String.format(Locale.getDefault(), "%.0f", fps))
        else -> camera
    }
}

private const val FLASH_MS = 300

private val LightPaper = Color(0xFFFBF8F1)
private val LightInk = Color(0xFF2A2B2E)
private val DarkPaper = Color(0xFF15181A)
private val DarkInk = Color(0xFFE9E6DF)
