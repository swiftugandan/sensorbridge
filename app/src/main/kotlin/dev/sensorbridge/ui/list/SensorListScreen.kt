package dev.sensorbridge.ui.list

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sensorbridge.R
import dev.sensorbridge.bridge.BridgeStatus
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.protocol.ProtocolCodec
import dev.sensorbridge.ui.components.icon
import dev.sensorbridge.ui.components.isEventDriven
import dev.sensorbridge.ui.components.label
import dev.sensorbridge.ui.format.ReadingFormat
import dev.sensorbridge.ui.microphone.describe
import dev.sensorbridge.ui.theme.CodeStyle
import dev.sensorbridge.ui.theme.NumericStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorListScreen(
    onOpenSensor: (String) -> Unit,
    onOpenMicrophone: () -> Unit,
    onOpenCamera: () -> Unit,
    viewModel: SensorListViewModel = viewModel(factory = SensorListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bridgeStatus by viewModel.bridgeStatus.collectAsStateWithLifecycle()
    val microphoneRow by viewModel.microphoneRow.collectAsStateWithLifecycle()
    val cameraRow by viewModel.cameraRow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    // Permissions can change in system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionsChanged()
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionsChanged()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The bridge works without the notification; the permission only makes it visible.
        viewModel.setBridgeEnabled(true)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.sensors_title)) },
                actions = {
                    OverflowMenu(
                        showWakeUpVariants = state.showWakeUpVariants,
                        onShowWakeUpVariantsChange = viewModel::setShowWakeUpVariants,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "bridge") {
                BridgeCard(
                    status = bridgeStatus,
                    onEnabledChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setBridgeEnabled(enabled)
                        }
                    },
                    snackbar = snackbar,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "count") {
                Text(
                    text = pluralStringResource(R.plurals.sensors_count, state.totalCount, state.totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "header-media") {
                SectionHeader("${stringResource(R.string.category_media)} · 2")
            }
            item(key = "camera") {
                CameraRow(cameraRow, onClick = onOpenCamera, onGrant = permissionLauncher::launch)
            }
            item(key = "microphone") {
                MicrophoneRow(microphoneRow, onClick = onOpenMicrophone, onGrant = permissionLauncher::launch)
            }
            state.sections.forEach { section ->
                item(key = "header-${section.category}") {
                    SectionHeader("${stringResource(section.category.label)} · ${section.sensors.size}")
                }
                items(section.sensors, key = { it.key }) { sensor ->
                    SensorRow(
                        sensor = sensor,
                        viewModel = viewModel,
                        onClick = { onOpenSensor(sensor.key) },
                        onGrant = permissionLauncher::launch,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(showWakeUpVariants: Boolean, onShowWakeUpVariantsChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_wakeup_variants)) },
                trailingIcon = { Checkbox(checked = showWakeUpVariants, onCheckedChange = null) },
                onClick = {
                    onShowWakeUpVariantsChange(!showWakeUpVariants)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun BridgeCard(
    status: BridgeStatus,
    onEnabledChange: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val running = status is BridgeStatus.Running
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Cable,
                            contentDescription = null,
                            tint = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bridge_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when (status) {
                            BridgeStatus.Stopped -> stringResource(R.string.bridge_stopped)
                            BridgeStatus.Starting -> stringResource(R.string.bridge_starting)
                            is BridgeStatus.Running -> pluralStringResource(R.plurals.bridge_running, status.clients, status.clients)
                            is BridgeStatus.Failed -> stringResource(R.string.bridge_failed, status.reason)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status is BridgeStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = running || status == BridgeStatus.Starting,
                    onCheckedChange = onEnabledChange,
                )
            }
            AnimatedVisibility(visible = running) {
                Column(Modifier.padding(top = 16.dp)) {
                    Text(
                        stringResource(R.string.bridge_connect_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    CommandLine("adb forward tcp:$DEFAULT_HOST_PORT localabstract:${ProtocolCodec.DEFAULT_SOCKET_NAME}", snackbar)
                    Spacer(Modifier.height(8.dp))
                    CommandLine("sensorctl stream accelerometer", snackbar)
                }
            }
        }
    }
}

@Composable
internal fun CommandLine(command: String, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall.merge(CodeStyle),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(command))
            scope.launch { snackbar.showSnackbar(copied) }
        }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SensorRow(
    sensor: SensorDescriptor,
    viewModel: SensorListViewModel,
    onClick: () -> Unit,
    onGrant: (String) -> Unit,
) {
    val preview by remember(sensor.key) { viewModel.preview(sensor.key) }.collectAsStateWithLifecycle()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(sensor.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        headlineContent = {
            Text(sensor.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = if (sensor.wakeUp) "${sensor.name} · wake-up" else sensor.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            PreviewValue(sensor, preview, onGrant)
        },
    )
}

@Composable
private fun CameraRow(state: CameraRowState, onClick: () -> Unit, onGrant: (String) -> Unit) {
    MediaRow(
        icon = Icons.Outlined.PhotoCamera,
        title = stringResource(R.string.camera_title),
        supporting = stringResource(
            if (state.lenses.size > 1) R.string.camera_row_supporting_both else R.string.camera_row_supporting_one,
        ),
        permission = Manifest.permission.CAMERA.takeUnless { state.hasPermission },
        onClick = onClick,
        onGrant = onGrant,
    )
}

/** A media source row: opens its own screen, or offers the permission it needs first. */
@Composable
private fun MediaRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    permission: String?,
    onClick: () -> Unit,
    onGrant: (String) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            if (permission != null) {
                FilledTonalButton(onClick = { onGrant(permission) }) { Text(stringResource(R.string.action_grant)) }
            } else {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun MicrophoneRow(state: MicrophoneRowState, onClick: () -> Unit, onGrant: (String) -> Unit) {
    MediaRow(
        icon = Icons.Outlined.Mic,
        title = stringResource(R.string.microphone_title),
        supporting = state.info?.let { stringResource(R.string.microphone_row_supporting, describe(it)) }
            ?: stringResource(R.string.microphone_unavailable),
        permission = Manifest.permission.RECORD_AUDIO.takeUnless { state.hasPermission },
        onClick = onClick,
        onGrant = onGrant,
    )
}

@Composable
private fun PreviewValue(sensor: SensorDescriptor, preview: Preview, onGrant: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (preview) {
        is Preview.NeedsPermission -> FilledTonalButton(onClick = { onGrant(preview.permission) }) {
            Text(stringResource(R.string.action_grant))
        }
        Preview.Waiting -> Text(
            text = stringResource(if (sensor.isEventDriven) R.string.preview_event_waiting else R.string.preview_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Preview.Unavailable -> Text(stringResource(R.string.preview_unavailable), style = MaterialTheme.typography.bodySmall, color = muted)
        is Preview.Live -> Text(
            text = ReadingFormat.summary(sensor, preview.reading),
            style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
            textAlign = TextAlign.End,
            maxLines = 1,
            // Fixed width: a changing width would shift and re-truncate the sensor name on every update.
            modifier = Modifier.width(PREVIEW_WIDTH),
        )
    }
}

private val PREVIEW_WIDTH = 96.dp

/** The port `sensorctl` forwards to by default. */
const val DEFAULT_HOST_PORT = 7337
