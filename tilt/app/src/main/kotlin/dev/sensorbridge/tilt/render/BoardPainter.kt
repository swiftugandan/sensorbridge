package dev.sensorbridge.tilt.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import dev.sensorbridge.tilt.engine.game.GameSession
import dev.sensorbridge.tilt.engine.game.Phase
import dev.sensorbridge.tilt.engine.geometry.Hole
import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.physics.Physics
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a level as a wooden labyrinth seen from above, lit from the top left.
 *
 * Everything that never moves (floor, holes, wall shadows) is painted once into a bitmap per surface size. Each
 * frame then adds the walls, raised so their tops lean with the board's tilt like a real box seen from a fixed
 * eye, the checkpoints, and the marble with its shadow. Frame drawing allocates nothing.
 */
class BoardPainter(private val level: Level) {

    private var width = 0
    private var height = 0
    private var cell = 1f
    private var originX = 0f
    private var originY = 0f
    private var staticLayer: Bitmap? = null

    private val wallSidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WALL_SIDE }
    private val wallTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WALL_TOP }
    private val wallLightEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WALL_EDGE_LIGHT; style = Paint.Style.STROKE }
    private val wallDarkEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WALL_EDGE_DARK; style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // In unit-radius coordinates; the canvas is scaled to the marble's size when drawing.
        shader = RadialGradient(
            -0.35f, -0.4f, 1.45f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFDDE2E8.toInt(), 0xFF8E97A3.toInt(), 0xFF3A404A.toInt(), 0xFF1E2228.toInt()),
            floatArrayOf(0f, 0.18f, 0.55f, 0.85f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rimLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFF4E0
    }
    private val sinkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        cell = floor(min(w.toFloat() / level.width, h.toFloat() / level.height))
        originX = floor((w - cell * level.width) / 2)
        originY = floor((h - cell * level.height) / 2)
        staticLayer?.recycle()
        staticLayer = null
        wallLightEdge.strokeWidth = cell * 0.06f
        wallDarkEdge.strokeWidth = cell * 0.06f
        rimLightPaint.strokeWidth = 0.08f
    }

    fun draw(canvas: Canvas, session: GameSession, interpolation: Double, timeSeconds: Double) {
        if (width == 0) return
        val layer = staticLayer ?: paintStatic().also { staticLayer = it }
        canvas.drawBitmap(layer, 0f, 0f, null)

        val tiltX = session.tilt[0].coerceIn(-MAX_LEAN, MAX_LEAN).toFloat()
        val tiltY = session.tilt[1].coerceIn(-MAX_LEAN, MAX_LEAN).toFloat()
        drawCheckpoints(canvas, session, timeSeconds)
        drawGoalGlow(canvas, timeSeconds, session)
        placeBall(session, interpolation, tiltX, tiltY)
        drawBallShadow(canvas)
        drawWalls(canvas, tiltX, tiltY)
        drawBallBody(canvas)
        if (session.phase == Phase.WON) drawWinBurst(canvas, session.phaseTime)
    }

    fun release() {
        staticLayer?.recycle()
        staticLayer = null
    }

    // --- Static layer ------------------------------------------------------------------------------------------

    private fun paintStatic(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val board = RectF(originX, originY, originX + cell * level.width, originY + cell * level.height)

        val wood = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(WoodGrain.tile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(board, wood)
        // Soft vignette: the board's centre catches more light than its edges.
        canvas.drawRect(
            board,
            Paint().apply {
                shader = RadialGradient(
                    board.centerX(), board.centerY(), maxOf(board.width(), board.height()) * 0.75f,
                    intArrayOf(0x00000000, 0x00000000, 0x55000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            },
        )

        // The start: a faint ring branded into the wood.
        val start = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = cell * 0.05f
            color = 0x40301A0C
        }
        canvas.drawCircle(sx(level.start.x), sy(level.start.y), cell * 0.5f, start)

        level.holes.forEach { paintHole(canvas, it) }
        paintHole(canvas, level.goal)
        paintGoalRing(canvas, level.goal)

        // Walls cast soft shadows down and to the right.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x70000000
            maskFilter = BlurMaskFilter(cell * 0.18f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.clipRect(board)
        for (wall in level.walls) {
            canvas.drawRect(
                sx(wall.left) + cell * SHADOW_DX, sy(wall.top) + cell * SHADOW_DY,
                sx(wall.right) + cell * SHADOW_DX, sy(wall.bottom) + cell * SHADOW_DY, shadow,
            )
        }
        canvas.restore()
        return bitmap
    }

    /** A bored hole: dark inside, its far wall catching the light, a crisp rim. */
    private fun paintHole(canvas: Canvas, hole: Hole) {
        val cx = sx(hole.center.x)
        val cy = sy(hole.center.y)
        val r = (hole.radius * cell).toFloat()
        val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.25f, cy - r * 0.25f, r * 1.25f,
                intArrayOf(0xFF050302.toInt(), 0xFF0E0906.toInt(), 0xFF3A2819.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r, inside)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = cell * 0.05f
            color = 0xFF5B3D26.toInt()
        }
        canvas.drawCircle(cx, cy, r, rim)
        rim.color = 0x99F6DDB8.toInt()
        rim.strokeWidth = cell * 0.04f
        val o = r + cell * 0.02f
        canvas.drawArc(cx - o, cy - o, cx + o, cy + o, -20f, 130f, false, rim)
    }

    private fun paintGoalRing(canvas: Canvas, goal: Hole) {
        val cx = sx(goal.center.x)
        val cy = sy(goal.center.y)
        val r = (goal.radius * cell).toFloat() + cell * 0.07f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = cell * 0.12f
            shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, GOLD_LIGHT, GOLD_DARK, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, ring)
    }

    // --- Per frame ---------------------------------------------------------------------------------------------

    private fun drawGoalGlow(canvas: Canvas, t: Double, session: GameSession) {
        if (session.phase == Phase.WON) return
        val pulse = (0.5 + 0.5 * sin(t * 2 * PI / GLOW_PERIOD)).toFloat()
        ringPaint.color = GOLD_LIGHT
        ringPaint.alpha = (40 + 70 * pulse).toInt()
        ringPaint.strokeWidth = cell * (0.06f + 0.06f * pulse)
        val r = (level.goal.radius * cell).toFloat() + cell * (0.2f + 0.08f * pulse)
        canvas.drawCircle(sx(level.goal.center.x), sy(level.goal.center.y), r, ringPaint)
    }

    private fun drawCheckpoints(canvas: Canvas, session: GameSession, t: Double) {
        level.checkpoints.forEachIndexed { i, p ->
            val cx = sx(p.x)
            val cy = sy(p.y)
            val reached = session.checkpointReached(i)
            checkpointPaint.style = Paint.Style.FILL
            checkpointPaint.color = if (reached) CHECKPOINT_ON else CHECKPOINT_OFF
            canvas.drawCircle(cx, cy, cell * 0.34f, checkpointPaint)
            checkpointPaint.style = Paint.Style.STROKE
            checkpointPaint.strokeWidth = cell * 0.07f
            checkpointPaint.color = if (reached) CHECKPOINT_RING_ON else CHECKPOINT_RING_OFF
            canvas.drawCircle(cx, cy, cell * 0.5f, checkpointPaint)
            if (!reached) {
                // A gentle breathing ring invites the marble over.
                val k = ((t % 1.6) / 1.6).toFloat()
                checkpointPaint.alpha = ((1 - k) * 120).toInt()
                checkpointPaint.strokeWidth = cell * 0.04f
                canvas.drawCircle(cx, cy, cell * (0.5f + 0.35f * k), checkpointPaint)
            }
        }
    }

    /**
     * Walls are boxes [WALL_HEIGHT] tall. Tilting the board shifts each top toward the low side; filling the hull
     * of base and top shows the sides facing the viewer.
     */
    private fun drawWalls(canvas: Canvas, tiltX: Float, tiltY: Float) {
        val dx = tiltX * WALL_HEIGHT * LEAN_EXAGGERATION * cell
        val dy = tiltY * WALL_HEIGHT * LEAN_EXAGGERATION * cell
        for (wall in level.walls) {
            val l = sx(wall.left)
            val t = sy(wall.top)
            val r = sx(wall.right)
            val b = sy(wall.bottom)
            hull(l, t, r, b, dx, dy)
            canvas.drawPath(path, wallSidePaint)
        }
        val inset = wallLightEdge.strokeWidth / 2
        for (wall in level.walls) {
            val l = sx(wall.left) + dx
            val t = sy(wall.top) + dy
            val r = sx(wall.right) + dx
            val b = sy(wall.bottom) + dy
            canvas.drawRect(l, t, r, b, wallTopPaint)
            canvas.drawLine(l + inset, b - inset, r - inset, b - inset, wallDarkEdge)
            canvas.drawLine(r - inset, t + inset, r - inset, b - inset, wallDarkEdge)
            canvas.drawLine(l + inset, t + inset, r - inset, t + inset, wallLightEdge)
            canvas.drawLine(l + inset, t + inset, l + inset, b - inset, wallLightEdge)
        }
    }

    /**
     * The convex hull of rectangle (l, t, r, b) and its copy moved by (dx, dy), as [path]: a hexagon whose two
     * extra corners depend on which way the copy moved.
     */
    private fun hull(l: Float, t: Float, r: Float, b: Float, dx: Float, dy: Float) {
        path.rewind()
        if ((dx >= 0) == (dy >= 0)) {
            // Moved down-right (or up-left): corners top-left and bottom-right stretch along the diagonal.
            val tl = if (dx >= 0) 0f else 1f // which copy owns the outermost top-left corner
            path.moveTo(l + dx * tl, t + dy * tl)
            path.lineTo(r + dx * tl, t + dy * tl)
            path.lineTo(r + dx * (1 - tl), t + dy * (1 - tl))
            path.lineTo(r + dx * (1 - tl), b + dy * (1 - tl))
            path.lineTo(l + dx * (1 - tl), b + dy * (1 - tl))
            path.lineTo(l + dx * tl, b + dy * tl)
        } else {
            // Moved up-right (or down-left): corners top-right and bottom-left stretch along the other diagonal.
            val tr = if (dx >= 0) 1f else 0f // which copy owns the outermost top-right corner
            path.moveTo(l + dx * (1 - tr), t + dy * (1 - tr))
            path.lineTo(l + dx * tr, t + dy * tr)
            path.lineTo(r + dx * tr, t + dy * tr)
            path.lineTo(r + dx * tr, b + dy * tr)
            path.lineTo(r + dx * (1 - tr), b + dy * (1 - tr))
            path.lineTo(l + dx * (1 - tr), b + dy * (1 - tr))
        }
        path.close()
    }

    // Where and how the marble appears this frame; set by placeBall.
    private var ballX = 0f
    private var ballY = 0f
    private var ballRadius = 0f
    private var ballZ = 0f
    private var ballVisibility = 1f
    private var ballSink = 0f

    private fun placeBall(session: GameSession, a: Double, tiltX: Float, tiltY: Float) {
        val ball = session.ball
        val prev = session.previousBall
        val x = prev.x + (ball.x - prev.x) * a
        val y = prev.y + (ball.y - prev.y) * a
        val z = (prev.z + (ball.z - prev.z) * a).toFloat()
        val progress = session.animationProgress.toFloat()
        var scale = 1f + 0.35f * z / Physics.JUMP_HEIGHT.toFloat()
        ballVisibility = 1f
        ballSink = 0f
        when (session.phase) {
            Phase.FALLING, Phase.WON -> {
                scale *= 1f - 0.55f * progress
                ballSink = progress
            }
            Phase.RESPAWNING -> {
                scale *= 1.5f - 0.5f * progress
                ballVisibility = progress
            }
            else -> Unit
        }
        ballRadius = (Physics.BALL_RADIUS * cell).toFloat() * scale
        ballZ = z
        // The marble's centre sits a radius above the board, so it leans with the walls.
        val lift = (Physics.BALL_RADIUS.toFloat() + z) * LEAN_EXAGGERATION * cell
        ballX = sx(x) + tiltX * lift
        ballY = sy(y) + tiltY * lift
    }

    /** A soft oval that drifts away and fades as the marble rises. */
    private fun drawBallShadow(canvas: Canvas) {
        if (ballSink > 0f) return
        val z = ballZ
        shadowPaint.alpha = (90 * ballVisibility / (1 + 1.5f * z)).toInt()
        val spread = 1f + 0.4f * z
        val ox = ballX + cell * (SHADOW_DX + 0.5f * z)
        val oy = ballY + cell * (SHADOW_DY + 0.6f * z)
        val rx = ballRadius * spread
        val ry = ballRadius * 0.9f * spread
        canvas.drawOval(ox - rx, oy - ry, ox + rx, oy + ry, shadowPaint)
    }

    /** Polished steel: a bright window reflection top left, a warm rim of light bounced off the wood. */
    private fun drawBallBody(canvas: Canvas) {
        val visibility = ballVisibility
        canvas.save()
        canvas.translate(ballX, ballY)
        canvas.scale(ballRadius, ballRadius)
        ballPaint.alpha = (255 * visibility).toInt()
        canvas.drawCircle(0f, 0f, 1f, ballPaint)
        rimLightPaint.alpha = (0x55 * visibility).toInt()
        rect.set(-0.92f, -0.92f, 0.92f, 0.92f)
        canvas.drawArc(rect, 10f, 100f, false, rimLightPaint)
        glintPaint.alpha = (235 * visibility).toInt()
        canvas.drawCircle(-0.36f, -0.42f, 0.17f, glintPaint)
        glintPaint.alpha = (90 * visibility).toInt()
        canvas.drawCircle(-0.3f, -0.36f, 0.32f, glintPaint)
        if (ballSink > 0f) {
            sinkPaint.color = Color.BLACK
            sinkPaint.alpha = (230 * ballSink).toInt()
            canvas.drawCircle(0f, 0f, 1.02f, sinkPaint)
        }
        canvas.restore()
    }

    private fun drawWinBurst(canvas: Canvas, seconds: Double) {
        val cx = sx(level.goal.center.x)
        val cy = sy(level.goal.center.y)
        for (k in 0 until 3) {
            val t = ((seconds - k * 0.18) / 1.1).toFloat()
            if (t !in 0f..1f) continue
            ringPaint.color = GOLD_LIGHT
            ringPaint.alpha = ((1 - t) * 220).toInt()
            ringPaint.strokeWidth = cell * 0.12f * (1 - t) + 1f
            canvas.drawCircle(cx, cy, cell * (0.6f + 3.2f * t), ringPaint)
        }
    }

    private fun sx(x: Double) = originX + (x * cell).toFloat()
    private fun sy(y: Double) = originY + (y * cell).toFloat()

    private companion object {
        val BACKGROUND = 0xFF17120E.toInt()
        val WALL_TOP = 0xFF7B4F30.toInt()
        val WALL_SIDE = 0xFF452A18.toInt()
        val WALL_EDGE_LIGHT = 0xFFA4724A.toInt()
        val WALL_EDGE_DARK = 0xFF5A3720.toInt()
        val GOLD_LIGHT = 0xFFF6C35A.toInt()
        val GOLD_DARK = 0xFFA8701C.toInt()
        val CHECKPOINT_OFF = 0x332A8C7C
        val CHECKPOINT_ON = 0xFF2FA08C.toInt()
        val CHECKPOINT_RING_OFF = 0xCC2A8C7C.toInt()
        val CHECKPOINT_RING_ON = 0xFF8FE3D2.toInt()

        /** Where shadows fall, in cells: down and right, away from the light. */
        const val SHADOW_DX = 0.1f
        const val SHADOW_DY = 0.14f

        const val WALL_HEIGHT = 0.5f

        /** Parallax is exaggerated a little: a real eye would see only a hint of lean at these angles. */
        const val LEAN_EXAGGERATION = 1.4f
        const val MAX_LEAN = 0.45

        const val GLOW_PERIOD = 1.8
    }
}
