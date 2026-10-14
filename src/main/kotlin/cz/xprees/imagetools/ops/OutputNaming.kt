package cz.xprees.imagetools.ops

import cz.xprees.imagetools.model.ImageFormat
import java.nio.file.Files
import java.nio.file.Path

object OutputNaming {
    fun converted(source: Path, target: ImageFormat): Path = source.resolveSibling("${baseName(source)}.${target.outputExtension}")

    fun resized(source: Path, width: Int, height: Int, format: ImageFormat): Path =
        source.resolveSibling("${baseName(source)}-${width}x$height.${format.outputExtension}")

    /**
     * Name for a vector rendered at [spec]: plain at 100 %, `logo@2x.png` for whole multiples, otherwise `logo-512x512.png`
     * (so rendering the same SVG at several sizes doesn't keep colliding).
     */
    fun rendered(source: Path, target: ImageFormat, spec: ResizeSpec, width: Int, height: Int): Path = when {
        spec is ResizeSpec.Percent && spec.percent == 100 -> converted(source, target)
        spec is ResizeSpec.Percent && spec.percent % 100 == 0 -> source.resolveSibling("${baseName(source)}@${spec.percent / 100}x.${target.outputExtension}")
        else -> resized(source, width, height, target)
    }

    /** `photo.png` → `photo-1.png`, `photo-2.png`, … first one that doesn't exist. */
    fun uniqueSibling(path: Path): Path {
        if (!Files.exists(path)) return path
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = path.resolveSibling("$base-$i$ext")
            if (!Files.exists(candidate)) return candidate
            i++
        }
    }

    fun baseName(path: Path): String = path.fileName.toString().substringBeforeLast('.')

    fun formatOf(path: Path): ImageFormat? = ImageFormat.fromExtension(path.fileName.toString().substringAfterLast('.', ""))
}
