package cz.xprees.imagetools.codec

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** Locates the platform-specific CLI binaries shipped inside the plugin (`<plugin>/bin/<os>-<arch>/`). */
object NativeTools {

    /** Overrides the lookup, for tests and for running against locally built binaries. */
    const val DIR_PROPERTY = "imagetools.natives.dir"

    val platformKey: String
        get() {
            val os = when {
                SystemInfo.isWindows -> "win"
                SystemInfo.isMac -> "mac"
                else -> "linux"
            }
            val arch = if (CpuArch.isArm64()) "arm64" else "x64"
            return "$os-$arch"
        }

    /** `<plugin>/lib/<this jar>` -> `<plugin>`. Derived from our own jar, which avoids the internal plugin-descriptor API. */
    private fun pluginDirectory(): Path? =
        PathManager.getJarPathForClass(NativeTools::class.java)?.let { Path.of(it).parent?.parent }

    fun binary(name: String): Path? {
        val exe = if (SystemInfo.isWindows) "$name.exe" else name
        val dir = System.getProperty(DIR_PROPERTY)?.let(Path::of)
            ?: pluginDirectory()?.resolve("bin")
            ?: return null
        val file = dir.resolve(platformKey).resolve(exe)
        if (!Files.isRegularFile(file)) return null
        if (!SystemInfo.isWindows && !Files.isExecutable(file)) {
            runCatching {
                Files.setPosixFilePermissions(
                    file,
                    Files.getPosixFilePermissions(file) + setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE),
                )
            }
        }
        return file
    }
}

class ProcessFailedException(message: String) : RuntimeException(message)

/** Runs [command] to completion. A blocked caller that gets interrupted (e.g. coroutine cancellation) kills the process. */
internal fun runProcess(command: List<String>, timeoutSeconds: Long = 300): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    try {
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            throw ProcessFailedException("${Path.of(command.first()).fileName} timed out after ${timeoutSeconds}s")
        }
        if (process.exitValue() != 0) {
            throw ProcessFailedException("${Path.of(command.first()).fileName} exited with ${process.exitValue()}: ${output.trim().take(500)}")
        }
        return output
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
