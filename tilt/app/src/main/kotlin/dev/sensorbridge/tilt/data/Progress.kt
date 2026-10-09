package dev.sensorbridge.tilt.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.sensorbridge.tilt.engine.catalog.LevelCatalog
import dev.sensorbridge.tilt.engine.catalog.LevelEntry
import dev.sensorbridge.tilt.engine.game.Scoring
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The best a player has done on one level. */
data class LevelRecord(val bestSeconds: Double, val stars: Int)

/** Everything the player has achieved, keyed by level id. */
data class Progress(val records: Map<String, LevelRecord>) {

    fun record(entry: LevelEntry): LevelRecord? = records[entry.id]

    /** The first campaign level is always open; each later one opens when the one before it is finished. */
    fun isUnlocked(entry: LevelEntry): Boolean {
        val index = LevelCatalog.campaign.indexOf(entry)
        return index <= 0 || LevelCatalog.campaign[index - 1].id in records
    }

    /** Where "Continue" goes: the first unfinished level, or the last one once everything is done. */
    val nextUp: LevelEntry
        get() = LevelCatalog.campaign.firstOrNull { it.id !in records } ?: LevelCatalog.campaign.last()

    val campaignStars: Int get() = LevelCatalog.campaign.sumOf { records[it.id]?.stars ?: 0 }
    val campaignMaxStars: Int get() = LevelCatalog.campaign.size * Scoring.MAX_STARS
}

/** Outcome of saving a finished run. */
data class RunSaved(val record: LevelRecord, val previous: LevelRecord?) {
    val isNewBest: Boolean get() = previous == null || record.bestSeconds < previous.bestSeconds
}

class ProgressStore(private val store: DataStore<Preferences>) {

    val progress: Flow<Progress> = store.data.map { p ->
        val records = mutableMapOf<String, LevelRecord>()
        for ((key, value) in p.asMap()) {
            val id = key.name.removePrefix(BEST_PREFIX).takeIf { it != key.name } ?: continue
            val stars = p[intPreferencesKey(STARS_PREFIX + id)] ?: continue
            records[id] = LevelRecord(value as Double, stars)
        }
        Progress(records)
    }.distinctUntilChanged()

    /** Keeps the faster time and the higher star count, each independently. */
    suspend fun saveRun(id: String, seconds: Double, stars: Int): RunSaved {
        lateinit var saved: RunSaved
        store.edit { p ->
            val bestKey = doublePreferencesKey(BEST_PREFIX + id)
            val starsKey = intPreferencesKey(STARS_PREFIX + id)
            val previous = p[bestKey]?.let { best -> LevelRecord(best, p[starsKey] ?: 0) }
            val record = LevelRecord(
                bestSeconds = minOf(seconds, previous?.bestSeconds ?: Double.MAX_VALUE),
                stars = maxOf(stars, previous?.stars ?: 0),
            )
            p[bestKey] = record.bestSeconds
            p[starsKey] = record.stars
            saved = RunSaved(record, previous)
        }
        return saved
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    private companion object {
        const val BEST_PREFIX = "best/"
        const val STARS_PREFIX = "stars/"
    }
}
