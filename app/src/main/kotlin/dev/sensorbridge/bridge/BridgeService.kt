package dev.sensorbridge.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.sensorbridge.appContainer
import dev.sensorbridge.domain.protocol.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Foreground service that owns the [BridgeServer]. Foreground status is what lets sensors keep delivering
 * while the app's UI is not visible.
 *
 * Exported but guarded by `android.permission.DUMP` (see the manifest), which the adb shell holds and
 * ordinary apps cannot, so `adb shell am start-foreground-service` can start it headlessly.
 */
class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: BridgeServer? = null

    private val controller get() = appContainer.bridgeController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must promote to foreground first, even a stop request, or the system kills the process.
        promoteToForeground(clients = 0)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        } else if (server == null) {
            launchServer()
        }
        return START_NOT_STICKY
    }

    private fun launchServer() {
        val newServer = try {
            BridgeServer(
                socketName = ProtocolCodec.DEFAULT_SOCKET_NAME,
                handler = appContainer.requestHandler,
                allowedUids = setOf(ROOT_UID, SHELL_UID, Process.myUid()),
                onClientCountChanged = { count -> scope.launch { onClientsChanged(count) } },
            )
        } catch (e: IOException) {
            fail("Could not open socket: ${e.message}")
            return
        }
        server = newServer
        controller.report(BridgeStatus.Running(newServer.socketName, clients = 0))
        scope.launch {
            try {
                newServer.serve()
            } catch (e: IOException) {
                fail("Bridge stopped: ${e.message}")
            }
        }
    }

    private fun onClientsChanged(count: Int) {
        val current = server ?: return
        controller.report(BridgeStatus.Running(current.socketName, count))
        promoteToForeground(count)
    }

    private fun promoteToForeground(clients: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, BridgeNotifications.ID, BridgeNotifications.build(this, clients), type)
    }

    private fun fail(reason: String) {
        controller.report(BridgeStatus.Failed(reason))
        server?.close()
        server = null
        stopSelf()
    }

    override fun onDestroy() {
        server?.close()
        server = null
        scope.cancel()
        if (controller.status.value !is BridgeStatus.Failed) {
            controller.report(BridgeStatus.Stopped)
        }
        NotificationManagerCompat.from(this).cancel(BridgeNotifications.ID)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.sensorbridge.action.STOP_BRIDGE"
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000

        fun startIntent(context: Context) = Intent(context, BridgeService::class.java)

        fun stopIntent(context: Context) = startIntent(context).setAction(ACTION_STOP)
    }
}
