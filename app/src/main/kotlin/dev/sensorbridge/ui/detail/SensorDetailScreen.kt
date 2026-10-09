package dev.sensorbridge.ui.detail

import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sensorbridge.R
import dev.sensorbridge.domain.ReportingMode
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.domain.ValueShape
import dev.sensorbridge.domain.magnitude
import dev.sensorbridge.ui.components.LiveChart
import dev.sensorbridge.ui.components.isEventDriven
import dev.sensorbridge.ui.components.label
import dev.sensorbridge.ui.components.permissionLabel
import dev.sensorbridge.ui.format.ReadingFormat
import dev.sensorbridge.ui.list.CommandLine
import dev.sensorbridge.ui.theme.LocalSeriesColors
import dev.sensorbridge.ui.theme.NumericStyle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDetailScreen(
    onNavigateUp: () -> Unit,
    viewModel: SensorDetailViewModel = viewModel(factory = SensorDetailViewModel.Factory),
) {
    val descriptor = viewModel.descriptor
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionsChanged()
    }
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionsChanged()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(descriptor?.displayName ?: viewModel.key, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (descriptor != null) {
                            Text(
                                descriptor.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                    if (descriptor != null && !descriptor.isEventDriven) {
                        IconButton(onClick = viewModel::togglePaused) {
                            Icon(
                                if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                contentDescription = stringResource(if (state.paused) R.string.resume else R.string.pause),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (descriptor == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sensor_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val live = state.live) {
                is LiveState.NeedsPermission -> PermissionCard(live.permission) { permissionLauncher.launch(live.permission) }
                is LiveState.Failed -> MessageCard(stringResource(R.string.sensor_error, live.message), isError = true)
                else -> Unit
            }

            if (descriptor.isEventDriven) {
                EventLog(descriptor, (state.live as? LiveState.Streaming)?.snapshot)
            } else {
                LiveCard(descriptor, state.live)
                if (state.availableRates.isNotEmpty()) {
                    RateSelector(state.rateHz, state.availableRates, viewModel::setRate)
                }
            }

            when (descriptor.reportingMode) {
                ReportingMode.ON_CHANGE -> HintText(stringResource(R.string.on_change_hint))
                ReportingMode.ONE_SHOT, ReportingMode.SPECIAL_TRIGGER -> HintText(stringResource(R.string.event_hint))
                ReportingMode.CONTINUOUS -> Unit
            }

            DetailsCard(descriptor)

            SectionTitle(stringResource(R.string.cli_heading))
            val rateArg = if (state.availableRates.isNotEmpty()) " --rate ${state.rateHz}" else ""
            CommandLine("sensorctl stream ${descriptor.key}$rateArg", snackbar)
            CommandLine("sensorctl read ${descriptor.key}", snackbar)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LiveCard(descriptor: SensorDescriptor, live: LiveState) {
    val streaming = live as? LiveState.Streaming
    val latest = streaming?.snapshot?.latest
    val seriesColors = LocalSeriesColors.current
    val labels = descriptor.axes.ifEmpty { listOf("v0") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.live_heading), Modifier.weight(1f))
                streaming?.snapshot?.measuredRateHz?.let { hz ->
                    Text(
                        stringResource(R.string.measured_rate, String.format(Locale.getDefault(), "%.1f", hz)),
                        style = MaterialTheme.typography.labelMedium.merge(NumericStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HeadlineValue(descriptor, latest)
            if (latest != null) AccuracyLabel(latest.accuracy)

            // Legend + current values: identity never relies on colour alone, every series is named.
            if (labels.size > 1) {
                Spacer(Modifier.height(12.dp))
                AxisValues(descriptor, labels, latest, seriesColors.slots)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                pluralStringResource(R.plurals.history_heading, SensorDetailViewModel.WINDOW_SECONDS, SensorDetailViewModel.WINDOW_SECONDS),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
            ) {
                if (streaming != null) {
                    LiveChart(
                        snapshot = streaming.snapshot,
                        scale = streaming.scale,
                        labels = labels,
                        stepped = descriptor.reportingMode == ReportingMode.ON_CHANGE,
                        formatValue = { ReadingFormat.withUnit(ReadingFormat.value(it, descriptor), descriptor.unit) },
                        description = "${descriptor.displayName}: ${labels.joinToString()} over the last ${SensorDetailViewModel.WINDOW_SECONDS} seconds",
                        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.preview_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadlineValue(descriptor: SensorDescriptor, latest: SensorReading?) {
    val (value, caption) = when {
        latest == null || latest.values.isEmpty() -> "—" to null
        descriptor.shape == ValueShape.VECTOR ->
            ReadingFormat.value(latest.magnitude(), descriptor) to stringResource(R.string.magnitude)
        descriptor.shape == ValueShape.SCALAR || descriptor.shape == ValueShape.COUNTER ->
            ReadingFormat.value(latest.values[0], descriptor) to descriptor.axes.firstOrNull()
        else -> ReadingFormat.summary(descriptor, latest) to null
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displaySmall)
        if (descriptor.unit != null && descriptor.shape != ValueShape.ROTATION) {
            Spacer(Modifier.width(8.dp))
            Text(
                descriptor.unit.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
    if (caption != null) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AxisValues(descriptor: SensorDescriptor, labels: List<String>, latest: SensorReading?, colors: List<Color>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            // Fixed width: values change width as digits and signs change, which would otherwise re-wrap the row.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(AXIS_CELL_WIDTH)) {
                Box(
                    Modifier
                        .size(width = 12.dp, height = 3.dp)
                        .background(colors[index.coerceAtMost(colors.lastIndex)], RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        latest?.values?.getOrNull(index)?.let { ReadingFormat.value(it, descriptor) } ?: "—",
                        style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                    )
                }
            }
        }
    }
}

private val AXIS_CELL_WIDTH = 92.dp

@Composable
private fun AccuracyLabel(accuracy: Int) {
    val text = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> R.string.accuracy_high
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> R.string.accuracy_medium
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> R.string.accuracy_low
        SensorManager.SENSOR_STATUS_NO_CONTACT -> R.string.accuracy_none
        else -> R.string.accuracy_unreliable
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelSmall,
        color = if (accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RateSelector(selected: Int, rates: List<Int>, onSelect: (Int) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.sample_rate))
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rates.forEachIndexed { index, hz ->
                SegmentedButton(
                    selected = hz == selected,
                    onClick = { onSelect(hz) },
                    shape = SegmentedButtonDefaults.itemShape(index, rates.size),
                    label = { Text(hz.toString(), maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun EventLog(descriptor: SensorDescriptor, snapshot: HistorySnapshot?) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(stringResource(R.string.recent_events))
            Spacer(Modifier.height(8.dp))
            val events = snapshot?.recent.orEmpty()
            if (events.isEmpty()) {
                Text(stringResource(R.string.no_events_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            events.forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row {
                    Text(
                        timeFormat.format(Date(event.wallTimeMillis)),
                        style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        event.values.withIndex().joinToString("  ") { (i, v) ->
                            "${descriptor.axisLabel(i)} ${ReadingFormat.number(v, ReadingFormat.decimalsFor(descriptor))}"
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(descriptor: SensorDescriptor) {
    val unit = descriptor.unit?.let { " $it" }.orEmpty()
    val rows = listOf(
        R.string.detail_key to descriptor.key,
        R.string.detail_type to "${descriptor.stringType} (${descriptor.type})",
        R.string.detail_vendor to descriptor.vendor,
        R.string.detail_version to descriptor.version.toString(),
        R.string.detail_reporting to stringResource(descriptor.reportingMode.label),
        R.string.detail_wakeup to stringResource(if (descriptor.wakeUp) R.string.yes else R.string.no),
        R.string.detail_range to "${ReadingFormat.number(descriptor.maximumRange, 2)}$unit",
        R.string.detail_resolution to "${ReadingFormat.number(descriptor.resolution, 4)}$unit",
        R.string.detail_power to "${ReadingFormat.number(descriptor.powerMilliAmps, 2)} mA",
    ) + listOfNotNull(
        descriptor.maxRateHz?.let { R.string.detail_max_rate to "${ReadingFormat.number(it, 0)} Hz" },
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(stringResource(R.string.details_heading))
            Spacer(Modifier.height(8.dp))
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(112.dp),
                    )
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
                Text(
                    stringResource(R.string.permission_needed_body, permissionLabel(permission)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.action_grant)) }
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}
