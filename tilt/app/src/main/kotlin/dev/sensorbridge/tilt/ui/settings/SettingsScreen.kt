package dev.sensorbridge.tilt.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sensorbridge.tilt.R
import dev.sensorbridge.tilt.data.GameSettings
import dev.sensorbridge.tilt.data.JumpInput
import dev.sensorbridge.tilt.data.Steering
import dev.sensorbridge.tilt.engine.game.NeutralMode
import dev.sensorbridge.tilt.engine.game.SessionConfig
import dev.sensorbridge.tilt.game.MicReading
import dev.sensorbridge.tilt.ui.theme.TiltColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permission by viewModel.micPermission.collectAsStateWithLifecycle()
    val mic by viewModel.micTest.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onPermissionsChanged()
        onPauseOrDispose { }
    }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Section(R.string.section_steering) }
            item {
                Choice(
                    options = listOf(Steering.TILT to R.string.steering_tilt, Steering.TOUCH to R.string.steering_touch),
                    selected = s.steering,
                    onSelect = { v -> viewModel.update { it.copy(steering = v) } },
                )
                Summary(if (s.steering == Steering.TILT) R.string.steering_tilt_summary else R.string.steering_touch_summary)
            }
            if (s.steering == Steering.TILT) {
                item {
                    Label(R.string.neutral_title)
                    Choice(
                        options = listOf(NeutralMode.AUTO to R.string.neutral_auto, NeutralMode.FLAT to R.string.neutral_flat),
                        selected = s.neutral,
                        onSelect = { v -> viewModel.update { it.copy(neutral = v) } },
                    )
                }
            }
            item { SensitivitySlider(s, viewModel) }

            item { Section(R.string.section_jumping) }
            item {
                Choice(
                    options = listOf(JumpInput.SOUND to R.string.jump_sound, JumpInput.TAP to R.string.jump_tap),
                    selected = s.jump,
                    onSelect = { v -> viewModel.update { it.copy(jump = v) } },
                )
                if (s.jump == JumpInput.SOUND) {
                    Summary(R.string.jump_sound_summary)
                    if (permission) MicMeter(mic) else MicPermissionRow(viewModel::onPermissionsChanged)
                }
            }

            item { Section(R.string.section_feedback) }
            item {
                SwitchRow(R.string.sound_effects, s.sound) { v -> viewModel.update { it.copy(sound = v) } }
                if (s.sound) {
                    var volume by remember(s.volume) { mutableFloatStateOf(s.volume.toFloat()) }
                    Label(R.string.volume)
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = { viewModel.update { it.copy(volume = volume.toDouble()) } },
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                SwitchRow(R.string.vibration, s.haptics) { v -> viewModel.update { it.copy(haptics = v) } }
            }

            item { Section(R.string.section_progress) }
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.reset_progress)) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.reset_progress_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetProgress()
                }) { Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Label(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Summary(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun SensitivitySlider(settings: GameSettings, viewModel: SettingsViewModel) {
    var value by remember(settings.sensitivity) { mutableFloatStateOf(settings.sensitivity.toFloat()) }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sensitivity), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.sensitivity_value, value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = value,
        onValueChange = { value = (it * 10).toInt() / 10f },
        onValueChangeFinished = { viewModel.update { it.copy(sensitivity = value.toDouble()) } },
        valueRange = SessionConfig.MIN_SENSITIVITY.toFloat()..SessionConfig.MAX_SENSITIVITY.toFloat(),
        steps = ((SessionConfig.MAX_SENSITIVITY - SessionConfig.MIN_SENSITIVITY) * 10).toInt() - 1,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun SwitchRow(title: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MicPermissionRow(onResult: () -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        onResult()
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.mic_permission_needed), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        if (!denied) {
            OutlinedButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.allow)) }
        } else {
            // Once refused, Android stops asking; the app's system settings page is the way back.
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text(stringResource(R.string.open_settings)) }
        }
    }
}

/** A level bar for the microphone with a tick at the trigger level; flashes gold when it hears a jump. */
@Composable
private fun MicMeter(reading: MicReading?) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(reading?.jumped) {
        if (reading?.jumped == true) {
            flash = true
            delay(400)
            flash = false
        }
    }
    val barColor by animateColorAsState(if (flash) TiltColors.Gold else MaterialTheme.colorScheme.secondary, label = "bar")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mic_test), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (flash) Text(stringResource(R.string.mic_test_jump), style = MaterialTheme.typography.labelLarge, color = TiltColors.Gold)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            fun x(db: Double) = (((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0) * size.width).toFloat()
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            reading?.let {
                drawRoundRect(barColor, size = Size(x(it.levelDb), size.height), cornerRadius = radius)
                val t = x(it.thresholdDb)
                drawLine(tick, Offset(t, -4f), Offset(t, size.height + 4f), strokeWidth = 3f)
            }
        }
    }
}

private const val FLOOR_DB = -80.0
