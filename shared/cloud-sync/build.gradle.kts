// P2 · Cloud-Sync-Ports/Outbox-Entwurf und Handy-Auth (P2-C).
// Wire-DTOs = Vertrag zu den Supabase-RPCs (supabase/migrations/…0500_rpc_sync.sql), Ports für Backend/Auth/Outbox/Live,
// reine Abbildung Event ↔ Tabellenzeile und Outbox-Batching mit Tests. Der Sync-Adapter folgt in P2-B
// (docs/p2-plan.md). Kein watchOS (CLAUDE.md Regel 3: umbrella-watch ohne Ktor/supabase-kt).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonTest.dependencies { implementation(libs.ktor.mock) }
        jvmTest.dependencies { implementation(projects.shared.matchSession) }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.androidx.security.crypto)
        }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        jvmMain.dependencies { implementation(libs.ktor.cio) }
        commonMain.dependencies {
            api(projects.shared.data)
            api(libs.kotlinx.serialization.json)
            // StateFlow/Flow in den Ports (Sitzung, Sync-Zustand, Live-Kanäle)
            api(libs.kotlinx.coroutines.core)
            api(libs.supabase.auth)
            api(libs.supabase.postgrest)
        }
    }
}
