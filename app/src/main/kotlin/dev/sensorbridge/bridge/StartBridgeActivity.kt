package dev.sensorbridge.bridge

import android.app.Activity
import android.os.Bundle
import dev.sensorbridge.appContainer

/**
 * Invisible trampoline that starts the bridge and finishes immediately.
 *
 * Starting a foreground service from the background is refused by Android 12+ and by some vendor task
 * managers when the app is not running; starting it from an activity is always permitted. `sensorctl` falls
 * back to this when `am start-foreground-service` is rejected. Guarded by DUMP like [BridgeService].
 */
class StartBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer.bridgeController.start()
        finish()
    }
}
