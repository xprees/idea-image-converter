package cz.xprees.imagetools.codec

import com.luciad.imageio.webp.CompressionType
import com.luciad.imageio.webp.WebPImageReaderSpi
import com.luciad.imageio.webp.WebPImageWriterSpi
import com.luciad.imageio.webp.WebPReadParam
import com.luciad.imageio.webp.WebPWriteParam
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import cz.xprees.imagetools.meta.RawMetadata
import cz.xprees.imagetools.meta.WebpMetadata
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** webp-imageio ships JNI libraries for x86/x64 Windows, x64/arm64 macOS and the common Linux CPUs, but not for Windows on ARM. */
private val isSupported: Boolean = !(SystemInfo.isWindows && CpuArch.isArm64())

/** WebP through libwebp's JNI bindings (webp-imageio). SPIs are instantiated directly so the IDE's classloader layout doesn't matter. */
class WebpCodec : ImageCodec {
    override fun canRead(format: ImageFormat) = format == ImageFormat.WEBP && isSupported
    override fun canWrite(format: ImageFormat) = format == ImageFormat.WEBP && isSupported

    override fun frameCount(source: Path, format: ImageFormat): Int = WebpMetadata.frameCount(Files.readAllBytes(source))

    override fun read(source: Path, format: ImageFormat): DecodedImage {
        val bytes = Files.readAllBytes(source)
        val metadata = WebpMetadata.read(bytes)
        val reader = WebPImageReaderSpi().createReaderInstance()
        val decoded = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                reader.setInput(stream, true, false)
                reader.read(0, WebPReadParam())
            }
        } finally {
            reader.dispose()
        }
        val normalised = Pixels.toSrgb(decoded)
        return DecodedImage(normalised, metadata.exif, metadata.icc.takeIf { normalised === decoded }, WebpMetadata.frameCount(bytes))
    }

    override fun write(image: DecodedImage, target: Path, format: ImageFormat, options: EncodeOptions) {
        val writer = WebPImageWriterSpi().createWriterInstance()
        val encoded = ByteArrayOutputStream()
        try {
            val param = (writer.defaultWriteParam as WebPWriteParam).apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                if (options.lossless) {
                    compressionType = CompressionType.Lossless
                } else {
                    compressionType = CompressionType.Lossy
                    compressionQuality = options.quality / 100f
                }
            }
            ImageIO.createImageOutputStream(encoded).use { stream ->
                writer.setOutput(stream)
                writer.write(null, IIOImage(Pixels.toSrgb(image.image), null, null), param)
            }
        } finally {
            writer.dispose()
        }
        Files.write(target, WebpMetadata.write(encoded.toByteArray(), RawMetadata(image.exif, image.icc)))
    }
}
