// Scoring-Engine: Events, RuleSets, Reducer, Undo, Merge – pure Kotlin (kein Clock/IO).
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            api(projects.shared.coreModel)
        }
        // Property-Tests (Kotest Property) und Testvektoren (JSON) nur auf der JVM –
        // die Pure-Regel gilt für commonMain, nicht für Tests
        jvmTest.dependencies {
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Repo-Root für den Testvektor-Runner (docs/testvectors/ und testvectors/)
val repoRoot: String = rootDir.absolutePath
tasks.named<Test>("jvmTest") {
    systemProperty("advantly.repoRoot", repoRoot)
}

// --- Golden-Testvektoren (01 §8.1) ---------------------------------------------------------------
// testvectors/<sport>/<preset>.json im Repo-Root, erzeugt aus der Kotlin-Engine (je Preset ein kanonischer
// Beispiel-Vektor); Garmin (P4) prüft dagegen. Die Spezifikations-Vektoren unter docs/testvectors/ bleiben, wo sie sind.
val testVectorsDir = layout.projectDirectory.dir("../../testvectors")
val jvmTestCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("test") as KotlinJvmCompilation
val goldenVectorClasspath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
val goldenVectorTool = "eu.advantly.core.scoring.vectors.GoldenVectorToolKt"
val toolchainLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get())) }

tasks.register<JavaExec>("generateTestVectors") {
    group = "verification"
    description = "Erzeugt die Golden-Testvektoren unter testvectors/ (je Preset ein kanonischer Vektor)."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath = goldenVectorClasspath
    mainClass.set(goldenVectorTool)
    javaLauncher.set(toolchainLauncher)
    args("generate", testVectorsDir.asFile.absolutePath)
    outputs.dir(testVectorsDir)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("checkTestVectorDrift") {
    group = "verification"
    description = "Prüft, dass testvectors/ exakt zum aktuellen Engine-Stand passt (sonst: generateTestVectors)."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath = goldenVectorClasspath
    mainClass.set(goldenVectorTool)
    javaLauncher.set(toolchainLauncher)
    args("check", testVectorsDir.asFile.absolutePath)
    inputs.dir(testVectorsDir)
    inputs.files(goldenVectorClasspath)
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn("checkTestVectorDrift")
}
