package cz.xprees.imagetools

import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.codec.NativeTools
import cz.xprees.imagetools.meta.Exif
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.ops.ImageOperations
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Needs the bundled avifenc/avifdec for the host platform (see downloadAvifNatives); skipped when they are absent. */
class AvifTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
        assumeTrue("avifenc/avifdec for ${NativeTools.platformKey} not available", NativeTools.binary("avifenc") != null && NativeTools.binary("avifdec") != null)
    }

    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    private fun writeSource(format: ImageFormat, name: String, image: java.awt.image.BufferedImage, exif: ByteArray? = null, icc: ByteArray? = null): Path {
        val file = path("$name.${format.outputExtension}")
        CodecRegistry.writer(format).write(TestImages.decoded(image, exif, icc), file, format, EncodeOptions(quality = 100))
        return file
    }

    @Test
    fun `avif is offered as a target`() {
        assertThat(CodecRegistry.writableFormats()).contains(ImageFormat.AVIF)
    }

    @Test
    fun `png to avif and back keeps the picture`() {
        val source = writeSource(ImageFormat.PNG, "pic", TestImages.quadrants())
        val avif = path("pic.avif")
        ImageOperations.convert(source, ImageFormat.PNG, avif, ImageFormat.AVIF, EncodeOptions(quality = 90))
        assertThat(Files.size(avif)).isGreaterThan(0)
        val back = path("back.png")
        ImageOperations.convert(avif, ImageFormat.AVIF, back, ImageFormat.PNG, EncodeOptions())
        val decoded = CodecRegistry.reader(ImageFormat.PNG).read(back, ImageFormat.PNG).image
        assertThat(decoded.width to decoded.height).isEqualTo(64 to 48)
        assertThat(TestImages.meanDifference(TestImages.quadrants(), decoded)).isLessThan(8.0)
    }

    @Test
    fun `lossless avif is pixel exact`() {
        val original = TestImages.noise(64, 64)
        val source = writeSource(ImageFormat.PNG, "noise", original)
        val avif = path("noise.avif")
        ImageOperations.convert(source, ImageFormat.PNG, avif, ImageFormat.AVIF, EncodeOptions(lossless = true))
        val decoded = CodecRegistry.reader(ImageFormat.AVIF).read(avif, ImageFormat.AVIF).image
        assertThat(TestImages.meanDifference(original, decoded)).isEqualTo(0.0)
    }

    @Test
    fun `higher quality gives a bigger file`() {
        val source = writeSource(ImageFormat.PNG, "noise", TestImages.noise())
        val low = path("low.avif").also { ImageOperations.convert(source, ImageFormat.PNG, it, ImageFormat.AVIF, EncodeOptions(quality = 20)) }
        val high = path("high.avif").also { ImageOperations.convert(source, ImageFormat.PNG, it, ImageFormat.AVIF, EncodeOptions(quality = 95)) }
        assertThat(Files.size(high)).isGreaterThan(Files.size(low))
    }

    @Test
    fun `alpha survives`() {
        val source = writeSource(ImageFormat.PNG, "alpha", TestImages.halfTransparent())
        val avif = path("alpha.avif")
        ImageOperations.convert(source, ImageFormat.PNG, avif, ImageFormat.AVIF, EncodeOptions(lossless = true))
        val decoded = CodecRegistry.reader(ImageFormat.AVIF).read(avif, ImageFormat.AVIF).image
        assertThat(decoded.getRGB(0, 0) ushr 24).isLessThan(8)
        assertThat(decoded.getRGB(15, 0) ushr 24).isGreaterThan(247)
    }

    @Test
    fun `exif and orientation survive a jpg to avif to webp trip`() {
        val jpg = writeSource(ImageFormat.JPG, "rot", TestImages.quadrants(), exif = TestImages.exifWithOrientation(6))
        val avif = path("rot.avif")
        ImageOperations.convert(jpg, ImageFormat.JPG, avif, ImageFormat.AVIF, EncodeOptions(quality = 95))
        val decoded = CodecRegistry.reader(ImageFormat.AVIF).read(avif, ImageFormat.AVIF)
        assertThat(decoded.image.width to decoded.image.height).describedAs("rotated upright before encoding").isEqualTo(48 to 64)
        assertThat(Exif.orientation(decoded.exif)).isEqualTo(1)
        assertThat(CodecRegistry.reader(ImageFormat.AVIF).frameCount(avif, ImageFormat.AVIF)).isEqualTo(1)
    }

    @Test
    fun `corrupt avif reports a failure`() {
        val broken = path("broken.avif").also { Files.write(it, byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)) }
        val target = path("broken.png")
        val result = runCatching { ImageOperations.convert(broken, ImageFormat.AVIF, target, ImageFormat.PNG, EncodeOptions()) }
        assertThat(result.isFailure).isTrue()
        assertThat(target).doesNotExist()
    }
}
