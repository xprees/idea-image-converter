package cz.xprees.imagetools.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import cz.xprees.imagetools.ImageSelection
import cz.xprees.imagetools.ImageToolsBundle.message
import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.ops.ImageToolsService
import cz.xprees.imagetools.ops.ResizeSpec
import cz.xprees.imagetools.settings.ImageToolsSettings
import cz.xprees.imagetools.ui.QualityDialog

private val PRESET_QUALITIES = listOf(80, 90, 100)
private val SCALE_PRESETS = listOf(2, 3)

/** "Convert Image to…" popup. Children are rebuilt on each open so the offered formats follow the selection. */
class ConvertImageGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ImageSelection.of(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return AnAction.EMPTY_ARRAY
        val sources = ImageSelection.of(e).map { it.format }.toSet()
        val hasVector = sources.any { it.isVector }
        return CodecRegistry.writableFormats()
            // hide the format everything selected already has
            .filterNot { sources.size == 1 && it in sources }
            .map { if (hasVector) vectorEntryFor(it) else entryFor(it) }
            .toTypedArray()
    }

    /** SVG has no pixel size, so every target offers size presets instead of quality ones (custom size also asks for quality). */
    private fun vectorEntryFor(format: ImageFormat): AnAction {
        val options = if (format.lossy) EncodeOptions(quality = ImageToolsSettings.getInstance().state.resizeQuality) else EncodeOptions()
        val group = DefaultActionGroup(format.label, true)
        group.add(ConvertAction(format, options, message("svg.size.original")))
        SCALE_PRESETS.forEach { group.add(ConvertAction(format, options, message("svg.size.scale", it), ResizeSpec.Percent(it * 100))) }
        group.add(Separator.getInstance())
        group.add(CustomSvgSizeAction(format))
        return group
    }

    private fun entryFor(format: ImageFormat): AnAction {
        if (!format.lossy) return ConvertAction(format, EncodeOptions(), format.label)
        val group = DefaultActionGroup(format.label, true)
        PRESET_QUALITIES.forEach { group.add(ConvertAction(format, EncodeOptions(quality = it), message("convert.quality", it))) }
        if (format.supportsLossless) group.add(ConvertAction(format, EncodeOptions(lossless = true), message("convert.lossless")))
        group.add(Separator.getInstance())
        group.add(CustomQualityAction(format))
        return group
    }
}

class ConvertAction(
    private val target: ImageFormat,
    private val options: EncodeOptions,
    text: String,
    private val vectorSize: ResizeSpec = ResizeSpec.Percent(100),
) : DumbAwareAction(text) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val images = ImageSelection.of(e).takeIf { it.isNotEmpty() } ?: return
        ImageToolsService.getInstance(project).convert(images, target, options, vectorSize)
    }
}

class CustomQualityAction(private val target: ImageFormat) : DumbAwareAction(message("convert.custom")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val images = ImageSelection.of(e).takeIf { it.isNotEmpty() } ?: return
        val settings = ImageToolsSettings.getInstance()
        val dialog = QualityDialog(project, target, settings.state.customQuality)
        if (!dialog.showAndGet()) return
        settings.state.customQuality = dialog.quality
        ImageToolsService.getInstance(project).convert(images, target, EncodeOptions(quality = dialog.quality, lossless = dialog.lossless))
    }
}

class CustomSvgSizeAction(private val target: ImageFormat) : DumbAwareAction(message("svg.size.custom")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val images = ImageSelection.of(e).takeIf { it.isNotEmpty() } ?: return
        ImageToolsService.getInstance(project).convertWithSizeDialog(images, target)
    }
}
