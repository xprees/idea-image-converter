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
import cz.xprees.imagetools.settings.ImageToolsSettings
import cz.xprees.imagetools.ui.QualityDialog

private val PRESET_QUALITIES = listOf(80, 90, 100)

/** "Convert Image to…" popup. Children are rebuilt on each open so the offered formats follow the selection. */
class ConvertImageGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ImageSelection.of(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return AnAction.EMPTY_ARRAY
        val sources = ImageSelection.of(e).map { it.format }.toSet()
        return CodecRegistry.writableFormats()
            // hide the format everything selected already has
            .filterNot { sources.size == 1 && it in sources }
            .map(::entryFor)
            .toTypedArray()
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
) : DumbAwareAction(text) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val images = ImageSelection.of(e).takeIf { it.isNotEmpty() } ?: return
        ImageToolsService.getInstance(project).convert(images, target, options)
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
