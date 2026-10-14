// Scoring-Engine: Events, RuleSets, Reducer, Undo, Merge – pure Kotlin (kein Clock/IO).
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
        // Property-Tests (Kotest Property) nur auf der JVM – die Pure-Regel gilt für commonMain, nicht für Tests
        jvmTest.dependencies {
            implementation(libs.kotest.property)
        }
    }
}

// --- Golden-Testvektoren (01 §8.1) ---------------------------------------------------------------
// Ziel: testvectors/<sport>/<name>.json im Repo-Root, erzeugt aus der Kotlin-Engine; Garmin (P4) prüft dagegen.
// Die Spezifikations-Vektoren unter docs/testvectors/ bleiben, wo sie sind (Regel-Referenz, nicht generiert).
val testVectorsDir = layout.projectDirectory.dir("../../testvectors")

tasks.register("generateTestVectors") {
    group = "verification"
    description = "Erzeugt die Golden-Testvektoren unter testvectors/ (Platzhalter, Welle 1)."
    outputs.dir(testVectorsDir)
    doLast {
        // TODO(Welle 1, Paket A): Generator aus jvmTest/jvmMain aufrufen (z. B. JavaExec auf einen
        //  TestVectorGenerator mit festem Seed) und testvectors/<sport>/<name>.json schreiben.
        logger.lifecycle("generateTestVectors: Platzhalter – noch keine Engine (Welle 1).")
    }
}

tasks.register("checkTestVectorDrift") {
    group = "verification"
    description = "Prüft, dass testvectors/ zum aktuellen Engine-Stand passt (Platzhalter, Welle 1)."
    dependsOn("generateTestVectors")
    doLast {
        // TODO(Welle 1, Paket A): nach generateTestVectors `git diff --exit-code -- testvectors/` ausführen
        //  (bzw. Dateien vergleichen) und bei Drift fehlschlagen; dann an `check` und an die CI hängen.
        logger.lifecycle("checkTestVectorDrift: Platzhalter – noch nicht an check gehängt.")
    }
}
