package dev.sensorbridge.tilt.game

import android.os.SystemClock
import android.util.Log
import dev.sensorbridge.tilt.engine.catalog.LevelEntry
import dev.sensorbridge.tilt.engine.catalog.PlayableLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Loads levels off the main thread and remembers the last few, so retrying is instant and the next level can be
 * prepared while the current one is played.
 */
class LevelRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val loads = LinkedHashMap<String, Deferred<PlayableLevel>>()

    suspend fun load(entry: LevelEntry): PlayableLevel = request(entry).await()

    /** Starts loading [entry] in the background if it isn't already. */
    suspend fun prefetch(entry: LevelEntry) {
        request(entry)
    }

    private suspend fun request(entry: LevelEntry): Deferred<PlayableLevel> = mutex.withLock {
        loads[entry.id]?.takeUnless { it.isCancelled }?.let { return@withLock it }
        val load = scope.async {
            val started = SystemClock.elapsedRealtime()
            entry.load().also { Log.i(TAG, "Prepared ${entry.id} in ${SystemClock.elapsedRealtime() - started} ms") }
        }
        loads[entry.id] = load
        while (loads.size > CAPACITY) loads.remove(loads.keys.first())
        load
    }

    private companion object {
        const val CAPACITY = 4
        const val TAG = "Tilt"
    }
}
