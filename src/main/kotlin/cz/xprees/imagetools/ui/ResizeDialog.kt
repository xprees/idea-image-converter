package cz.xprees.imagetools.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel
import cz.xprees.imagetools.ImageToolsBundle.message
import cz.xprees.imagetools.ops.ResizeSpec
import javax.swing.JComponent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel mode (with an aspect-ratio lock) for a single image, percentage for any number of images.
 * @param original size of the single selected image, or null when several are selected.
 */
class ResizeDialog(project: Project, private val original: Pair<Int, Int>?, private val count: Int) : DialogWrapper(project) {
    private enum class Mode { PIXELS, PERCENT }

    private var mode = if (original != null) Mode.PIXELS else Mode.PERCENT
    private val width = JBIntSpinner(original?.first ?: 1, 1, MAX_SIDE)
    private val height = JBIntSpinner(original?.second ?: 1, 1, MAX_SIDE)
    private val lock = JBCheckBox(message("resize.lock"), true)
    private val scale = JBIntSpinner(50, 1, 1000)
    private var updating = false

    private val content: JComponent

    init {
        title = message("resize.dialog.title")
        if (original != null) {
            val (w, h) = original
            width.addChangeListener {
                if (lock.isSelected && !updating) sync { height.number = max(1, (width.number * h / w.toDouble()).roundToInt()) }
            }
            height.addChangeListener {
                if (lock.isSelected && !updating) sync { width.number = max(1, (height.number * w / h.toDouble()).roundToInt()) }
            }
        }
        content = buildContent()
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

    private fun buildContent() = panel {
        if (original == null) {
            row { label(message("resize.multiple", count)) }
            row(message("resize.percent")) {
                cell(scale)
                text("%")
            }
            return@panel
        }

        row { label(message("resize.original", original.first, original.second)) }
        // The DSL only accepts radio buttons inside a buttonsGroup, which also binds the selected value to `mode`.
        lateinit var pixelsButton: JBRadioButton
        lateinit var percentButton: JBRadioButton
        buttonsGroup {
            row { pixelsButton = radioButton(message("resize.mode.pixels"), Mode.PIXELS).component }
            indent {
                row(message("resize.width")) { cell(width) }
                row(message("resize.height")) { cell(height) }
                row { cell(lock) }
            }.enabledIf(radioPredicate(pixelsButton))
            row { percentButton = radioButton(message("resize.mode.percent"), Mode.PERCENT).component }
            indent {
                row(message("resize.percent")) {
                    cell(scale)
                    text("%")
                }
            }.enabledIf(radioPredicate(percentButton))
        }.bind(::mode)
    }

    override fun createCenterPanel(): JComponent = content

    override fun doOKAction() {
        (content as? com.intellij.openapi.ui.DialogPanel)?.apply()
        super.doOKAction()
    }

    override fun doValidate(): ValidationInfo? =
        if (mode == Mode.PIXELS && original != null && (width.number < 1 || height.number < 1)) {
            ValidationInfo(message("resize.error.size"), width)
        } else {
            null
        }

    fun spec(): ResizeSpec = if (original != null && mode == Mode.PIXELS) ResizeSpec.Exact(width.number, height.number) else ResizeSpec.Percent(scale.number)

    private companion object {
        const val MAX_SIDE = 100_000
    }
}
