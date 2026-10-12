package cz.xprees.imagetools.codec

import cz.xprees.imagetools.meta.Exif
import cz.xprees.imagetools.meta.Orientation
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object Pixels {
    private val standardTypes = setOf(
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_3BYTE_BGR,
        BufferedImage.TYPE_4BYTE_ABGR,
    )

    /** True when [image]'s samples are plain sRGB and therefore safe to hand to any encoder or to pair with a raw ICC blob. */
    fun isPlainSrgb(image: BufferedImage): Boolean =
        image.type in standardTypes && image.colorModel.colorSpace.isCS_sRGB

    /** Redraws into INT_RGB / INT_ARGB (converting palettes, gray and ICC-tagged rasters to sRGB) unless already plain sRGB. */
    fun toSrgb(image: BufferedImage): BufferedImage {
        if (isPlainSrgb(image)) return image
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val w = image.width
        val h = image.height
        return BufferedImage(w, h, type).also { it.setRGB(0, 0, w, h, image.getRGB(0, 0, w, h, null, 0, w), 0, w) }
    }

    /** Composites onto an opaque [background] (RGB) for targets without alpha. No-op for opaque images. */
    fun flatten(image: BufferedImage, background: Int): BufferedImage {
        if (!image.colorModel.hasAlpha()) return toSrgb(image)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color(background)
            g.fillRect(0, 0, out.width, out.height)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** Scales with the best quality Java2D offers: bicubic, stepping down by at most 2x so downscales don't alias. */
    fun scale(image: BufferedImage, width: Int, height: Int): BufferedImage {
        require(width > 0 && height > 0) { "Target size must be positive: ${width}x$height" }
        val source = toSrgb(image)
        val type = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        var current = source
        var w = current.width
        var h = current.height
        while (w != width || h != height) {
            w = stepTowards(w, width)
            h = stepTowards(h, height)
            val next = BufferedImage(w, h, type)
            val g = next.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(current, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
            current = next
        }
        return current
    }

    private fun stepTowards(current: Int, target: Int): Int = when {
        target < current -> maxOf(target, current / 2)
        target > current -> minOf(target, current * 2)
        else -> current
    }
}

/** Applies the EXIF orientation to the pixels and resets the tag, so the result displays upright everywhere. */
fun DecodedImage.upright(): DecodedImage {
    val orientation = Exif.orientation(exif)
    val rotated = Orientation.apply(image, orientation)
    return DecodedImage(
        rotated,
        exif?.let { if (orientation != 1) Exif.withUprightOrientation(it) else it },
        icc,
        frameCount,
    )
}
