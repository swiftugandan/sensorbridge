package dev.sensorbridge.ui.microphone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sensorbridge.R
import dev.sensorbridge.domain.audio.AudioLevel
import dev.sensorbridge.domain.audio.AudioSource
import dev.sensorbridge.domain.audio.MicrophoneInfo
import dev.sensorbridge.domain.audio.SpectrumFrame
import dev.sensorbridge.domain.audio.noteName
import dev.sensorbridge.ui.components.permissionLabel
import dev.sensorbridge.ui.theme.NumericStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicrophoneScreen(
    onNavigateUp: () -> Unit,
    viewModel: MicrophoneViewModel = viewModel(factory = MicrophoneViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionsChanged()
    }
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionsChanged()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.microphone_title))
                        state.info?.let {
                            Text(
                                describe(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val live = state.live
            when (live) {
                is MicrophoneLive.NeedsPermission -> PermissionCard(live.permission) { permissionLauncher.launch(live.permission) }
                is MicrophoneLive.Failed -> ErrorCard(stringResource(R.string.sensor_error, live.message))
                else -> Unit
            }
            val streaming = live as? MicrophoneLive.Streaming
            LevelCard(streaming?.level, streaming?.peak, streaming?.toneHz)
            SpectrogramCard(viewModel, state.scale, streaming?.total ?: viewModel.history.total)
            state.info?.let { DetailsCard(it, viewModel.frameSeconds) }
            Text(
                stringResource(R.string.microphone_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LevelCard(level: AudioLevel?, peak: SpectrumFrame.Peak?, toneHz: Float?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(stringResource(R.string.microphone_level))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    level?.let { String.format(Locale.getDefault(), "%.1f", it.rmsDbfs) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "dBFS",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // The clipping warning shares this line and only fades in and out, so nothing below it ever moves.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.microphone_loudness),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (level?.clipped == true) 1f else 0f),
                ) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.microphone_clipping), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            LevelBar(level)
            Spacer(Modifier.height(16.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.microphone_peak_frequency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        peak?.let { formatFrequency(it.frequencyHz, precise = true) } ?: "—",
                        style = MaterialTheme.typography.titleLarge.merge(NumericStyle),
                    )
                }
                Column(Modifier.weight(1f)) {
                    // Named only for a clear, steady tone; the label carries its frequency so the note is never
                    // mistaken for a name of the strongest frequency, which may be rumble.
                    Text(
                        toneHz?.let { stringResource(R.string.microphone_tone_with_frequency, formatFrequency(it, precise = true)) }
                            ?: stringResource(R.string.microphone_tone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        toneHz?.let(::noteName) ?: "—",
                        style = MaterialTheme.typography.titleLarge.merge(NumericStyle),
                    )
                }
            }
        }
    }
}

/** Horizontal meter from −60 to 0 dBFS: RMS as a filled bar, the held peak as a tick. */
@Composable
private fun LevelBar(level: AudioLevel?) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.onSurface
    val description = level?.let { String.format(Locale.getDefault(), "Level %.0f dBFS, peak %.0f dBFS", it.rmsDbfs, it.peakHoldDbfs) } ?: ""
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = description },
    ) {
        drawRect(track)
        if (level != null) {
            val rmsX = fraction(level.rmsDbfs) * size.width
            drawRoundRect(fill, size = Size(rmsX, size.height), cornerRadius = CornerRadius(6.dp.toPx()))
            val peakX = fraction(level.peakHoldDbfs) * size.width
            drawLine(tick, Offset(peakX, 0f), Offset(peakX, size.height), strokeWidth = 2.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("−60", "−40", "−20", "0").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall.merge(NumericStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fraction(db: Float): Float = ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)

private const val METER_FLOOR_DB = -60f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpectrogramCard(viewModel: MicrophoneViewModel, scale: FrequencyScale, total: Long) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val surface = MaterialTheme.colorScheme.surface
    val palette = remember(surface, dark) { SpectrogramPalette.forSurface(surface, dark) }
    val renderer = remember(palette, scale) {
        SpectrogramRenderer(viewModel.history, scale, palette, MicrophoneViewModel.FLOOR_DB, MicrophoneViewModel.CEILING_DB)
    }
    val seconds = (MicrophoneViewModel.HISTORY_COLUMNS * viewModel.frameSeconds).toInt()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.microphone_spectrogram), Modifier.weight(1f))
                Text(
                    stringResource(R.string.microphone_window, seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FrequencyScale.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == scale,
                        onClick = { viewModel.setScale(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, FrequencyScale.entries.size),
                        label = {
                            Text(stringResource(if (option == FrequencyScale.LOG) R.string.microphone_scale_log else R.string.microphone_scale_linear))
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Spectrogram(
                renderer = renderer,
                total = total,
                description = pluralStringResource(R.plurals.microphone_spectrogram_description, seconds, seconds),
                probeAt = viewModel::probe,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(8.dp))
            SpectrogramLegend(
                palette,
                MicrophoneViewModel.FLOOR_DB,
                MicrophoneViewModel.CEILING_DB,
                Modifier.fillMaxWidth().height(28.dp),
            )
            Text(
                stringResource(R.string.microphone_inspect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailsCard(info: MicrophoneInfo, frameSeconds: Float) {
    val binHz = info.sampleRate.toFloat() / MicrophoneViewModel.FFT_SIZE
    val rows = listOf(
        R.string.microphone_sample_rate to String.format(Locale.getDefault(), "%,d Hz", info.sampleRate),
        R.string.microphone_source to stringResource(sourceLabel(info.source)),
        R.string.microphone_fft to "${MicrophoneViewModel.FFT_SIZE}-point, Hann, 50% overlap",
        R.string.microphone_frequency_resolution to String.format(Locale.getDefault(), "%.1f Hz", binHz),
        R.string.microphone_time_resolution to String.format(Locale.getDefault(), "%.0f ms", frameSeconds * 1_000),
        R.string.microphone_range to String.format(Locale.getDefault(), "%.0f to %.0f dBFS", MicrophoneViewModel.FLOOR_DB, MicrophoneViewModel.CEILING_DB),
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(stringResource(R.string.details_heading))
            Spacer(Modifier.height(8.dp))
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
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
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.semantics { heading() })
}

@Composable
fun describe(info: MicrophoneInfo): String =
    stringResource(R.string.microphone_summary, info.sampleRate / 1_000f, stringResource(sourceLabel(info.source)).lowercase(Locale.getDefault()))

private fun sourceLabel(source: AudioSource): Int = when (source) {
    AudioSource.UNPROCESSED -> R.string.microphone_source_unprocessed
    AudioSource.VOICE_RECOGNITION -> R.string.microphone_source_voice
}
