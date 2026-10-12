package cz.xprees.imagetools.codec

import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes

/** AVIF through libavif's `avifenc` / `avifdec`, bundled per platform. Swap for an in-process binding later without touching callers. */
class AvifCliCodec(private val png: ImageIoCodec = ImageIoCodec()) : ImageCodec {
    override fun canRead(format: ImageFormat) = format == ImageFormat.AVIF && isAvailable
    override fun canWrite(format: ImageFormat) = format == ImageFormat.AVIF && isAvailable

    val isAvailable: Boolean get() = NativeTools.binary("avifenc") != null && NativeTools.binary("avifdec") != null

    /** `avis` as major or compatible brand in the `ftyp` box marks an image sequence. */
    override fun frameCount(source: Path, format: ImageFormat): Int {
        val head = Files.newInputStream(source).use { it.readNBytes(256) }
        val brands = String(head, Charsets.ISO_8859_1)
        return if (head.size > 12 && brands.substring(4, 8) == "ftyp" && "avis" in brands.substring(8, minOf(brands.length, 64))) 2 else 1
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override fun read(source: Path, format: ImageFormat): DecodedImage {
        val decoder = NativeTools.binary("avifdec") ?: throw UnsupportedFormatException("avifdec is not available on ${NativeTools.platformKey}")
        val temp = Files.createTempDirectory("imagetools-avif")
        try {
            val out = temp.resolve("decoded.png")
            runProcess(listOf(decoder.toString(), "--jobs", "all", source.toString(), out.toString()))
            val decoded = png.read(out, ImageFormat.PNG)
            return DecodedImage(decoded.image, decoded.exif, decoded.icc, frameCount(source, format))
        } finally {
            temp.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override fun write(image: DecodedImage, target: Path, format: ImageFormat, options: EncodeOptions) {
        val encoder = NativeTools.binary("avifenc") ?: throw UnsupportedFormatException("avifenc is not available on ${NativeTools.platformKey}")
        val temp = Files.createTempDirectory("imagetools-avif")
        try {
            val input = temp.resolve("input.png")
            input.writeBytes(png.encodePng(Pixels.toSrgb(image.image)))
            val command = mutableListOf(encoder.toString(), "--jobs", "all", "--speed", "6")
            if (options.lossless) command += "--lossless" else command += listOf("--qcolor", options.quality.toString(), "--qalpha", options.quality.toString())
            image.exif?.let { exif ->
                val file = temp.resolve("meta.exif").also { it.writeBytes(exif) }
                command += listOf("--exif", file.toString())
            }
            image.icc?.let { icc ->
                val file = temp.resolve("meta.icc").also { it.writeBytes(icc) }
                command += listOf("--icc", file.toString())
            }
            command += listOf(input.toString(), target.toString())
            runProcess(command)
        } finally {
            temp.deleteRecursively()
        }
    }
}
