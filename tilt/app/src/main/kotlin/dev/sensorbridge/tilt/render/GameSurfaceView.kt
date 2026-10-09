package dev.sensorbridge.tilt.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.sensorbridge.tilt.game.GameController

/**
 * The board, drawn on its own thread in step with the display.
 *
 * A dedicated thread paced by its own [Choreographer] advances the [GameController] and draws each frame with a
 * hardware canvas, so the game keeps its frame rate whatever the UI thread is doing (recomposing the HUD,
 * handling touches). The thread lives exactly as long as the surface.
 */
@SuppressLint("ViewConstructor")
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** The level being played; null while it loads. May be swapped from the UI thread at any time. */
    @Volatile
    var controller: GameController? = null

    private var renderer: Renderer? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer = Renderer(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The surface must not be drawn on after this returns, so wait for the thread to finish.
        renderer?.quitAndJoin()
        renderer = null
    }

    private inner class Renderer(private val holder: SurfaceHolder) : Choreographer.FrameCallback {
        private val thread = HandlerThread("tilt-render", Process.THREAD_PRIORITY_DISPLAY)
        private lateinit var handler: Handler
        private var choreographer: Choreographer? = null
        private var painter: BoardPainter? = null
        private var painted: GameController? = null
        private var width = 0
        private var height = 0
        private var running = false

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            handler.post {
                running = true
                choreographer = Choreographer.getInstance().also { it.postFrameCallback(this) }
            }
        }

        fun resize(w: Int, h: Int) = handler.post {
            width = w
            height = h
            painter?.resize(w, h)
        }

        fun quitAndJoin() {
            handler.post {
                running = false
                choreographer?.removeFrameCallback(this)
                painter?.release()
                painter = null
                // The next surface may come back after a pause: don't let the time away count as play.
                painted?.resetClock()
            }
            thread.quitSafely()
            thread.join()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer?.postFrameCallback(this)
            val current = controller
            if (current !== painted) {
                painter?.release()
                painter = current?.let { BoardPainter(it.playable.level).apply { resize(width, height) } }
                painted = current
            }
            current?.frame(frameTimeNanos)
            if (width == 0 || height == 0) return
            val canvas: Canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: IllegalStateException) {
                return // surface already gone; surfaceDestroyed will stop us
            } ?: return
            try {
                canvas.drawColor(BACKGROUND)
                if (current != null) painter?.draw(canvas, current.session, current.interpolation, frameTimeNanos / 1e9)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private companion object {
        val BACKGROUND = 0xFF17120E.toInt()
    }
}
