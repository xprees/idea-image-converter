package cz.xprees.imagetools.codec

import com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReaderSpi
import cz.xprees.imagetools.meta.JpegMetadata
import cz.xprees.imagetools.meta.PngMetadata
import cz.xprees.imagetools.meta.RawMetadata
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream

/** PNG / JPG (read + write) and BMP / GIF (read) on top of the JDK's ImageIO, with TwelveMonkeys for decoding JPEG. */
class ImageIoCodec : ImageCodec {
    override fun canRead(format: ImageFormat) = format in setOf(ImageFormat.PNG, ImageFormat.JPG, ImageFormat.BMP, ImageFormat.GIF)
    override fun canWrite(format: ImageFormat) = format == ImageFormat.PNG || format == ImageFormat.JPG

    override fun frameCount(source: Path, format: ImageFormat): Int {
        if (format != ImageFormat.GIF) return 1
        return withReader(Files.readAllBytes(source), format) { reader, _ -> runCatching { reader.getNumImages(true) }.getOrDefault(1) }
    }

    override fun read(source: Path, format: ImageFormat): DecodedImage {
        val bytes = Files.readAllBytes(source)
        val metadata = when (format) {
            ImageFormat.JPG -> JpegMetadata.read(bytes)
            ImageFormat.PNG -> PngMetadata.read(bytes)
            else -> RawMetadata(null, null)
        }
        val (decoded, frames) = withReader(bytes, format) { reader, _ ->
            reader.read(0) to if (format == ImageFormat.GIF) runCatching { reader.getNumImages(true) }.getOrDefault(1) else 1
        }
        val normalised = Pixels.toSrgb(decoded)
        // An ICC blob only describes the pixels when we did not have to convert them out of that profile.
        val icc = metadata.icc.takeIf { normalised === decoded }
        return DecodedImage(normalised, metadata.exif, icc, frames)
    }

    override fun write(image: DecodedImage, target: Path, format: ImageFormat, options: EncodeOptions) {
        val bytes = when (format) {
            ImageFormat.PNG -> PngMetadata.write(encodePng(Pixels.toSrgb(image.image)), RawMetadata(image.exif, image.icc))
            ImageFormat.JPG -> JpegMetadata.write(
                encodeJpeg(Pixels.flatten(image.image, options.background), options.quality),
                RawMetadata(image.exif, image.icc),
            )
            else -> throw UnsupportedFormatException("ImageIO cannot write $format")
        }
        Files.write(target, bytes)
    }

    /** Plain PNG bytes without ancillary metadata. Also used as the hand-off format to external encoders. */
    fun encodePng(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { out ->
        check(ImageIO.write(image, "png", out)) { "No PNG writer available" }
    }.toByteArray()

    private fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: throw UnsupportedFormatException("No JPEG writer available")
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.setOutput(stream)
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /**
     * TwelveMonkeys' JPEG SPI wraps the JDK's one and only finds it in `onRegistration`. We never register it
     * globally (that would change JPEG decoding for every other plugin in the IDE), so trigger the lookup by hand.
     */
    private val jpegSpi: JPEGImageReaderSpi by lazy {
        JPEGImageReaderSpi().also { it.onRegistration(IIORegistry.getDefaultInstance(), ImageReaderSpi::class.java) }
    }

    private inline fun <T> withReader(bytes: ByteArray, format: ImageFormat, block: (ImageReader, ImageInputStream) -> T): T {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader = if (format == ImageFormat.JPG) {
                jpegSpi.createReaderInstance()
            } else {
                ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                    ?: throw UnsupportedFormatException("Cannot decode ${format.label} image")
            }
            try {
                reader.setInput(stream, true, false)
                return block(reader, stream)
            } finally {
                reader.dispose()
            }
        }
    }
}
