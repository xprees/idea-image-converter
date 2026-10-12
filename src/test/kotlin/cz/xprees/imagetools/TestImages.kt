package cz.xprees.imagetools

import cz.xprees.imagetools.codec.DecodedImage
import cz.xprees.imagetools.meta.RawMetadata
import java.awt.Color
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TestImages {
    /** Four solid quadrants, so any flip or rotation is detectable: TL red, TR green, BL blue, BR yellow. */
    fun quadrants(width: Int = 64, height: Int = 48, alpha: Boolean = false): BufferedImage {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val color = when {
                y < height / 2 && x < width / 2 -> Color.RED
                y < height / 2 -> Color.GREEN
                x < width / 2 -> Color.BLUE
                else -> Color.YELLOW
            }
            image.setRGB(x, y, color.rgb)
        }
        return image
    }

    /** Noisy photo-like content so quality settings visibly change the file size. */
    fun noise(width: Int = 128, height: Int = 128): BufferedImage {
        val random = java.util.Random(42)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            for (y in 0 until height) for (x in 0 until width) {
                image.setRGB(x, y, (random.nextInt(256) shl 16) or (((x * 2) and 0xFF) shl 8) or ((y * 2) and 0xFF))
            }
        }
    }

    /** Fully transparent left half, opaque red right half. */
    fun halfTransparent(width: Int = 16, height: Int = 16): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            for (y in 0 until height) for (x in 0 until width) {
                image.setRGB(x, y, if (x < width / 2) 0x00000000 else 0xFFFF0000.toInt())
            }
        }

    /** Minimal little-endian EXIF block holding only an Orientation tag. */
    fun exifWithOrientation(orientation: Int): ByteArray =
        ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
            .putShort(1)
            .putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
            .putInt(0)
            .array()

    /** A real, non-sRGB profile so tests can tell whether it travelled with the file. */
    fun iccProfile(): ByteArray = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).data

    fun decoded(image: BufferedImage, exif: ByteArray? = null, icc: ByteArray? = null) = DecodedImage(image, exif, icc)

    fun metadata(exif: ByteArray? = null, icc: ByteArray? = null) = RawMetadata(exif, icc)

    /** Mean absolute per-channel difference between two same-sized images (0..255). */
    fun meanDifference(a: BufferedImage, b: BufferedImage): Double {
        require(a.width == b.width && a.height == b.height) { "size mismatch ${a.width}x${a.height} vs ${b.width}x${b.height}" }
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            total += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                Math.abs((p and 0xFF) - (q and 0xFF))
        }
        return total / (3.0 * a.width * a.height)
    }
}
