package cz.xprees.imagetools.codec

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.parser.resources.ResourcePolicy
import com.github.weisj.jsvg.view.ViewBox
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/** Rasterises SVG with JSVG (pure Java). Output is always ARGB; the document is fitted and centred in the requested canvas. */
object SvgRenderer {
    /** Only a safety net for degenerate sizes: JSVG already falls back to 300x150 for documents without any size. */
    const val FALLBACK_SIZE = 512

    const val MAX_SIDE = 16_384
    const val MAX_PIXELS = 100_000_000L

    /** Images referenced relative to the SVG are allowed; nothing is ever fetched from the network. */
    private val context = LoaderContext.builder().externalResourcePolicy(ResourcePolicy.ALLOW_RELATIVE).build()

    private fun load(source: Path): SVGDocument =
        Files.newInputStream(source).use { SVGLoader().load(it, source.toUri(), context) }
            ?: throw UnsupportedFormatException("${source.fileName} is not a valid SVG")

    /** Size in pixels at 100 %, from width/height or the viewBox. */
    fun intrinsicSize(source: Path): Pair<Int, Int> = intrinsicSize(load(source))

    private fun intrinsicSize(document: SVGDocument): Pair<Int, Int> {
        val size = document.size()
        val w = size.width.takeIf { it.isFinite() && it > 0f } ?: FALLBACK_SIZE.toFloat()
        val h = size.height.takeIf { it.isFinite() && it > 0f } ?: FALLBACK_SIZE.toFloat()
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    fun checkSize(width: Int, height: Int) {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS) {
            "Output size ${width}x$height px is too large (at most $MAX_SIDE px per side and ${MAX_PIXELS / 1_000_000} megapixels)"
        }
    }

    fun render(source: Path, width: Int, height: Int): BufferedImage {
        checkSize(width, height)
        val document = load(source)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            document.render(null, g, ViewBox(0f, 0f, width.toFloat(), height.toFloat()))
        } finally {
            g.dispose()
        }
        return image
    }

    /** Renders at 100 %. */
    fun render(source: Path): BufferedImage {
        val (w, h) = intrinsicSize(source)
        return render(source, w, h)
    }
}
