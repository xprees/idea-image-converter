package cz.xprees.imagetools

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.model.ImageFormat

/** An image file we can decode, together with its format. Directories and other file types are never included. */
data class ImageFile(val file: VirtualFile, val format: ImageFormat)

object ImageSelection {
    fun of(event: AnActionEvent): List<ImageFile> {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?: event.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
            ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem) return@mapNotNull null
            val format = ImageFormat.fromExtension(file.extension) ?: return@mapNotNull null
            if (CodecRegistry.canRead(format)) ImageFile(file, format) else null
        }
    }
}
