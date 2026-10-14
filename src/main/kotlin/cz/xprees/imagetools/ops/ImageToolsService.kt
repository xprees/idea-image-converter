package cz.xprees.imagetools.ops

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import cz.xprees.imagetools.ImageFile
import cz.xprees.imagetools.ImageToolsBundle.message
import cz.xprees.imagetools.codec.CodecRegistry
import cz.xprees.imagetools.model.EncodeOptions
import cz.xprees.imagetools.model.ImageFormat
import cz.xprees.imagetools.settings.ImageToolsSettings
import cz.xprees.imagetools.ui.ResizeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<ImageToolsService>()

/** One unit of batch work: read [source], write [target]. */
private class Job(val source: Path, val sourceFormat: ImageFormat, val target: Path, val targetFormat: ImageFormat)

private enum class Conflict { OVERWRITE, KEEP_BOTH, SKIP }
private enum class AnimatedChoice { FIRST_FRAME, SKIP }

/** Runs conversions and resizes as cancellable background tasks and reports one summary notification per batch. */
@Service(Service.Level.PROJECT)
class ImageToolsService(private val project: Project, private val scope: CoroutineScope) {

    /**
     * @param vectorSize how large SVG sources are rendered; raster sources always keep their pixel size.
     */
    fun convert(images: List<ImageFile>, target: ImageFormat, options: EncodeOptions, vectorSize: ResizeSpec = ResizeSpec.Percent(100)) {
        val effective = ImageToolsSettings.getInstance().withUserSettings(options)
        val jobs = images.mapNotNull { image ->
            val source = image.file.toNioPath()
            // already in the requested format: nothing to do (also covers .jpeg -> .jpg)
            if (image.format == target) null else Job(source, image.format, OutputNaming.converted(source, target), target)
        }
        val alreadyThere = images.size - jobs.size
        // renders at anything but 100 % get a name that says so (logo@2x.png), resolved per file once the SVG's size is known
        val sizedNames: ((Job) -> Path)? = if (vectorSize == ResizeSpec.Percent(100)) null else { job ->
            if (job.sourceFormat.isVector) {
                val (w, h) = vectorSize.resolve(ImageOperations.dimensions(job.source, job.sourceFormat))
                OutputNaming.rendered(job.source, job.targetFormat, vectorSize, w, h)
            } else {
                job.target
            }
        }
        scope.launch {
            runBatch(message("progress.convert", target.label), jobs, effective, initiallySkipped = alreadyThere, resolveTarget = sizedNames) { job, opts ->
                ImageOperations.convert(job.source, job.sourceFormat, job.target, job.targetFormat, opts, vectorSize)
            }.report({ count -> message("result.converted", count, target.label) }, message("result.title.done"))
        }
    }

    /** Reads the pixel size (SVG: its intrinsic size) in the background; reports a failure and returns null. */
    private suspend fun readSize(image: ImageFile): Pair<Int, Int>? = try {
        withBackgroundProgress(project, message("resize.loading"), cancellable = true) {
            runInterruptible(Dispatchers.IO) { ImageOperations.dimensions(image.file.toNioPath(), image.format) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.warn("Cannot read size of ${image.file.path}", e)
        notify(message("result.title.failed"), message("resize.loading.failed", StringUtil.escapeXmlEntities(image.file.name), StringUtil.escapeXmlEntities(e.message.orEmpty())), NotificationType.ERROR)
        null
    }

    /** SVG conversion with a size picked in a dialog: scale or target resolution (single SVG), scale only for several. */
    fun convertWithSizeDialog(images: List<ImageFile>, target: ImageFormat) {
        scope.launch {
            val size = if (images.size == 1) readSize(images.first()) ?: return@launch else null
            val mixed = images.any { !it.format.isVector }
            val settings = ImageToolsSettings.getInstance()
            val dialog = withContext(Dispatchers.EDT) {
                ResizeDialog(
                    project, size, images.size, vector = true, target = target,
                    initialQuality = settings.state.resizeQuality,
                    note = if (mixed) message("svg.note.mixed") else null,
                ).takeIf { it.showAndGet() }?.let { Triple(it.spec(), it.quality, it.lossless) }
            } ?: return@launch
            val (spec, quality, lossless) = dialog
            convert(images, target, EncodeOptions(quality = quality, lossless = lossless), spec)
        }
    }

    /** Asks for a size (reading the first image's dimensions off the EDT) and resizes the selection. */
    fun resize(images: List<ImageFile>) {
        scope.launch {
            val first = images.first()
            val size = if (images.size == 1) readSize(first) ?: return@launch else null
            val spec = withContext(Dispatchers.EDT) {
                ResizeDialog(project, size, images.size, vector = first.format.isVector).takeIf { it.showAndGet() }?.spec()
            } ?: return@launch

            val settings = ImageToolsSettings.getInstance()
            val options = settings.withUserSettings(EncodeOptions(quality = settings.state.resizeQuality))
            val jobs = images.map { image ->
                val source = image.file.toNioPath()
                // BMP and GIF can't be written, so their resized copy becomes a PNG
                val format = if (image.format.writable && CodecRegistry.writableFormats().contains(image.format)) image.format else ImageFormat.PNG
                // the final name depends on the resulting size, which the batch determines per file
                Job(source, image.format, source, format)
            }
            runBatch(message("progress.resize"), jobs, options, initiallySkipped = 0, resolveTarget = { job ->
                // percentages need the source size; exact sizes are known up front
                val (w, h) = (spec as? ResizeSpec.Exact)?.let { it.width to it.height }
                    ?: spec.resolve(ImageOperations.dimensions(job.source, job.sourceFormat))
                OutputNaming.resized(job.source, w, h, job.targetFormat)
            }) { job, opts ->
                ImageOperations.resize(job.source, job.sourceFormat, job.target, job.targetFormat, opts, spec)
            }.report({ count -> message("result.resized", count) }, message("result.title.done"))
        }
    }

    private class Summary(val done: Int, val skipped: Int, val failures: List<Pair<String, String>>, val outputs: List<Path>)

    private suspend fun runBatch(
        title: String,
        jobs: List<Job>,
        options: EncodeOptions,
        initiallySkipped: Int,
        resolveTarget: ((Job) -> Path)? = null,
        operation: (Job, EncodeOptions) -> Unit,
    ): Summary {
        var done = 0
        var skipped = initiallySkipped
        val failures = mutableListOf<Pair<String, String>>()
        val outputs = mutableListOf<Path>()
        var conflictForAll: Conflict? = null
        var animatedForAll: AnimatedChoice? = null

        withBackgroundProgress(project, title, cancellable = true) {
            reportSequentialProgress(jobs.size) { reporter ->
                for (planned in jobs) {
                    reporter.itemStep(planned.source.fileName.toString()) {
                        try {
                            val job = if (resolveTarget == null) planned else {
                                val target = runInterruptible(Dispatchers.IO) { resolveTarget(planned) }
                                Job(planned.source, planned.sourceFormat, target, planned.targetFormat)
                            }

                            val frames = runInterruptible(Dispatchers.IO) { ImageOperations.frameCount(job.source, job.sourceFormat) }
                            if (frames > 1) {
                                val choice = animatedForAll ?: askAnimated(job.source).also { (choice, all) -> if (all) animatedForAll = choice }.first
                                if (choice == AnimatedChoice.SKIP) { skipped++; return@itemStep }
                            }

                            var target = job.target
                            if (Files.exists(target)) {
                                val choice = conflictForAll ?: askConflict(target).also { (choice, all) -> if (all) conflictForAll = choice }.first
                                when (choice) {
                                    Conflict.SKIP -> { skipped++; return@itemStep }
                                    Conflict.KEEP_BOTH -> target = OutputNaming.uniqueSibling(target)
                                    Conflict.OVERWRITE -> Unit
                                }
                            }
                            val finalJob = Job(job.source, job.sourceFormat, target, job.targetFormat)
                            runInterruptible(Dispatchers.IO) { operation(finalJob, options) }
                            outputs.add(target)
                            done++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            LOG.warn("Failed on ${planned.source}", e)
                            failures += planned.source.fileName.toString() to (e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            }
        }
        return Summary(done, skipped, failures, outputs)
    }

    private suspend fun Summary.report(successText: (Int) -> String, title: String) {
        if (outputs.isNotEmpty()) LocalFileSystem.getInstance().refreshNioFiles(outputs)
        val lines = mutableListOf<String>()
        if (done > 0) lines += successText(done)
        if (skipped > 0) lines += message("result.skipped", skipped)
        if (failures.isNotEmpty()) {
            lines += message("result.failed", failures.size)
            failures.take(5).forEach { (name, reason) -> lines += message("result.failure.line", StringUtil.escapeXmlEntities(name), StringUtil.escapeXmlEntities(reason)) }
        }
        if (lines.isEmpty()) return
        val type = when {
            failures.isEmpty() -> NotificationType.INFORMATION
            done == 0 -> NotificationType.ERROR
            else -> NotificationType.WARNING
        }
        notify(if (failures.isEmpty()) title else message("result.title.failed"), lines.joinToString("<br>"), type)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("cz.xprees.imagetools")
            .createNotification(title, content, type)
            .notify(project)
    }

    private suspend fun askConflict(target: Path): Pair<Conflict, Boolean> = withContext(Dispatchers.EDT) {
        var all = false
        val option = remember { all = it }
        val choices = arrayOf(message("conflict.overwrite"), message("conflict.keep.both"), message("conflict.skip"))
        val result = Messages.showDialog(
            project, message("conflict.message", target.fileName), message("conflict.title"), choices, 1,
            Messages.getQuestionIcon(), option.withText(message("conflict.apply.all")),
        )
        val conflict = when (result) {
            0 -> Conflict.OVERWRITE
            1 -> Conflict.KEEP_BOTH
            else -> Conflict.SKIP
        }
        // closing the dialog (Esc) skips this file, but must never silently overwrite the rest
        conflict to (all && result in 0..2)
    }

    private suspend fun askAnimated(source: Path): Pair<AnimatedChoice, Boolean> = withContext(Dispatchers.EDT) {
        var all = false
        val option = remember { all = it }
        val choices = arrayOf(message("animated.first.frame"), message("animated.skip"))
        val result = Messages.showDialog(
            project, message("animated.message", source.fileName), message("animated.title"), choices, 0,
            Messages.getWarningIcon(), option.withText(message("animated.apply.all")),
        )
        (if (result == 0) AnimatedChoice.FIRST_FRAME else AnimatedChoice.SKIP) to (all && result in 0..1)
    }

    private fun remember(onChange: (Boolean) -> Unit) = object : DoNotAskOption.Adapter() {
        var text: String = ""
        fun withText(value: String) = apply { text = value }
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) = onChange(isSelected)
        override fun getDoNotShowMessage(): String = text
    }

    companion object {
        fun getInstance(project: Project): ImageToolsService = project.service()
    }
}
