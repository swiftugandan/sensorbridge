package dev.sensorbridge.domain.vision

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual check, not an assertion: renders every JPEG in `$SKETCH_PREVIEW_DIR/in` at a few settings into
 * `$SKETCH_PREVIEW_DIR/out`, so changes to the renderer can be judged on real photos. Skipped unless set.
 */
class SketchPreviewTest {

    @Test
    fun `render previews`() {
        val dir = System.getenv("SKETCH_PREVIEW_DIR")
        assumeTrue("set SKETCH_PREVIEW_DIR to render previews", dir != null)
        val out = File(dir, "out").apply { mkdirs() }
        val renderer = SketchRenderer(ink = 0xFF2A2B2E.toInt(), paper = 0xFFFBF8F1.toInt())
        File(dir, "in").listFiles { f -> f.extension.lowercase() == "jpg" }!!.sorted().forEach { file ->
            val frame = lumaOf(ImageIO.read(file))
            for (detail in listOf(0.5f, 0.85f)) {
                val pixels = IntArray(frame.width * frame.height)
                renderer.render(frame, SketchParams(detail = detail), pixels)
                val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                image.setRGB(0, 0, frame.width, frame.height, pixels, 0, frame.width)
                ImageIO.write(image, "png", File(out, "${file.nameWithoutExtension}_d${(detail * 100).toInt()}.png"))
            }
        }
    }

    /** Downscales to camera-analysis size (longest side 640) and takes Rec. 601 luma, like the Y plane. */
    private fun lumaOf(source: BufferedImage): LumaFrame {
        val scale = 640f / maxOf(source.width, source.height)
        val w = (source.width * scale).toInt()
        val h = (source.height * scale).toInt()
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, w, h, null)
            dispose()
        }
        val luma = ByteArray(w * h) { i ->
            val rgb = scaled.getRGB(i % w, i / w)
            val y = 0.299 * (rgb shr 16 and 0xFF) + 0.587 * (rgb shr 8 and 0xFF) + 0.114 * (rgb and 0xFF)
            y.toInt().coerceIn(0, 255).toByte()
        }
        return LumaFrame(w, h, luma, 0, false, 0)
    }
}
