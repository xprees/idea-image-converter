package cz.xprees.imagetools.codec

import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import java.nio.file.Path

/** Read-only: SVG is rendered at its own size. Sized renders go through [SvgRenderer] directly. */
class SvgCodec : ImageCodec {
    override fun canRead(format: ImageFormat) = format == ImageFormat.SVG
    override fun canWrite(format: ImageFormat) = false

    override fun read(source: Path, format: ImageFormat) = DecodedImage(SvgRenderer.render(source))

    override fun write(image: DecodedImage, target: Path, format: ImageFormat, options: EncodeOptions) {
        throw UnsupportedFormatException("SVG cannot be written")
    }
}
