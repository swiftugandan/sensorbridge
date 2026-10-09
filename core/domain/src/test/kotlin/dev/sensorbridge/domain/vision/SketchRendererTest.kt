package dev.sensorbridge.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchRendererTest {

    private val ink = 0xFF000000.toInt()
    private val paper = 0xFFFFFFFF.toInt()
    private val renderer = SketchRenderer(ink, paper)

    @Test
    fun `a flat image is blank paper`() {
        val frame = frame(40, 30) { _, _ -> 128 }
        assertTrue(render(frame).all { it == paper })
    }

    @Test
    fun `a step edge draws one line hugging the edge`() {
        // Dark left half, bright right half: the stroke sits on the dark side of x = 20.
        val frame = frame(40, 30) { x, _ -> if (x < 20) 40 else 220 }
        val out = render(frame)
        val inkedColumns = (0 until 40).filter { x -> isInk(out[15 * 40 + x]) }
        assertTrue("expected a stroke, got none", inkedColumns.isNotEmpty())
        // The stroke lies on the dark side, within the wide blur's reach of the edge (about 3σ).
        assertTrue("stroke strayed from the edge: $inkedColumns", inkedColumns.all { it in 12..21 })
    }

    @Test
    fun `more detail never removes lines`() {
        val frame = frame(40, 30) { x, y -> 100 + (x * 3 + y * 2) % 40 } // faint texture
        val low = render(frame, SketchParams(detail = 0f)).count(::isInk)
        val high = render(frame, SketchParams(detail = 1f)).count(::isInk)
        assertTrue("low=$low high=$high", high >= low)
    }

    @Test
    fun `rotating a quarter turn swaps dimensions and moves the left edge to the top`() {
        // Dark left column band on a 40×30 frame, rotated 90° clockwise, becomes a dark band along the top.
        val frame = frame(40, 30, rotation = 90) { x, _ -> if (x < 20) 40 else 220 }
        assertEquals(30, renderer.outputWidth(frame))
        assertEquals(40, renderer.outputHeight(frame))
        val out = render(frame)
        val inkedRows = (0 until 40).filter { y -> isInk(out[y * 30 + 15]) }
        assertTrue("stroke rows: $inkedRows", inkedRows.isNotEmpty() && inkedRows.all { it in 12..21 })
    }

    @Test
    fun `mirroring flips left and right`() {
        val plain = render(frame(40, 30) { x, _ -> if (x < 20) 40 else 220 })
        val mirrored = render(frame(40, 30, mirrored = true) { x, _ -> if (x < 20) 40 else 220 })
        for (x in 0 until 40) assertEquals(plain[15 * 40 + x], mirrored[15 * 40 + (39 - x)])
    }

    @Test
    fun `camera noise over a real scene adds almost no ink`() {
        // A scene spanning the brightness range plus preview-level sensor noise (σ ≈ 4 of 255): the noise must
        // not add more than a sliver of ink to what the clean scene draws, even at high detail.
        val random = java.util.Random(7)
        val scene = { x: Int, y: Int -> if ((x / 20 + y / 20) % 2 == 0) 50 else 200 }
        val clean = render(frame(120, 80, value = scene), SketchParams(detail = 0.85f)).count(::isInk)
        val noisy = render(
            frame(120, 80) { x, y -> (scene(x, y) + random.nextGaussian() * 4).toInt().coerceIn(0, 255) },
            SketchParams(detail = 0.85f),
        ).count(::isInk)
        assertTrue("clean $clean vs noisy $noisy inked pixels", noisy - clean < 120 * 80 / 100)
    }

    @Test
    fun `edges between dark surfaces are drawn as firmly as bright ones`() {
        // The same 2:1 brightness ratio, once in the shadows and once in the light, on one frame.
        val frame = frame(80, 30) { x, _ -> if (x < 20) 20 else if (x < 40) 40 else if (x < 60) 110 else 220 }
        val out = render(frame)
        val dark = (12..21).count { isInk(out[15 * 80 + it]) }
        val bright = (52..61).count { isInk(out[15 * 80 + it]) }
        assertTrue("dark edge $dark px vs bright edge $bright px", dark > 0 && dark >= bright - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parameters are validated`() {
        SketchParams(detail = 1.5f)
    }

    private fun isInk(argb: Int) = (argb and 0xFF) < 128

    private fun render(frame: LumaFrame, params: SketchParams = SketchParams()): IntArray =
        IntArray(frame.width * frame.height).also { renderer.render(frame, params, it) }

    private fun frame(w: Int, h: Int, rotation: Int = 0, mirrored: Boolean = false, value: (Int, Int) -> Int) =
        LumaFrame(w, h, ByteArray(w * h) { value(it % w, it / w).toByte() }, rotation, mirrored, 0)
}
