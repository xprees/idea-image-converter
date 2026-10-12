package cz.xprees.imagetools.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import cz.xprees.imagetools.model.EncodeOptions

@Service(Service.Level.APP)
@State(name = "ImageToolsSettings", storages = [Storage("imageTools.xml")])
class ImageToolsSettings : SimplePersistentStateComponent<ImageToolsSettings.State>(State()) {
    class State : BaseState() {
        /** `RRGGBB` without the leading `#`. */
        var backgroundHex by string("FFFFFF")
        var customQuality by property(EncodeOptions.DEFAULT_QUALITY)
        var resizeQuality by property(EncodeOptions.DEFAULT_QUALITY)
    }

    val backgroundRgb: Int get() = parseHex(state.backgroundHex) ?: 0xFFFFFF

    /** Fills in user settings that aren't part of a menu preset. */
    fun withUserSettings(options: EncodeOptions) = options.copy(background = backgroundRgb)

    companion object {
        private val HEX = Regex("#?([0-9a-fA-F]{6})")

        fun getInstance(): ImageToolsSettings = service()

        fun parseHex(text: String?): Int? = text?.trim()?.let(HEX::matchEntire)?.groupValues?.get(1)?.toInt(16)

        fun isValidHex(text: String?) = parseHex(text) != null
    }
}
