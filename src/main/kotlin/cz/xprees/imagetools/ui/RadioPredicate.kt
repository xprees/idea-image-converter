package cz.xprees.imagetools.ui

import com.intellij.ui.layout.ComponentPredicate
import javax.swing.JRadioButton
import javax.swing.event.ChangeListener

internal fun radioPredicate(button: JRadioButton) = object : ComponentPredicate() {
    override fun invoke(): Boolean = button.isSelected

    override fun addListener(listener: (Boolean) -> Unit) {
        button.addChangeListener(ChangeListener { listener(button.isSelected) })
    }
}
