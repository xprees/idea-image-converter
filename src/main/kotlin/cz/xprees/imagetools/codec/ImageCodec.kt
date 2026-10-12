package cz.xprees.imagetools.codec

import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Pixels plus the metadata blobs we carry across formats.
 *
 * @property exif raw TIFF-structured EXIF payload (the bytes after `Exif\0\0`), orientation already normalised to 1.
 * @property icc raw ICC profile
 * @property frameCount > 1 for animated sources; only the first frame is decoded.
 */
class DecodedImage(
    val image: BufferedImage,
    val exif: ByteArray? = null,
    val icc: ByteArray? = null,
    val frameCount: Int = 1,
) {
    fun withImage(replacement: BufferedImage) = DecodedImage(replacement, exif, icc, frameCount)
}

/**
 * Reads and writes a set of formats. All implementations must be thread-safe and must not
 * touch the IDE (no PSI/VFS), so they can run on any background thread.
 */
interface ImageCodec {
    fun canRead(format: ImageFormat): Boolean
    fun canWrite(format: ImageFormat): Boolean

    /** Cheap probe (headers only) of how many frames the file holds. */
    fun frameCount(source: Path, format: ImageFormat): Int = 1

    fun read(source: Path, format: ImageFormat): DecodedImage
    fun write(image: DecodedImage, target: Path, format: ImageFormat, options: EncodeOptions)
}

class UnsupportedFormatException(message: String) : RuntimeException(message)
