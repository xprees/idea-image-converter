package cz.xprees.imagetools.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import cz.xprees.imagetools.ImageToolsBundle.message
import cz.xprees.imagetools.ops.ResizeSpec
import javax.swing.ButtonGroup
import javax.swing.JComponent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel mode (with an aspect-ratio lock) for a single image, percentage for any number of images.
 * @param original size of the single selected image, or null when several are selected.
 */
class ResizeDialog(project: Project, private val original: Pair<Int, Int>?, private val count: Int) : DialogWrapper(project) {
    private val pixels = JBRadioButton(message("resize.mode.pixels"), original != null)
    private val percent = JBRadioButton(message("resize.mode.percent"), original == null)
    private val width = JBIntSpinner(original?.first ?: 1, 1, MAX_SIDE)
    private val height = JBIntSpinner(original?.second ?: 1, 1, MAX_SIDE)
    private val lock = JBCheckBox(message("resize.lock"), true)
    private val scale = JBIntSpinner(50, 1, 1000)
    private var updating = false

    init {
        title = message("resize.dialog.title")
        ButtonGroup().apply { add(pixels); add(percent) }
        if (original != null) {
            val (w, h) = original
            width.addChangeListener {
                if (lock.isSelected && !updating) sync { height.number = max(1, (width.number * h / w.toDouble()).roundToInt()) }
            }
            height.addChangeListener {
                if (lock.isSelected && !updating) sync { width.number = max(1, (height.number * w / h.toDouble()).roundToInt()) }
            }
        }
        init()
    }

    private fun sync(block: () -> Unit) {
        updating = true
        try {
            block()
        } finally {
            updating = false
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        if (original != null) {
            row { label(message("resize.original", original.first, original.second)) }
            row { cell(pixels) }
            indent {
                row(message("resize.width")) { cell(width) }
                row(message("resize.height")) { cell(height) }
                row { cell(lock) }
            }.enabledIf(pixelsSelected())
            row { cell(percent) }
        } else {
            row { label(message("resize.multiple", count)) }
        }
        indent {
            row(message("resize.percent")) {
                cell(scale)
                text("%")
            }
        }.also { if (original != null) it.enabledIf(percentSelected()) }
    }

    private fun pixelsSelected() = radioPredicate(pixels)
    private fun percentSelected() = radioPredicate(percent)

    override fun doValidate(): ValidationInfo? =
        if (pixels.isSelected && (width.number < 1 || height.number < 1)) ValidationInfo(message("resize.error.size"), width) else null

    fun spec(): ResizeSpec = if (original != null && pixels.isSelected) ResizeSpec.Exact(width.number, height.number) else ResizeSpec.Percent(scale.number)

    private companion object {
        const val MAX_SIDE = 100_000
    }
}
