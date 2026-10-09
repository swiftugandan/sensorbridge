package dev.sensorbridge.tilt.ui.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sensorbridge.domain.audio.AudioAccessException
import dev.sensorbridge.tilt.AppGraph
import dev.sensorbridge.tilt.data.GameSettings
import dev.sensorbridge.tilt.data.JumpInput
import dev.sensorbridge.tilt.data.Steering
import dev.sensorbridge.tilt.engine.catalog.LevelCatalog
import dev.sensorbridge.tilt.engine.catalog.LevelEntry
import dev.sensorbridge.tilt.engine.game.Scoring
import dev.sensorbridge.tilt.engine.nav.NavGrid
import dev.sensorbridge.tilt.game.GameController
import dev.sensorbridge.tilt.game.Hud
import dev.sensorbridge.tilt.game.MicJump
import dev.sensorbridge.tilt.platform.JumpTrigger
import dev.sensorbridge.tilt.platform.TouchSteering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How jumping works right now, after accounting for microphone permission. */
enum class JumpMode {
    /** Listening for a blow or clap. */
    SOUND,

    /** Tapping the board jumps (by choice). */
    TAP,

    /** Sound was chosen but the microphone isn't available, so tapping jumps instead. */
    TAP_FALLBACK,
}

data class Completion(
    val seconds: Double,
    val stars: Int,
    val parSeconds: Double,
    val bestSeconds: Double,
    val isNewBest: Boolean,
    val falls: Int,
    /** The next campaign level, or null after the last one and for daily challenges. */
    val next: LevelEntry?,
)

data class PlayState(
    val entry: LevelEntry,
    val loading: Boolean = true,
    val error: String? = null,
    val controller: GameController? = null,
    val hint: String? = null,
    val parSeconds: Double? = null,
    /** True when the level can't be finished without jumping. */
    val needsJump: Boolean = false,
    val settings: GameSettings = GameSettings(),
    val jumpMode: JumpMode = JumpMode.SOUND,
    /** Counts heard jumps, so the microphone indicator can pulse on each. */
    val jumpsHeard: Int = 0,
    val completion: Completion? = null,
)

/**
 * One level being played. Owns the [GameController] (so a run survives configuration changes) and switches the
 * sensors, microphone and sound on only while the screen is in front of the player.
 */
class PlayViewModel(private val entry: LevelEntry, private val graph: AppGraph) : ViewModel() {

    private val jumps = JumpTrigger()
    val touch = TouchSteering()
    private val micJump = MicJump(graph.microphone)
    private val orientation = graph.newOrientationSource()
    private val sound = graph.newSoundOutput()

    private val state = MutableStateFlow(PlayState(entry))
    val uiState: StateFlow<PlayState> = state.asStateFlow()

    private val hudState = MutableStateFlow<Hud?>(null)
    val hud: StateFlow<Hud?> = hudState.asStateFlow()

    @Volatile
    private var steering = Steering.TILT

    /** The display's rotation in quarter turns; the activity is portrait, but a tablet's natural axes may differ. */
    @Volatile
    var displayQuarterTurns = 0

    private var resumed = false
    private var micJob: Job? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            graph.settings.settings.collect { settings ->
                steering = settings.steering
                state.update { it.copy(settings = settings) }
                state.value.controller?.updateConfig(settings.sessionConfig)
                graph.haptics.enabled = settings.haptics
                sound.volume = settings.volume
                if (resumed) restartInputs()
            }
        }
    }

    private suspend fun load() {
        val playable = try {
            graph.levels.load(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
            return
        }
        val needsJump = withContext(Dispatchers.Default) { (NavGrid(playable.level).routeToGoal()?.jumpCount ?: 0) > 0 }
        val settings = graph.settings.settings.first()
        val controller = GameController(
            playable = playable,
            config = settings.sessionConfig,
            orientation = { if (steering == Steering.TOUCH) touch.up else orientation.up },
            quarterTurns = { if (steering == Steering.TOUCH) 0 else displayQuarterTurns },
            jumps = jumps,
            sound = sound,
            haptics = graph.haptics,
        )
        state.update {
            it.copy(
                loading = false,
                controller = controller,
                hint = playable.level.hint,
                parSeconds = playable.parSeconds,
                needsJump = needsJump,
                settings = settings,
            )
        }
        if (resumed) restartInputs()
        nextEntry()?.let { graph.levels.prefetch(it) }
        controller.hud.collect { hud ->
            hudState.value = hud
            if (hud.finishedSeconds != null && state.value.completion == null) complete(hud)
        }
    }

    fun onResume() {
        resumed = true
        restartInputs()
    }

    fun onPause() {
        resumed = false
        state.value.controller?.pause()
        stopInputs()
    }

    fun pause() = state.value.controller?.pause()

    fun resume() = state.value.controller?.resume()

    fun restart() {
        state.update { it.copy(completion = null) }
        state.value.controller?.restart()
    }

    /** A tap on the board: jumps, if tapping is how jumping works right now. */
    fun tap() {
        if (state.value.jumpMode != JumpMode.SOUND) jumps.request()
    }

    /** Re-checks microphone permission, e.g. after the player answered the permission prompt. */
    fun onPermissionsChanged() {
        if (resumed) restartInputs()
    }

    private fun restartInputs() {
        stopInputs()
        val settings = state.value.settings
        if (settings.steering == Steering.TILT) orientation.start()
        if (settings.sound) sound.start()
        val wantsSound = settings.jump == JumpInput.SOUND
        val canListen = wantsSound && micJump.hasPermission()
        state.update {
            it.copy(jumpMode = if (!wantsSound) JumpMode.TAP else if (canListen) JumpMode.SOUND else JumpMode.TAP_FALLBACK)
        }
        // Listen only where it matters: the microphone stays off on levels without jumps.
        if (canListen && state.value.needsJump) {
            micJob = viewModelScope.launch {
                micJump.readings()
                    .catch { e ->
                        if (e !is AudioAccessException) throw e
                        state.update { it.copy(jumpMode = JumpMode.TAP_FALLBACK) }
                    }
                    .collect { reading ->
                        if (reading.jumped) {
                            jumps.request()
                            state.update { it.copy(jumpsHeard = it.jumpsHeard + 1) }
                        }
                    }
            }
        }
    }

    private fun stopInputs() {
        micJob?.cancel()
        micJob = null
        orientation.stop()
        sound.stop()
    }

    private suspend fun complete(hud: Hud) {
        val seconds = hud.finishedSeconds ?: return
        val par = state.value.parSeconds ?: return
        val stars = Scoring.stars(seconds, par)
        val saved = graph.progress.saveRun(entry.id, seconds, stars)
        state.update {
            it.copy(
                completion = Completion(
                    seconds = seconds,
                    stars = stars,
                    parSeconds = par,
                    bestSeconds = saved.record.bestSeconds,
                    isNewBest = saved.isNewBest,
                    falls = hud.falls,
                    next = nextEntry(),
                ),
            )
        }
    }

    private fun nextEntry(): LevelEntry? {
        val campaign = LevelCatalog.campaign
        val index = campaign.indexOf(entry)
        return if (index >= 0) campaign.getOrNull(index + 1) else null
    }

    override fun onCleared() {
        stopInputs()
    }
}
