package dev.sensorbridge.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
import dev.sensorbridge.domain.protocol.ErrorCode
import dev.sensorbridge.domain.protocol.ProtocolCodec
import dev.sensorbridge.domain.protocol.ProtocolException
import dev.sensorbridge.domain.protocol.RequestHandler
import dev.sensorbridge.domain.protocol.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the bridge protocol on an abstract-namespace Unix socket, reachable from a workstation through
 * `adb forward tcp:<port> localabstract:<socketName>`.
 *
 * Abstract sockets are visible to every app on the device, so each connection's peer UID is checked against
 * [allowedUids] (the adb shell, root and this app) before any request is read.
 */
class BridgeServer(
    val socketName: String,
    private val handler: RequestHandler,
    private val allowedUids: Set<Int>,
    private val onClientCountChanged: (Int) -> Unit,
) : Closeable {

    /** Binds immediately so a name clash fails at construction rather than inside [serve]. */
    private val serverSocket = LocalServerSocket(socketName)
    private val clients: MutableSet<LocalSocket> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var closed = false

    /** Accepts connections until [close] is called. Each client is served concurrently. */
    suspend fun serve() = withContext(Dispatchers.IO) {
        coroutineScope {
            while (isActive) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (closed) break else throw e
                }
                launch { serveClient(socket) }
            }
        }
    }

    private suspend fun serveClient(socket: LocalSocket) = socket.use {
        val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
        val peerUid = socket.peerCredentials.uid
        if (peerUid !in allowedUids) {
            writer.writeResponse(Response.Error(ErrorCode.UNAUTHORIZED, "UID $peerUid may not use this bridge"))
            return@use
        }

        clients += socket
        onClientCountChanged(clients.size)
        try {
            val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val responses = try {
                    handler.handle(ProtocolCodec.decodeRequest(line))
                } catch (e: ProtocolException) {
                    flowOf(Response.Error(e.code, e.message.orEmpty()))
                }
                responses.collect { writer.writeResponse(it) }
            }
        } catch (_: IOException) {
            // The peer hung up, possibly mid-stream; collection stops and listeners are released.
        } finally {
            clients -= socket
            onClientCountChanged(clients.size)
        }
    }

    private fun Writer.writeResponse(response: Response) {
        write(ProtocolCodec.encode(response))
        write('\n'.code)
        flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        // close() alone does not wake a thread blocked in accept(); shutting the descriptor down does.
        runCatching { Os.shutdown(serverSocket.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { serverSocket.close() }
        clients.forEach { runCatching { it.shutdownInput() }; runCatching { it.close() } }
    }
}
