package cz.xprees.imagetools.meta

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal EXIF (TIFF) handling: we only ever read and rewrite the Orientation tag, the rest is passed through verbatim. */
object Exif {
    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3

    /** Returns 1..8, or 1 when absent or malformed. */
    fun orientation(exif: ByteArray?): Int {
        val (buf, valueOffset) = locateOrientation(exif) ?: return 1
        return buf.getShort(valueOffset).toInt().takeIf { it in 1..8 } ?: 1
    }

    /** Copy of [exif] with the Orientation tag reset to 1 (pixels have been rotated already). */
    fun withUprightOrientation(exif: ByteArray): ByteArray {
        val copy = exif.copyOf()
        locateOrientation(copy)?.let { (buf, valueOffset) -> buf.putShort(valueOffset, 1) }
        return copy
    }

    private fun locateOrientation(exif: ByteArray?): Pair<ByteBuffer, Int>? {
        if (exif == null || exif.size < 8) return null
        val order = when {
            exif[0] == 'I'.code.toByte() && exif[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            exif[0] == 'M'.code.toByte() && exif[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val buf = ByteBuffer.wrap(exif).order(order)
        val ifd0 = buf.getInt(4)
        if (ifd0 < 8 || ifd0 + 2 > exif.size) return null
        val count = buf.getShort(ifd0).toInt() and 0xFFFF
        for (i in 0 until count) {
            val entry = ifd0 + 2 + i * 12
            if (entry + 12 > exif.size) return null
            if ((buf.getShort(entry).toInt() and 0xFFFF) == TAG_ORIENTATION) {
                if (buf.getShort(entry + 2).toInt() != TYPE_SHORT) return null
                return buf to entry + 8
            }
        }
        return null
    }
}

/** Physically applies an EXIF orientation (1..8) so that the result is displayed upright without the tag. */
object Orientation {
    fun apply(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width
        val h = image.height
        val swap = orientation >= 5
        val dw = if (swap) h else w
        val dh = if (swap) w else h
        val src = image.getRGB(0, 0, w, h, null, 0, w)
        val dst = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (dx, dy) = when (orientation) {
                    2 -> (w - 1 - x) to y
                    3 -> (w - 1 - x) to (h - 1 - y)
                    4 -> x to (h - 1 - y)
                    5 -> y to x
                    6 -> (h - 1 - y) to x
                    7 -> (h - 1 - y) to (w - 1 - x)
                    else -> y to (w - 1 - x)
                }
                dst[dy * dw + dx] = src[y * w + x]
            }
        }
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        return BufferedImage(dw, dh, type).also { it.setRGB(0, 0, dw, dh, dst, 0, dw) }
    }
}
