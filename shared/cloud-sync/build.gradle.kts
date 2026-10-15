// P2 · Cloud-Sync – SCHNITTSTELLENENTWURF (noch nicht verdrahtet, keine App hängt davon ab).
// Wire-DTOs = Vertrag zu den Supabase-RPCs (supabase/migrations/…0500_rpc_sync.sql), Ports für Backend/Auth/Outbox/Live,
// reine Abbildung Event ↔ Tabellenzeile und Outbox-Batching mit Tests. Die supabase-kt-Implementierung folgt in P2-B
// (docs/p2-plan.md). Kein watchOS (CLAUDE.md Regel 3: umbrella-watch ohne Ktor/supabase-kt).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.data)
            api(libs.kotlinx.serialization.json)
            // StateFlow/Flow in den Ports (Sitzung, Sync-Zustand, Live-Kanäle)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
