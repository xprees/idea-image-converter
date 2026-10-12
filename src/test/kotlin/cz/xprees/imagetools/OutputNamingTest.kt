package cz.xprees.imagetools

import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.ops.OutputNaming
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class OutputNamingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sibling names`() {
        val source = Path.of("/images/holiday.photo.jpeg")
        assertThat(OutputNaming.converted(source, ImageFormat.WEBP)).isEqualTo(Path.of("/images/holiday.photo.webp"))
        assertThat(OutputNaming.converted(source, ImageFormat.JPG)).isEqualTo(Path.of("/images/holiday.photo.jpg"))
        assertThat(OutputNaming.resized(source, 800, 600, ImageFormat.PNG)).isEqualTo(Path.of("/images/holiday.photo-800x600.png"))
    }

    @Test
    fun `unique sibling counts up`() {
        val base = tmp.root.toPath().resolve("a.png")
        assertThat(OutputNaming.uniqueSibling(base)).isEqualTo(base)
        Files.createFile(base)
        assertThat(OutputNaming.uniqueSibling(base).fileName.toString()).isEqualTo("a-1.png")
        Files.createFile(base.resolveSibling("a-1.png"))
        assertThat(OutputNaming.uniqueSibling(base).fileName.toString()).isEqualTo("a-2.png")
    }

    @Test
    fun `format detection by extension`() {
        assertThat(ImageFormat.fromExtension("JPEG")).isEqualTo(ImageFormat.JPG)
        assertThat(ImageFormat.fromExtension("webp")).isEqualTo(ImageFormat.WEBP)
        assertThat(ImageFormat.fromExtension("txt")).isNull()
        assertThat(OutputNaming.formatOf(Path.of("x/y.AVIF"))).isEqualTo(ImageFormat.AVIF)
        assertThat(OutputNaming.formatOf(Path.of("x/README"))).isNull()
    }
}
