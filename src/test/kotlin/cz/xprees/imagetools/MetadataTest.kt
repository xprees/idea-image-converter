package cz.xprees.imagetools

import cz.xprees.imagetools.meta.Exif
import cz.xprees.imagetools.meta.JpegMetadata
import cz.xprees.imagetools.meta.Orientation
import cz.xprees.imagetools.meta.PngMetadata
import cz.xprees.imagetools.meta.WebpMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class MetadataTest {
    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    private fun jpeg(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()

    @Test
    fun `reads and resets orientation`() {
        val exif = TestImages.exifWithOrientation(6)
        assertThat(Exif.orientation(exif)).isEqualTo(6)
        assertThat(Exif.orientation(Exif.withUprightOrientation(exif))).isEqualTo(1)
        assertThat(Exif.orientation(exif)).describedAs("original untouched").isEqualTo(6)
        assertThat(Exif.orientation(null)).isEqualTo(1)
        assertThat(Exif.orientation(byteArrayOf(1, 2, 3))).isEqualTo(1)
    }

    @Test
    fun `orientation transforms move the red top-left quadrant to the right place`() {
        val src = TestImages.quadrants(4, 2)
        val red = 0xFFFF0000.toInt()
        // orientation -> expected position of the original top-left pixel in the result (and result size)
        val cases = mapOf(
            1 to Triple(0, 0, 4 to 2),
            2 to Triple(3, 0, 4 to 2),
            3 to Triple(3, 1, 4 to 2),
            4 to Triple(0, 1, 4 to 2),
            5 to Triple(0, 0, 2 to 4),
            6 to Triple(1, 0, 2 to 4),
            7 to Triple(1, 3, 2 to 4),
            8 to Triple(0, 3, 2 to 4),
        )
        for ((orientation, expected) in cases) {
            val out = Orientation.apply(src, orientation)
            val (x, y, size) = expected
            assertThat(out.width to out.height).describedAs("size for $orientation").isEqualTo(size)
            assertThat(out.getRGB(x, y)).describedAs("red pixel for $orientation").isEqualTo(red)
        }
    }

    @Test
    fun `jpeg exif and icc round trip, including multi-segment profiles`() {
        val exif = TestImages.exifWithOrientation(3)
        val icc = ByteArray(150_000) { (it % 251).toByte() } // forces three APP2 segments
        val original = jpeg(TestImages.quadrants())
        val written = JpegMetadata.write(original, TestImages.metadata(exif, icc))
        val read = JpegMetadata.read(written)
        assertThat(read.exif).isEqualTo(exif)
        assertThat(read.icc).isEqualTo(icc)
        assertThat(ImageIO.read(written.inputStream())).describedAs("still decodable").isNotNull
    }

    @Test
    fun `jpeg write replaces existing metadata instead of duplicating`() {
        val once = JpegMetadata.write(jpeg(TestImages.quadrants()), TestImages.metadata(TestImages.exifWithOrientation(6), null))
        val twice = JpegMetadata.write(once, TestImages.metadata(TestImages.exifWithOrientation(1), null))
        assertThat(JpegMetadata.read(twice).exif).isEqualTo(TestImages.exifWithOrientation(1))
        assertThat(twice.size).isLessThanOrEqualTo(once.size)
    }

    @Test
    fun `png exif and icc round trip`() {
        val exif = TestImages.exifWithOrientation(8)
        val icc = TestImages.iccProfile()
        val written = PngMetadata.write(png(TestImages.quadrants()), TestImages.metadata(exif, icc))
        val read = PngMetadata.read(written)
        assertThat(read.exif).isEqualTo(exif)
        assertThat(read.icc).isEqualTo(icc)
        assertThat(ImageIO.read(written.inputStream())).isNotNull
    }

    @Test
    fun `webp container rewrite adds exif and icc`() {
        val encoded = com.luciad.imageio.webp.WebPImageWriterSpi().createWriterInstance().let { writer ->
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use {
                writer.setOutput(it)
                writer.write(null, javax.imageio.IIOImage(TestImages.quadrants(), null, null), writer.defaultWriteParam)
            }
            out.toByteArray()
        }
        val exif = TestImages.exifWithOrientation(2)
        val icc = TestImages.iccProfile()
        val written = WebpMetadata.write(encoded, TestImages.metadata(exif, icc))
        val read = WebpMetadata.read(written)
        assertThat(read.exif).isEqualTo(exif)
        assertThat(read.icc).isEqualTo(icc)
        assertThat(WebpMetadata.frameCount(written)).isEqualTo(1)
    }
}
