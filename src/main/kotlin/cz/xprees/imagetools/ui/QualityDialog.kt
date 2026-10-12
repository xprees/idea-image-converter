package cz.xprees.imagetools.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import cz.xprees.imagetools.ImageToolsBundle.message
import cz.xprees.imagetools.model.ImageFormat
import javax.swing.JComponent

class QualityDialog(project: Project, private val format: ImageFormat, initialQuality: Int) : DialogWrapper(project) {
    var quality: Int = initialQuality.coerceIn(1, 100)
        private set
    var lossless: Boolean = false
        private set

    private val content = panel {
        row(message("quality.label")) {
            spinner(1..100).bindIntValue(::quality)
        }
        if (format.supportsLossless) {
            row {
                checkBox(message("quality.lossless")).bindSelected(::lossless)
            }
        }
    }

    init {
        title = "${message("quality.dialog.title")} (${format.label})"
        init()
    }

    override fun createCenterPanel(): JComponent = content

    override fun doOKAction() {
        content.apply()
        super.doOKAction()
    }
}
