package cz.xprees.imagetools.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import cz.xprees.imagetools.ImageToolsBundle.message
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.ops.ResizeSpec
import java.util.Locale
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Size chooser: pixels (aspect ratio locked by default, with a hint when it no longer matches) for a single image,
 * or a percentage for any number of images.
 *
 * @param original size of the single selected image, or null when several are selected.
 * @param vector true for SVG sources: the hint then talks about fitting instead of stretching and scales go higher.
 * @param target when set, the dialog also asks for the quality of this (lossy) output format.
 * @param note extra explanatory line shown on top.
 */
class ResizeDialog(
    project: Project,
    private val original: Pair<Int, Int>?,
    private val count: Int,
    private val vector: Boolean = false,
    private val target: ImageFormat? = null,
    initialQuality: Int = EncodeOptions.DEFAULT_QUALITY,
    private val note: String? = null,
) : DialogWrapper(project) {
    private enum class Mode { PIXELS, PERCENT }

    private var mode = if (original != null) Mode.PIXELS else Mode.PERCENT
    private val width = JBIntSpinner(original?.first ?: 1, 1, MAX_SIDE)
    private val height = JBIntSpinner(original?.second ?: 1, 1, MAX_SIDE)
    private val lock = JBCheckBox(message("resize.lock"), true)
    private val scale = JBIntSpinner(if (vector) 200 else 50, 1, if (vector) 10_000 else 1_000)
    private var updating = false

    var quality: Int = initialQuality.coerceIn(1, 100)
        private set
    var lossless: Boolean = false
        private set

    private var pixelsRadio: JBRadioButton? = null
    private var ratioWarning: Row? = null
    private val content: DialogPanel

    init {
        title = if (target != null) message("svg.dialog.title", target.label) else message("resize.dialog.title")
        if (original != null) {
            val (w, h) = original
            width.addChangeListener {
                if (lock.isSelected && !updating) sync { height.number = max(1, (width.number * h / w.toDouble()).roundToInt()) }
                refreshRatioWarning()
            }
            height.addChangeListener {
                if (lock.isSelected && !updating) sync { width.number = max(1, (height.number * w / h.toDouble()).roundToInt()) }
                refreshRatioWarning()
            }
            lock.addActionListener {
                if (lock.isSelected) resetRatio() else refreshRatioWarning()
            }
        }
        content = buildContent()
        refreshRatioWarning() // the DSL doesn't apply an initial visible(false) to the row's components
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

    /** Snaps the height back to the original ratio, keeping the width. */
    private fun resetRatio() {
        val (w, h) = original ?: return
        sync { height.number = max(1, (width.number * h / w.toDouble()).roundToInt()) }
        refreshRatioWarning()
    }

    /** Differs by more than the one pixel that rounding alone can cause. */
    private fun ratioDiffers(): Boolean {
        val (ow, oh) = original ?: return false
        val expectedHeight = (width.number * oh / ow.toDouble()).roundToInt()
        val expectedWidth = (height.number * ow / oh.toDouble()).roundToInt()
        return abs(height.number - expectedHeight) > 1 && abs(width.number - expectedWidth) > 1
    }

    private fun refreshRatioWarning() {
        ratioWarning?.visible(pixelsRadio?.isSelected != false && ratioDiffers())
    }

    private fun buildContent() = panel {
        note?.let { row { comment(it) } }

        if (original == null) {
            if (note == null) row { label(message("resize.multiple", count)) }
            scaleRow()
            qualityRows()
            return@panel
        }

        val (ow, oh) = original
        row { label(message("resize.original", ow, oh)) }
        // The DSL only accepts radio buttons inside a buttonsGroup, which also binds the selected value to `mode`.
        lateinit var percentButton: JBRadioButton
        buttonsGroup {
            row { pixelsRadio = radioButton(message("resize.mode.pixels"), Mode.PIXELS).component }
            indent {
                row(message("resize.width")) { cell(width) }
                row(message("resize.height")) { cell(height) }
                row { cell(lock) }
                row { comment(message(if (vector) "resize.ratio.original.vector" else "resize.ratio.original.raster", ratioText(ow, oh), ow, oh)) }
                ratioWarning = row {
                    cell(JBLabel(message(if (vector) "resize.ratio.warning.vector" else "resize.ratio.warning.raster"), AllIcons.General.Warning, SwingConstants.LEFT))
                    cell(ActionLink(message("resize.ratio.reset")) { resetRatio() })
                }.visible(false)
            }.enabledIf(radioPredicate(pixelsRadio!!))
            row { percentButton = radioButton(message("resize.mode.percent"), Mode.PERCENT).component }
            indent { scaleRow() }.enabledIf(radioPredicate(percentButton))
        }.bind(::mode)
        pixelsRadio!!.addChangeListener { refreshRatioWarning() }
        qualityRows()
    }

    private fun com.intellij.ui.dsl.builder.Panel.scaleRow() {
        row(message("resize.percent")) {
            cell(scale)
            text("%")
        }
        if (vector) row { comment(message("resize.scale.hint")) }
    }

    private fun com.intellij.ui.dsl.builder.Panel.qualityRows() {
        val format = target ?: return
        if (!format.lossy) return
        separator()
        row(message("quality.label")) { spinner(1..100).bindIntValue(::quality) }
        if (format.supportsLossless) row { checkBox(message("quality.lossless")).bindSelected(::lossless) }
    }

    override fun createCenterPanel(): JComponent = content

    override fun doOKAction() {
        content.apply()
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

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** `16:9` when it reduces to small numbers, otherwise `1.618:1`. */
        fun ratioText(w: Int, h: Int): String {
            val g = gcd(w, h)
            val a = w / g
            val b = h / g
            return if (a <= 50 && b <= 50) "$a:$b" else String.format(Locale.ROOT, "%.3f:1", w.toDouble() / h)
        }
    }
}
