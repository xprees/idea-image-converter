package cz.xprees.imagetools.model

enum class ImageFormat(
    val label: String,
    val extensions: Set<String>,
    val writable: Boolean,
    val lossy: Boolean,
    val supportsAlpha: Boolean,
) {
    PNG(".png", setOf("png"), writable = true, lossy = false, supportsAlpha = true),
    JPG(".jpg", setOf("jpg", "jpeg"), writable = true, lossy = true, supportsAlpha = false),
    WEBP(".webp", setOf("webp"), writable = true, lossy = true, supportsAlpha = true),
    AVIF(".avif", setOf("avif"), writable = true, lossy = true, supportsAlpha = true),
    BMP(".bmp", setOf("bmp"), writable = false, lossy = false, supportsAlpha = false),
    GIF(".gif", setOf("gif"), writable = false, lossy = false, supportsAlpha = true);

    /** Extension used for files we write. */
    val outputExtension: String get() = extensions.first()

    /** WebP and AVIF can be encoded losslessly. */
    val supportsLossless: Boolean get() = this == WEBP || this == AVIF

    companion object {
        private val byExtension = entries.flatMap { f -> f.extensions.map { it to f } }.toMap()

        fun fromExtension(extension: String?): ImageFormat? = extension?.lowercase()?.let(byExtension::get)

        val targets: List<ImageFormat> = entries.filter { it.writable }
    }
}
