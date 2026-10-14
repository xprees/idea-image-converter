package cz.xprees.imagetools

import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.codec.SvgRenderer
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.ops.ImageOperations
import cz.xprees.imagetools.ops.OutputNaming
import cz.xprees.imagetools.ops.ResizeSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

class SvgTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun headless() {
        System.setProperty("java.awt.headless", "true")
    }

    private fun svg(name: String, content: String): Path = tmp.root.toPath().resolve("$name.svg").also { Files.writeString(it, content) }

    /** 100x50 canvas: left half red, right half blue. */
    private fun halves(attrs: String = """width="100" height="50" viewBox="0 0 100 50"""") = svg(
        "halves",
        """<svg xmlns="http://www.w3.org/2000/svg" $attrs>
             <rect x="0" y="0" width="50" height="50" fill="#ff0000"/>
             <rect x="50" y="0" width="50" height="50" fill="#0000ff"/>
           </svg>""",
    )

    private fun BufferedImage.rgb(x: Int, y: Int) = getRGB(x, y)

    private fun isRed(argb: Int) = (argb ushr 24) > 240 && ((argb shr 16) and 0xFF) > 230 && (argb and 0xFF) < 25
    private fun isBlue(argb: Int) = (argb ushr 24) > 240 && (argb and 0xFF) > 230 && ((argb shr 16) and 0xFF) < 25

    @Test
    fun `intrinsic size comes from width and height, then viewBox, then the browser default`() {
        assertThat(SvgRenderer.intrinsicSize(halves())).isEqualTo(100 to 50)
        val viewBoxOnly = svg("vb", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 30"><rect width="40" height="30"/></svg>""")
        assertThat(SvgRenderer.intrinsicSize(viewBoxOnly)).isEqualTo(40 to 30)
        val bare = svg("bare", """<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>""")
        // no size information at all: JSVG applies the 300x150 default that browsers use for such documents
        assertThat(SvgRenderer.intrinsicSize(bare)).isEqualTo(300 to 150)
    }

    @Test
    fun `renders vector sharp at any scale`() {
        val image = SvgRenderer.render(halves(), 400, 200)
        assertThat(image.width to image.height).isEqualTo(400 to 200)
        assertThat(isRed(image.rgb(10, 100))).isTrue()
        assertThat(isRed(image.rgb(190, 5))).describedAs("edge stays crisp, no blur from upscaling").isTrue()
        assertThat(isBlue(image.rgb(210, 195))).isTrue()
        assertThat(isBlue(image.rgb(390, 100))).isTrue()
    }

    @Test
    fun `scales documents without a viewBox too`() {
        val image = SvgRenderer.render(halves("""width="100" height="50""""), 200, 100)
        assertThat(isRed(image.rgb(20, 50))).isTrue()
        assertThat(isBlue(image.rgb(180, 50))).isTrue()
    }

    @Test
    fun `fits inside a canvas with another aspect ratio and keeps margins transparent`() {
        // 100x50 content into 100x100: scale 1, centred vertically with 25px empty bands
        val image = SvgRenderer.render(halves(), 100, 100)
        assertThat(image.rgb(10, 5) ushr 24).describedAs("top band").isEqualTo(0)
        assertThat(image.rgb(10, 95) ushr 24).describedAs("bottom band").isEqualTo(0)
        assertThat(isRed(image.rgb(10, 50))).isTrue()
        assertThat(isBlue(image.rgb(90, 50))).isTrue()
    }

    @Test
    fun `convert uses the intrinsic size by default and keeps transparency in png`() {
        val source = svg("shape", """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10"><rect x="10" width="10" height="10" fill="#00ff00"/></svg>""")
        val target = source.resolveSibling("shape.png")
        ImageOperations.convert(source, ImageFormat.SVG, target, ImageFormat.PNG, EncodeOptions())
        val image = CodecRegistry.reader(ImageFormat.PNG).read(target, ImageFormat.PNG).image
        assertThat(image.width to image.height).isEqualTo(20 to 10)
        assertThat(image.rgb(2, 5) ushr 24).isEqualTo(0)
        assertThat(image.rgb(15, 5) ushr 24).isEqualTo(255)
    }

    @Test
    fun `convert at a scale or exact size`() {
        val source = halves()
        val at3x = source.resolveSibling("halves@3x.png")
        ImageOperations.convert(source, ImageFormat.SVG, at3x, ImageFormat.PNG, EncodeOptions(), ResizeSpec.Percent(300))
        assertThat(CodecRegistry.reader(ImageFormat.PNG).read(at3x, ImageFormat.PNG).image.let { it.width to it.height }).isEqualTo(300 to 150)

        val exact = source.resolveSibling("halves-64x32.webp")
        ImageOperations.convert(source, ImageFormat.SVG, exact, ImageFormat.WEBP, EncodeOptions(lossless = true), ResizeSpec.Exact(64, 32))
        val webp = CodecRegistry.reader(ImageFormat.WEBP).read(exact, ImageFormat.WEBP).image
        assertThat(webp.width to webp.height).isEqualTo(64 to 32)
        assertThat(isRed(webp.rgb(5, 16))).isTrue()
    }

    @Test
    fun `svg to jpg is flattened onto the background colour`() {
        val source = svg("dot", """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="5" height="10" fill="#000000"/></svg>""")
        val target = source.resolveSibling("dot.jpg")
        ImageOperations.convert(source, ImageFormat.SVG, target, ImageFormat.JPG, EncodeOptions(quality = 100, background = 0xFF0000))
        val image = CodecRegistry.reader(ImageFormat.JPG).read(target, ImageFormat.JPG).image
        assertThat(image.colorModel.hasAlpha()).isFalse()
        assertThat((image.rgb(8, 5) shr 16) and 0xFF).describedAs("transparent half shows the red background").isGreaterThan(220)
    }

    @Test
    fun `resize renders the vector at the requested size`() {
        val source = halves()
        val target = source.resolveSibling("out.png")
        ImageOperations.resize(source, ImageFormat.SVG, target, ImageFormat.PNG, EncodeOptions(), ResizeSpec.Exact(500, 250))
        val image = CodecRegistry.reader(ImageFormat.PNG).read(target, ImageFormat.PNG).image
        assertThat(image.width to image.height).isEqualTo(500 to 250)
        assertThat(isRed(image.rgb(248, 125))).describedAs("still crisp at the seam").isTrue()
        assertThat(isBlue(image.rgb(252, 125))).isTrue()
    }

    @Test
    fun `dimensions report the intrinsic size`() {
        assertThat(ImageOperations.dimensions(halves(), ImageFormat.SVG)).isEqualTo(100 to 50)
    }

    @Test
    fun `oversized output and broken files fail cleanly`() {
        val source = halves()
        val tooBig = source.resolveSibling("big.png")
        val big = runCatching { ImageOperations.convert(source, ImageFormat.SVG, tooBig, ImageFormat.PNG, EncodeOptions(), ResizeSpec.Exact(20_000, 10_000)) }
        assertThat(big.exceptionOrNull()).hasMessageContaining("too large")
        assertThat(tooBig).doesNotExist()

        val broken = svg("broken", "this is not svg")
        val result = runCatching { ImageOperations.convert(broken, ImageFormat.SVG, broken.resolveSibling("broken.png"), ImageFormat.PNG, EncodeOptions()) }
        assertThat(result.isFailure).isTrue()
        assertThat(broken.resolveSibling("broken.png")).doesNotExist()
    }

    @Test
    fun `remote images referenced by an svg are never fetched`() {
        val source = svg(
            "remote",
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="10" height="10">
                 <image xlink:href="http://127.0.0.1:9/never.png" width="10" height="10"/>
                 <rect width="5" height="10" fill="#ff0000"/>
               </svg>""",
        )
        val image = SvgRenderer.render(source)
        assertThat(isRed(image.rgb(2, 5))).isTrue()
    }

    @Test
    fun `output names for scaled vector renders`() {
        val source = Path.of("/icons/logo.svg")
        assertThat(OutputNaming.rendered(source, ImageFormat.PNG, ResizeSpec.Percent(100), 24, 24)).isEqualTo(Path.of("/icons/logo.png"))
        assertThat(OutputNaming.rendered(source, ImageFormat.PNG, ResizeSpec.Percent(200), 48, 48)).isEqualTo(Path.of("/icons/logo@2x.png"))
        assertThat(OutputNaming.rendered(source, ImageFormat.WEBP, ResizeSpec.Percent(150), 36, 36)).isEqualTo(Path.of("/icons/logo-36x36.webp"))
        assertThat(OutputNaming.rendered(source, ImageFormat.AVIF, ResizeSpec.Exact(512, 512), 512, 512)).isEqualTo(Path.of("/icons/logo-512x512.avif"))
    }

    @Test
    fun `svg is a read only source`() {
        assertThat(CodecRegistry.canRead(ImageFormat.SVG)).isTrue()
        assertThat(CodecRegistry.writableFormats()).doesNotContain(ImageFormat.SVG)
        assertThat(ImageFormat.fromExtension("SVG")).isEqualTo(ImageFormat.SVG)
    }
}
