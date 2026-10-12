package cz.xprees.imagetools.model

/**
 * @property quality 1..100, ignored for lossless targets and PNG.
 * @property lossless only honoured by formats with [ImageFormat.supportsLossless].
 * @property background RGB used to flatten transparency for targets without alpha.
 */
data class EncodeOptions(
    val quality: Int = DEFAULT_QUALITY,
    val lossless: Boolean = false,
    val background: Int = 0xFFFFFF,
) {
    init {
        require(quality in 1..100) { "quality must be in 1..100 but was $quality" }
    }

    companion object {
        const val DEFAULT_QUALITY = 90
    }
}
