package dev.sensorbridge.tilt.ui.play

import android.Manifest
import android.app.Activity
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sensorbridge.tilt.R
import dev.sensorbridge.tilt.data.JumpInput
import dev.sensorbridge.tilt.data.Steering
import dev.sensorbridge.tilt.engine.catalog.Chapter
import dev.sensorbridge.tilt.engine.game.NeutralMode
import dev.sensorbridge.tilt.engine.game.Phase
import dev.sensorbridge.tilt.game.Hud
import dev.sensorbridge.tilt.render.GameSurfaceView
import dev.sensorbridge.tilt.ui.components.Stars
import dev.sensorbridge.tilt.ui.components.formatSeconds
import dev.sensorbridge.tilt.ui.components.formatTenths
import dev.sensorbridge.tilt.ui.theme.NumericStyle
import dev.sensorbridge.tilt.ui.theme.TiltColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    onQuit: () -> Unit,
    onPlay: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hud by viewModel.hud.collectAsStateWithLifecycle()
    val view = LocalView.current

    ImmersiveWhilePlaying()
    LifecycleResumeEffect(viewModel) {
        viewModel.displayQuarterTurns = when (view.display?.rotation) {
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    MicrophonePermission(state, viewModel::onPermissionsChanged)

    val paused = hud?.paused == true
    BackHandler {
        when {
            state.completion != null || state.controller == null || paused -> onQuit()
            else -> viewModel.pause()
        }
    }

    Box(Modifier.fillMaxSize().background(TiltColors.Walnut)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            HudBar(state, hud, onPause = viewModel::pause, onBack = onQuit)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = ::GameSurfaceView,
                    update = { it.controller = state.controller },
                    // A SurfaceView clears its window's pixels to show the surface beneath. Before Android 12 it
                    // clears the whole current clip, so without a clip it would wipe out the HUD drawn before it.
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
                if (state.controller != null) BoardInput(state, viewModel)
                hud?.let { CountdownOverlay(state, it) }
                when {
                    state.loading -> Loading()
                    state.error != null -> Message(stringResource(R.string.load_failed, state.error.orEmpty()))
                }
            }
            JumpHint(state)
        }
        AnimatedVisibility(paused && state.completion == null, enter = fadeIn(), exit = fadeOut()) {
            PauseMenu(onResume = viewModel::resume, onRestart = viewModel::restart, onQuit = onQuit)
        }
        AnimatedVisibility(
            state.completion != null,
            enter = fadeIn(tween(250, delayMillis = 450)) + slideInVertically(tween(350, delayMillis = 450)) { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            state.completion?.let { completion ->
                CompletionCard(
                    completion,
                    onNext = { completion.next?.let { onPlay(it.id) } },
                    onRetry = viewModel::restart,
                    onQuit = onQuit,
                )
            }
        }
    }
}

/** Hides the system bars while a level is on screen (a swipe brings them back briefly) and keeps the screen on. */
@Composable
private fun ImmersiveWhilePlaying() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

/** Asks for the microphone once, the first time a level needs jumping and sound is the chosen way to jump. */
@Composable
private fun MicrophonePermission(state: PlayState, onResult: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    val shouldAsk = state.needsJump && state.settings.jump == JumpInput.SOUND && state.jumpMode == JumpMode.TAP_FALLBACK
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(shouldAsk) {
        if (shouldAsk && !asked) {
            asked = true
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun HudBar(state: PlayState, hud: Hud?, onPause: () -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playing = state.controller != null && state.completion == null
        if (playing) {
            IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, stringResource(R.string.pause)) }
        } else {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                chapterLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(state.entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
            Text(
                formatTenths(hud?.tenths ?: 0),
                style = MaterialTheme.typography.headlineSmall.merge(NumericStyle),
            )
            state.parSeconds?.let {
                Text(
                    stringResource(R.string.par, formatSeconds(it)),
                    style = MaterialTheme.typography.labelMedium.merge(NumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun chapterLabel(state: PlayState): String = when (state.entry.chapter) {
    Chapter.TRAINING -> "${stringResource(R.string.chapter_training)} ${state.entry.number}"
    Chapter.LABYRINTHS -> stringResource(R.string.chapter_labyrinths)
    Chapter.DAILY -> stringResource(R.string.daily_challenge)
}

/**
 * Touches on the board. With touch steering, dragging steers, drawn as a stick under the finger; a quick tap (or a
 * second finger) jumps when tapping is how jumping works.
 */
@Composable
private fun BoardInput(state: PlayState, viewModel: PlayViewModel) {
    val touchSteering = state.settings.steering == Steering.TOUCH
    var origin by remember { mutableStateOf<Offset?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val currentSteering by rememberUpdatedState(touchSteering)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boardWidth = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(viewModel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val startedAt = down.uptimeMillis
                        var moved = false
                        var extraFingers = false
                        if (currentSteering) {
                            origin = start
                            finger = start
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2 && !extraFingers) {
                                extraFingers = true
                                viewModel.tap()
                            }
                            val primary = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = primary.position - start
                            if (hypot(delta.x, delta.y) > viewConfiguration.touchSlop) moved = true
                            if (currentSteering) {
                                finger = primary.position
                                viewModel.touch.drag(delta.x / boardWidth.toDouble(), delta.y / boardWidth.toDouble())
                            }
                            if (!primary.pressed) {
                                if (!moved && !extraFingers && primary.uptimeMillis - startedAt < TAP_MS) viewModel.tap()
                                break
                            }
                        }
                        viewModel.touch.drag(0.0, 0.0)
                        origin = null
                    }
                }
                .drawBehind {
                    val o = origin ?: return@drawBehind
                    val reach = boardWidth * 0.25f
                    drawCircle(Color.White.copy(alpha = 0.12f), radius = reach, center = o)
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = reach, center = o, style = Stroke(2.dp.toPx()))
                    val d = finger - o
                    val length = hypot(d.x, d.y)
                    val knob = if (length > reach) o + d * (reach / length) else finger
                    drawCircle(TiltColors.Gold.copy(alpha = 0.85f), radius = 22.dp.toPx(), center = knob)
                },
        )
    }
}

private const val TAP_MS = 250L

@Composable
private fun CountdownOverlay(state: PlayState, hud: Hud) {
    val counting = hud.phase == Phase.COUNTDOWN && !hud.paused
    var showGo by remember { mutableStateOf(false) }
    LaunchedEffect(hud.phase) {
        if (hud.phase == Phase.PLAYING && hud.tenths < 3) {
            showGo = true
            delay(500)
            showGo = false
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = if (counting) hud.countdown.toString() else if (showGo) stringResource(R.string.go) else "",
            transitionSpec = { (scaleIn(initialScale = 1.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
            label = "countdown",
        ) { text ->
            if (text.isNotEmpty()) {
                Text(
                    text,
                    fontSize = 120.sp,
                    style = MaterialTheme.typography.displayLarge,
                    color = TiltColors.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        AnimatedVisibility(counting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TiltColors.Walnut.copy(alpha = 0.88f),
                modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hud.starting && state.hint != null) {
                        Text(state.hint, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (state.settings.steering == Steering.TILT) {
                        Text(
                            stringResource(
                                when {
                                    hud.waitingForSteady -> R.string.hold_steady
                                    state.settings.neutral == NeutralMode.AUTO -> R.string.hold_level
                                    else -> R.string.hold_flat
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            stringResource(R.string.touch_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Below the board on levels with jumps: how to jump, with a microphone that pulses each time it hears one. */
@Composable
private fun JumpHint(state: PlayState) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        if (!state.needsJump || state.completion != null) return@Box
        val pulse = remember { Animatable(1f) }
        LaunchedEffect(state.jumpsHeard) {
            if (state.jumpsHeard > 0) {
                pulse.snapTo(1.5f)
                pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
        }
        val listening = state.jumpMode == JumpMode.SOUND
        val text = when {
            listening -> stringResource(R.string.jump_hint_sound)
            state.jumpMode == JumpMode.TAP_FALLBACK -> stringResource(R.string.mic_denied)
            state.settings.steering == Steering.TOUCH -> stringResource(R.string.jump_hint_touch)
            else -> stringResource(R.string.jump_hint_tap)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Icon(
                if (listening) Icons.Rounded.Mic else Icons.Rounded.TouchApp,
                contentDescription = if (listening) stringResource(R.string.mic_listening) else null,
                tint = if (listening) TiltColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).scale(pulse.value),
            )
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading_level), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow touches so the board underneath never sees them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PauseMenu(onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit) {
    Scrim {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(32.dp).widthIn(max = 360.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.paused), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.resume)) }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restart)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onQuit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.quit_to_levels)) }
            }
        }
    }
}

@Composable
private fun CompletionCard(completion: Completion, onNext: () -> Unit, onRetry: () -> Unit, onQuit: () -> Unit) {
    val starsText = pluralStringResource(R.plurals.stars, completion.stars, completion.stars)
    Scrim {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(24.dp).widthIn(max = 400.dp),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.level_complete), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    formatSeconds(completion.seconds),
                    style = MaterialTheme.typography.displayMedium.merge(NumericStyle),
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(12.dp))
                AnimatedStars(completion.stars, Modifier.semantics { contentDescription = starsText })
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Stat(stringResource(R.string.par, formatSeconds(completion.parSeconds)))
                    if (completion.isNewBest) {
                        Text(
                            stringResource(R.string.new_best),
                            style = MaterialTheme.typography.labelLarge,
                            color = TiltColors.Walnut,
                            modifier = Modifier.background(TiltColors.Gold, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    } else {
                        Stat(stringResource(R.string.best_time, formatSeconds(completion.bestSeconds)))
                    }
                    Stat(stringResource(R.string.falls, completion.falls))
                }
                Spacer(Modifier.height(24.dp))
                if (completion.next != null) {
                    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.next_level)) }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onQuit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.quit_to_levels)) }
            }
        }
    }
}

@Composable
private fun Stat(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge.merge(NumericStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Stars pop in one after another, so earning the third feels like an event. */
@Composable
private fun AnimatedStars(count: Int, modifier: Modifier = Modifier) {
    val scales = remember { List(3) { Animatable(0f) } }
    LaunchedEffect(count) {
        scales.forEachIndexed { i, scale ->
            launch {
                delay(700L + 220L * i)
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        scales.forEachIndexed { i, scale ->
            Box(Modifier.size(44.dp).scale(scale.value)) {
                Stars(count = if (i < count) 1 else 0, max = 1, size = 44.dp)
            }
        }
    }
}
