// P1-Gate-Werkzeuge (nur JVM, kein App-Bestandteil): Integritätsvergleich der Datenbanken von Handy und Uhr
// („0 verlorene Punkte“) und Auswertung des Feldtest-Logs der Uhr (Akku %/h je Match).
// Nutzt shared/data + core-scoring nur lesend. Bedienung: docs/release/p1-gate-protokoll.md.
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

plugins {
    alias(libs.plugins.advantly.kmp.base)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.shared.data)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

val mainCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("main") as KotlinJvmCompilation
val toolchainLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get())) }

// Relative Pfade in --args beziehen sich auf das Verzeichnis, aus dem Gradle aufgerufen wurde
val invocationDir: File = gradle.startParameter.currentDir

tasks.register<JavaExec>("gateCheck") {
    group = "verification"
    description =
        "P1-Gate: 'compare --db handy=<advantly.db> --db uhr=<advantly.db> --out <dir>' bzw. " +
        "'battery --log <fieldtest.jsonl> --out <dir>' (Argumente über --args=\"…\")."
    dependsOn(mainCompilation.compileTaskProvider)
    classpath = files(mainCompilation.output.allOutputs, mainCompilation.runtimeDependencyFiles)
    mainClass.set("eu.advantly.gate.check.MainKt")
    javaLauncher.set(toolchainLauncher)
    workingDir = invocationDir
    outputs.upToDateWhen { false }
}
