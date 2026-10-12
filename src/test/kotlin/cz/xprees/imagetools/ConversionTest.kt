package cz.xprees.imagetools

import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.meta.Exif
import cz.xprees.imagetools.meta.JpegMetadata
import cz.xprees.imagetools.meta.PngMetadata
import cz.xprees.imagetools.meta.WebpMetadata
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.ops.ImageOperations
import cz.xprees.imagetools.ops.ResizeSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ConversionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun headless() {
        System.setProperty("java.awt.headless", "true")
    }

    private fun write(format: ImageFormat, name: String, image: java.awt.image.BufferedImage, exif: ByteArray? = null, icc: ByteArray? = null): Path {
        val path = tmp.root.toPath().resolve("$name.${format.outputExtension}")
        CodecRegistry.writer(format).write(TestImages.decoded(image, exif, icc), path, format, EncodeOptions(quality = 100))
        return path
    }

    private fun read(format: ImageFormat, path: Path) = CodecRegistry.reader(format).read(path, format)

    private fun convert(source: Path, from: ImageFormat, to: ImageFormat, options: EncodeOptions = EncodeOptions()): Path {
        val target = source.resolveSibling("${source.fileName.toString().substringBeforeLast('.')}-out.${to.outputExtension}")
        ImageOperations.convert(source, from, target, to, options)
        return target
    }

    @Test
    fun `every pair of PNG JPG WEBP converts and keeps the picture`() {
        val formats = listOf(ImageFormat.PNG, ImageFormat.JPG, ImageFormat.WEBP)
        for (from in formats) for (to in formats) {
            if (from == to) continue
            val source = write(from, "src-$from-$to", TestImages.quadrants())
            val out = convert(source, from, to, EncodeOptions(quality = 100))
            val decoded = read(to, out).image
            assertThat(decoded.width to decoded.height).describedAs("$from -> $to size").isEqualTo(64 to 48)
            assertThat(TestImages.meanDifference(TestImages.quadrants(), decoded)).describedAs("$from -> $to fidelity").isLessThan(6.0)
        }
    }

    @Test
    fun `higher jpeg quality gives a bigger file`() {
        val source = write(ImageFormat.PNG, "noise", TestImages.noise())
        val low = Files.size(convert(source, ImageFormat.PNG, ImageFormat.JPG, EncodeOptions(quality = 20)))
        val high = Files.size(convert(source, ImageFormat.PNG, ImageFormat.JPG, EncodeOptions(quality = 100)))
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `higher webp quality gives a bigger file`() {
        val source = write(ImageFormat.PNG, "noise", TestImages.noise())
        val low = Files.size(convert(source, ImageFormat.PNG, ImageFormat.WEBP, EncodeOptions(quality = 20)))
        val high = Files.size(convert(source, ImageFormat.PNG, ImageFormat.WEBP, EncodeOptions(quality = 95)))
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `lossless webp is pixel exact`() {
        val original = TestImages.noise()
        val source = write(ImageFormat.PNG, "noise", original)
        val out = convert(source, ImageFormat.PNG, ImageFormat.WEBP, EncodeOptions(lossless = true))
        assertThat(TestImages.meanDifference(original, read(ImageFormat.WEBP, out).image)).isEqualTo(0.0)
    }

    @Test
    fun `transparency survives png to webp and is flattened onto the background for jpg`() {
        val source = write(ImageFormat.PNG, "alpha", TestImages.halfTransparent())
        val webp = read(ImageFormat.WEBP, convert(source, ImageFormat.PNG, ImageFormat.WEBP, EncodeOptions(lossless = true))).image
        assertThat(webp.getRGB(0, 0) ushr 24).isEqualTo(0)
        assertThat(webp.getRGB(15, 0) ushr 24).isEqualTo(255)

        val white = read(ImageFormat.JPG, convert(source, ImageFormat.PNG, ImageFormat.JPG, EncodeOptions(quality = 100))).image
        assertThat(white.colorModel.hasAlpha()).isFalse()
        assertThat(white.getRGB(0, 0) and 0xFFFFFF).describedAs("transparent -> default white").isGreaterThan(0xF8F8F8)

        val green = read(ImageFormat.JPG, convert(source, ImageFormat.PNG, ImageFormat.JPG, EncodeOptions(quality = 100, background = 0x00FF00))).image
        assertThat((green.getRGB(0, 0) shr 8) and 0xFF).isGreaterThan(200)
        assertThat((green.getRGB(0, 0) shr 16) and 0xFF).isLessThan(60)
    }

    @Test
    fun `exif orientation is applied to the pixels and the tag is reset`() {
        // 64x48 image tagged "rotate 90 CW": the upright result is 48x64 with the red quadrant at the top right
        val source = write(ImageFormat.JPG, "oriented", TestImages.quadrants(), exif = TestImages.exifWithOrientation(6))
        for (target in listOf(ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.JPG)) {
            val out = convert(source, ImageFormat.JPG, target, EncodeOptions(quality = 100))
            val decoded = read(target, out)
            assertThat(decoded.image.width to decoded.image.height).describedAs("$target size").isEqualTo(48 to 64)
            val topRight = decoded.image.getRGB(decoded.image.width - 4, 4)
            assertThat((topRight shr 16) and 0xFF).describedAs("$target red channel at top right").isGreaterThan(200)
            assertThat(Exif.orientation(decoded.exif)).describedAs("$target orientation tag").isEqualTo(1)
        }
    }

    @Test
    fun `exif and icc are carried through PNG WEBP and JPG`() {
        val exif = TestImages.exifWithOrientation(1)
        val icc = TestImages.iccProfile()
        val png = write(ImageFormat.PNG, "meta", TestImages.quadrants(), exif, icc)
        assertThat(PngMetadata.read(Files.readAllBytes(png)).icc).isEqualTo(icc)

        val webp = convert(png, ImageFormat.PNG, ImageFormat.WEBP, EncodeOptions(lossless = true))
        val webpMeta = WebpMetadata.read(Files.readAllBytes(webp))
        assertThat(webpMeta.icc).isEqualTo(icc)
        assertThat(webpMeta.exif).isEqualTo(exif)

        val jpg = convert(webp, ImageFormat.WEBP, ImageFormat.JPG, EncodeOptions(quality = 90))
        val jpgMeta = JpegMetadata.read(Files.readAllBytes(jpg))
        assertThat(jpgMeta.icc).isEqualTo(icc)
        assertThat(jpgMeta.exif).isEqualTo(exif)
    }

    @Test
    fun `resize by pixels and percent`() {
        val source = write(ImageFormat.PNG, "big", TestImages.quadrants(200, 100))
        val exact = source.resolveSibling("exact.png")
        ImageOperations.resize(source, ImageFormat.PNG, exact, ImageFormat.PNG, EncodeOptions(), ResizeSpec.Exact(50, 25))
        assertThat(read(ImageFormat.PNG, exact).image.let { it.width to it.height }).isEqualTo(50 to 25)

        val percent = source.resolveSibling("percent.webp")
        ImageOperations.resize(source, ImageFormat.PNG, percent, ImageFormat.WEBP, EncodeOptions(quality = 90), ResizeSpec.Percent(150))
        assertThat(read(ImageFormat.WEBP, percent).image.let { it.width to it.height }).isEqualTo(300 to 150)
    }

    @Test
    fun `resize spec rounds and never reaches zero`() {
        assertThat(ResizeSpec.Percent(50).resolve(101, 51)).isEqualTo(51 to 26)
        assertThat(ResizeSpec.Percent(1).resolve(10, 10)).isEqualTo(1 to 1)
        assertThat(ResizeSpec.Exact(7, 9).resolve(100, 100)).isEqualTo(7 to 9)
    }

    @Test
    fun `failed conversion leaves no partial output`() {
        val broken = tmp.root.toPath().resolve("broken.png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val target = tmp.root.toPath().resolve("broken.jpg")
        runCatching { ImageOperations.convert(broken, ImageFormat.PNG, target, ImageFormat.JPG, EncodeOptions()) }
            .also { assertThat(it.isFailure).isTrue() }
        assertThat(target).doesNotExist()
        assertThat(tmp.root.listFiles { f -> f.name.startsWith(".imagetools-") }).isEmpty()
    }
}
