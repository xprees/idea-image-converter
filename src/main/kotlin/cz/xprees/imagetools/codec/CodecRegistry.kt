package cz.xprees.imagetools.codec

import cz.xprees.imagetools.model.ImageFormat

object CodecRegistry {
    private val imageIo = ImageIoCodec()
    private val webp = WebpCodec()
    private val avif = AvifCliCodec(imageIo)

    private val all: List<ImageCodec> get() = listOf(imageIo, webp, avif)

    fun reader(format: ImageFormat): ImageCodec =
        all.firstOrNull { it.canRead(format) } ?: throw UnsupportedFormatException("${format.label} cannot be read on this platform")

    fun writer(format: ImageFormat): ImageCodec =
        all.firstOrNull { it.canWrite(format) } ?: throw UnsupportedFormatException("${format.label} cannot be written on this platform")

    /** Formats that can be offered as conversion targets right now (AVIF depends on the bundled binaries). */
    fun writableFormats(): List<ImageFormat> = ImageFormat.targets.filter { target -> all.any { it.canWrite(target) } }

    fun canRead(format: ImageFormat): Boolean = all.any { it.canRead(format) }
}
