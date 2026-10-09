package dev.sensorbridge.tilt.engine.catalog

import dev.sensorbridge.tilt.engine.ai.Rehearsal
import dev.sensorbridge.tilt.engine.ai.Rehearsals
import dev.sensorbridge.tilt.engine.game.Scoring
import dev.sensorbridge.tilt.engine.gen.GeneratorSpec
import dev.sensorbridge.tilt.engine.gen.LevelGenerator
import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.level.LevelParser
import dev.sensorbridge.tilt.engine.level.LevelValidator
import java.time.LocalDate

enum class Chapter(val title: String) {
    TRAINING("Training"),
    LABYRINTHS("Labyrinths"),
    DAILY("Daily"),
}

/** A level ready to play: its layout, and the par time its stars are measured against. */
class PlayableLevel(val entry: LevelEntry, val level: Level, val parSeconds: Double)

/**
 * One entry in the game's level list. Cheap to hold; [load] does the real work (parsing or generating, then
 * rehearsing for par), so call it off the main thread.
 */
class LevelEntry internal constructor(
    /** Stable identifier for saved progress, e.g. "training/2" or "daily/2026-10-09". */
    val id: String,
    val chapter: Chapter,
    /** 1-based position within its chapter. */
    val number: Int,
    val title: String,
    private val source: () -> Pair<Level, Double>,
) {
    fun load(): PlayableLevel {
        val (level, rehearsal) = source()
        return PlayableLevel(this, level, Scoring.parFor(rehearsal))
    }
}

/** Every level in the game, in play order. */
object LevelCatalog {
    private class TrainingFile(val path: String, val title: String)

    private val TRAINING_FILES = listOf(
        TrainingFile("/levels/training/1-first-roll.tilt", "First roll"),
        TrainingFile("/levels/training/2-mind-the-holes.tilt", "Mind the holes"),
        TrainingFile("/levels/training/3-leap.tilt", "Leap"),
        TrainingFile("/levels/training/4-second-chances.tilt", "Second chances"),
    )

    const val LABYRINTH_COUNT = 24
    private const val LABYRINTH_SEED = 0x7117_0000L

    /** Spreads consecutive days far apart in seed space (the golden ratio, as a 63-bit fraction). */
    private const val DAILY_SEED_STRIDE = 0x4F1BBCDCBFA53E0AL
    private val DAILY_SPEC = GeneratorSpec(4, 8, loops = 0.2, traps = 0.3, gaps = 1, decoys = 0.25, checkpoint = true)

    val training: List<LevelEntry> = TRAINING_FILES.mapIndexed { i, file ->
        LevelEntry("training/${i + 1}", Chapter.TRAINING, i + 1, file.title) { authored(file.path) }
    }

    val labyrinths: List<LevelEntry> = (1..LABYRINTH_COUNT).map { n ->
        LevelEntry("labyrinth/$n", Chapter.LABYRINTHS, n, "Labyrinth $n") {
            val generated = LevelGenerator.generate("Labyrinth $n", LABYRINTH_SEED + n, campaignSpec(n))
            generated.level to generated.rehearsalSeconds
        }
    }

    /** The campaign in order: training first, then the labyrinths. Finishing one unlocks the next. */
    val campaign: List<LevelEntry> = training + labyrinths

    fun byId(id: String): LevelEntry? = campaign.firstOrNull { it.id == id } ?: dailyFromId(id)

    /** The same challenge for everyone on a given day. */
    fun daily(date: LocalDate): LevelEntry =
        LevelEntry("daily/$date", Chapter.DAILY, 1, "Daily · $date") {
            val generated = LevelGenerator.generate("Daily challenge", date.toEpochDay() * DAILY_SEED_STRIDE, DAILY_SPEC)
            generated.level to generated.rehearsalSeconds
        }

    private fun dailyFromId(id: String): LevelEntry? =
        id.removePrefix("daily/").takeIf { it != id }?.let { runCatching { daily(LocalDate.parse(it)) }.getOrNull() }

    /**
     * Difficulty ramps gently over the campaign: bigger mazes first, then a few traps, a single jump from the
     * seventh labyrinth, a second only near the end. Checkpoints appear as soon as mazes get long. Sizes keep
     * roughly the phone's portrait proportions.
     */
    internal fun campaignSpec(n: Int): GeneratorSpec {
        val (wide, high) = when {
            n <= 3 -> 3 to 5
            n <= 8 -> 4 to 6
            n <= 14 -> 4 to 7
            n <= 19 -> 4 to 8
            else -> 5 to 9
        }
        return GeneratorSpec(
            roomsWide = wide,
            roomsHigh = high,
            loops = 0.08 + 0.01 * n,
            traps = minOf(0.08 + 0.015 * n, 0.4),
            gaps = when {
                n < 7 -> 0
                n < 18 -> 1
                else -> 2
            },
            decoys = minOf(0.08 + 0.01 * n, 0.3),
            checkpoint = n >= 4,
        )
    }

    private fun authored(path: String): Pair<Level, Double> {
        val text = LevelCatalog::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing level resource $path")
        val level = LevelValidator.requireValid(LevelParser.parse(text))
        return when (val rehearsal = Rehearsals.rehearse(level)) {
            is Rehearsal.Finished -> level to rehearsal.seconds
            is Rehearsal.Failed -> error("'${level.name}' cannot be finished: ${rehearsal.reason}")
        }
    }
}
