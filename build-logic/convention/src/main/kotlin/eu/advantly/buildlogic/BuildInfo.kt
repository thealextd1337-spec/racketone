package eu.advantly.buildlogic

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Git nur über Gradles ValueSource-Service; ohne Git/Repository bleibt der Build möglich. */
abstract class GitRevisionSource : ValueSource<String, GitRevisionSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repository: DirectoryProperty
    }

    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String {
        fun git(vararg args: String): String? =
            runCatching {
                val output = ByteArrayOutputStream()
                val result = exec.exec {
                    workingDir(parameters.repository.get().asFile)
                    commandLine("git", *args)
                    standardOutput = output
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                    environment("GIT_OPTIONAL_LOCKS", "0")
                }
                output.toString(Charsets.UTF_8).trim().takeIf { result.exitValue == 0 }
            }.getOrNull()

        val revision = git("rev-parse", "--short", "HEAD")?.takeIf { it.matches(Regex("[0-9a-f]+")) } ?: return "unknown"
        val changes = git("status", "--porcelain", "--untracked-files=normal") ?: return "unknown"
        return revision + if (changes.isEmpty()) "" else "-dirty"
    }
}

/** Task-Eingabe statt Konfigurationseingabe: auch bei CC-Wiederverwendung wird Git erneut gelesen. */
internal fun Project.gitRevision() =
    providers.of(GitRevisionSource::class.java) { parameters.repository.set(rootProject.layout.projectDirectory) }

@DisableCachingByDefault(because = "Debug enthält die aktuelle UTC-Bauminute")
abstract class GenerateAndroidBuildInfo : DefaultTask() {
    @get:Input abstract val revision: Property<String>
    @get:Input abstract val debug: Property<Boolean>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { !debug.get() }
    }

    @TaskAction
    fun generate() {
        val time = if (debug.get()) Instant.now().truncatedTo(ChronoUnit.MINUTES).toString() else ""
        val output = outputDirectory.file("values/build_info.xml").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            |<resources>
            |    <string name="build_git_commit" translatable="false">${revision.get()}</string>
            |    <string name="build_time_utc" translatable="false">$time</string>
            |</resources>
            |
            """.trimMargin(),
        )
    }
}

internal fun Project.configureAndroidBuildInfo() {
    val git = gitRevision()
    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            val task = tasks.register<GenerateAndroidBuildInfo>("generate${variant.name.replaceFirstChar(Char::uppercaseChar)}BuildInfo") {
                revision.set(git)
                debug.set(variant.buildType == "debug")
                outputDirectory.set(layout.buildDirectory.dir("generated/buildInfo/${variant.name}/res"))
            }
            variant.sources.res?.addGeneratedSourceDirectory(task, GenerateAndroidBuildInfo::outputDirectory)
        }
    }
}

/** Bestehender Apple-Versionierungsweg; Zeit nur für die Xcode-Konfiguration Debug. */
@DisableCachingByDefault(because = "Apple-Debug enthält die aktuelle UTC-Bauminute")
abstract class GenerateAppleVersionConfig : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<String>
    @get:Input abstract val revision: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun generate() {
        val name = versionName.get()
        val build = buildNumber.get()
        require(Regex("""\d+\.\d+\.\d+""").matches(name)) { "advantly.versionName muss x.y.z sein (Apple)" }
        require(Regex("[1-9]\\d*").matches(build)) { "advantly.buildNumber muss eine positive Ganzzahl sein" }
        val time = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            // Epoch-Sekunden vermeiden den xcconfig-Kommentartrenner // und lassen sich in Foundation sicher lesen.
            writeText(
                """
                |// Generiert von generateAppleVersionConfig – nicht von Hand ändern.
                |MARKETING_VERSION = $name
                |CURRENT_PROJECT_VERSION = $build
                |ADVANTLY_GIT_COMMIT = ${revision.get()}
                |ADVANTLY_BUILD_TIME =
                |ADVANTLY_BUILD_TIME[config=Debug] = ${time.epochSecond}
                |
                """.trimMargin(),
            )
        }
    }
}

class BuildInfoConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            tasks.register<GenerateAppleVersionConfig>("generateAppleVersionConfig") {
                group = "build setup"
                description = "Erzeugt Apples Versions- und Build-Info aus Gradle und Git."
                versionName.set(providers.gradleProperty("advantly.versionName"))
                buildNumber.set(providers.gradleProperty("advantly.buildNumber"))
                revision.set(gitRevision())
                outputFile.set(layout.projectDirectory.file("iosApp/Config/Version.xcconfig"))
            }
        }
    }
}
