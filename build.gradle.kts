import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

/**
 * Fetches the avifenc/avifdec archives built by .github/workflows/natives.yml, verifies them against the SHA-256 sums
 * committed in natives/checksums.txt and unpacks them to `<outputDir>/<platform>/`. With no sums listed it does nothing
 * and the plugin is built without AVIF support.
 */
abstract class DownloadAvifNatives : DefaultTask() {
    @get:InputFile
    abstract val checksums: RegularFileProperty

    @get:Input
    abstract val baseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun download() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val entry = Regex("([0-9a-fA-F]{64})\\s+\\*?(avif-([a-z0-9-]+)\\.zip)")
        val wanted = checksums.get().asFile.readLines().mapNotNull { entry.matchEntire(it.trim()) }
        if (wanted.isEmpty()) {
            logger.lifecycle("natives/checksums.txt lists no archives: building without bundled AVIF tools")
            return
        }
        for (match in wanted) {
            val (sha, fileName, platform) = match.destructured
            val url = "${baseUrl.get()}/$fileName"
            logger.lifecycle("Downloading $url")
            val temp = temporaryDir.resolve(fileName)
            val digest = MessageDigest.getInstance("SHA-256")
            URI(url).toURL().openStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha, ignoreCase = true)) {
                throw GradleException("Checksum mismatch for $fileName: expected $sha but got $actual")
            }
            fileSystem.copy {
                from(archives.zipTree(temp))
                into(out.resolve(platform))
            }
        }
    }
}

val downloadAvifNatives = tasks.register<DownloadAvifNatives>("downloadAvifNatives") {
    description = "Downloads and verifies the bundled avifenc/avifdec binaries"
    group = "build"
    checksums = layout.projectDirectory.file("natives/checksums.txt")
    baseUrl = providers.gradleProperty("nativesBaseUrl").orElse(
        providers.gradleProperty("pluginRepositoryUrl").zip(providers.gradleProperty("libavifVersion")) { repo, version ->
            "$repo/releases/download/natives-libavif-v$version"
        },
    )
    outputDir = layout.buildDirectory.dir("natives/bin")
}

val platformVersion = providers.gradleProperty("platformVersion")

// "2026.2.3" -> "262": two-digit year followed by the release number, which is how IDE build numbers start
val ideBuildLine = platformVersion.map { version ->
    val (year, release) = version.split('.').map(String::toInt)
    "${year - 2000}$release"
}
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").orElse(ideBuildLine)
val pluginUntilBuild = providers.gradleProperty("pluginUntilBuild").orElse(ideBuildLine.map { "$it.*" })

dependencies {
    implementation("com.github.usefulness:webp-imageio:0.11.0")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.15.3")
    implementation("com.github.weisj:jsvg:2.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")

    intellijPlatform {
        intellijIdea(platformVersion)
    }
}

java {
    // no Java sources: this only keeps Gradle's Java/Kotlin target validation consistent with the Kotlin target below
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IDE 2026.2 runs on Java 25 and its jars are built for it; Kotlin can't inline their helpers into older bytecode
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

intellijPlatform {
    // Kotlin only, no .form files: bytecode instrumentation (@NotNull checks, UI Designer) buys nothing.
    instrumentCode = false

    pluginConfiguration {
        name = "Image Tools"
        version = providers.gradleProperty("version")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = version.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 0.1.0 -> default channel, 0.1.0-beta.1 -> "beta"
        channels = providers.gradleProperty("version").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, platformVersion.get())
            create(IntelliJPlatformType.WebStorm, platformVersion.get())
            // Rider has no installer-based resolution (intellij-platform-gradle-plugin#1852)
            create(IntelliJPlatformType.Rider, platformVersion.get()) { useInstaller = false }
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    wrapper {
        gradleVersion = "9.8.0"
    }

    // The distribution zip stores plain 644 unless told otherwise; the tools must be runnable straight after install
    buildPlugin {
        eachFile {
            if (name.startsWith("avif") && path.contains("/bin/")) permissions { unix("rwxr-xr-x") }
        }
    }

    // Binaries go next to the jars (<plugin>/bin/<platform>/), not inside them, so they can be executed
    prepareSandbox {
        from(downloadAvifNatives) {
            into(pluginName.map { "$it/bin" })
            filePermissions { unix("rwxr-xr-x") }
        }
    }

    test {
        dependsOn(downloadAvifNatives)
        // AvifTest needs the bundled CLI binaries; IMAGETOOLS_NATIVES_DIR points at a locally built set instead
        val natives = providers.environmentVariable("IMAGETOOLS_NATIVES_DIR")
            .orElse(layout.buildDirectory.dir("natives/bin").map { it.asFile.path })
        systemProperty("imagetools.natives.dir", natives.get())
        systemProperty("java.awt.headless", "true")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
