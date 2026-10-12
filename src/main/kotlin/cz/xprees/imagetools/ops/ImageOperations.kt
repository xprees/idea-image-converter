package cz.xprees.imagetools.ops

import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.codec.Pixels
import cz.xprees.imagetools.codec.upright
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Sizes the user may ask for: exact pixels or a percentage of the (oriented) source. */
sealed interface ResizeSpec {
    data class Exact(val width: Int, val height: Int) : ResizeSpec
    data class Percent(val percent: Int) : ResizeSpec

    fun resolve(size: Pair<Int, Int>): Pair<Int, Int> = resolve(size.first, size.second)

    fun resolve(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> = when (this) {
        is Exact -> width to height
        is Percent -> maxOf(1, Math.round(sourceWidth * percent / 100.0).toInt()) to maxOf(1, Math.round(sourceHeight * percent / 100.0).toInt())
    }
}

/** Why a file was left alone (not an error). */
class SkippedException(message: String) : RuntimeException(message)

object ImageOperations {
    /**
     * Decodes [source], re-encodes as [targetFormat] into [target]. The write goes to a temp file first,
     * so an interrupted or failed run never leaves a truncated image behind.
     */
    fun convert(source: Path, sourceFormat: ImageFormat, target: Path, targetFormat: ImageFormat, options: EncodeOptions) {
        transcode(source, sourceFormat, target, targetFormat, options) { it }
    }

    fun resize(source: Path, sourceFormat: ImageFormat, target: Path, targetFormat: ImageFormat, options: EncodeOptions, spec: ResizeSpec) {
        transcode(source, sourceFormat, target, targetFormat, options) { decoded ->
            val (w, h) = spec.resolve(decoded.image.width, decoded.image.height)
            decoded.withImage(Pixels.scale(decoded.image, w, h))
        }
    }

    /** Pixel size after orientation, for pre-filling the resize dialog. */
    fun dimensions(source: Path, format: ImageFormat): Pair<Int, Int> {
        val image = CodecRegistry.reader(format).read(source, format).upright().image
        return image.width to image.height
    }

    private fun transcode(
        source: Path,
        sourceFormat: ImageFormat,
        target: Path,
        targetFormat: ImageFormat,
        options: EncodeOptions,
        transform: (cz.xprees.imagetools.codec.DecodedImage) -> cz.xprees.imagetools.codec.DecodedImage,
    ) {
        val decoded = CodecRegistry.reader(sourceFormat).read(source, sourceFormat)
        val prepared = transform(decoded.upright())
        val temp = Files.createTempFile(target.toAbsolutePath().parent, ".imagetools-", ".tmp")
        try {
            CodecRegistry.writer(targetFormat).write(prepared, temp, targetFormat, options)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun frameCount(source: Path, format: ImageFormat): Int = runCatching { CodecRegistry.reader(format).frameCount(source, format) }.getOrDefault(1)
}
