package cz.xprees.imagetools.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import cz.xprees.imagetools.ImageSelection
import cz.xprees.imagetools.ops.ImageToolsService

class ResizeImageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && ImageSelection.of(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val images = ImageSelection.of(e).takeIf { it.isNotEmpty() } ?: return
        ImageToolsService.getInstance(project).resize(images)
    }
}
