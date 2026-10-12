package cz.xprees.imagetools.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import cz.xprees.imagetools.ImageToolsBundle
import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.codec.NativeTools
import cz.xprees.imagetools.model.ImageFormat

class ImageToolsConfigurable : BoundConfigurable(ImageToolsBundle.message("settings.display.name")) {
    override fun createPanel() = panel {
        val state = ImageToolsSettings.getInstance().state

        row(ImageToolsBundle.message("settings.background")) {
            textField()
                .columns(8)
                .bindText({ "#" + state.backgroundHex.orEmpty() }, { state.backgroundHex = ImageToolsSettings.parseHex(it)?.let { rgb -> "%06X".format(rgb) } ?: "FFFFFF" })
                .errorOnApply(ImageToolsBundle.message("settings.background.invalid")) { !ImageToolsSettings.isValidHex(it.text) }
                .comment(ImageToolsBundle.message("settings.background.comment"))
        }
        row(ImageToolsBundle.message("settings.quality")) {
            spinner(1..100).bindIntValue({ state.resizeQuality }, { state.resizeQuality = it })
        }
        if (ImageFormat.AVIF !in CodecRegistry.writableFormats()) {
            row {
                comment(ImageToolsBundle.message("settings.avif.missing", NativeTools.platformKey))
            }
        }
    }
}
