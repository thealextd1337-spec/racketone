// Wear-OS-App (Compose for Wear OS, Material 3). Logik aus shared/presentation, Zählen über shared/match-session.
plugins {
    alias(libs.plugins.advantly.android.compose)
}

android {
    namespace = "eu.advantly.wear"
}

dependencies {
    implementation(projects.shared.presentation)
    // Match-Session (Engine + Store + Geräte-Sync), Single-Scorer-Rollen (W1-D)
    implementation(projects.shared.matchSession)
    // WearableListenerService: Empfang im Hintergrund (Matches vom Handy, Löschmarkierungen)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications)
    implementation(libs.androidx.concurrent)
    // Workout: ExerciseClient, Ambient, Ongoing Activity (aus Spike S5)
    implementation(libs.health.services.client)
    implementation(libs.wear.ambient)
    implementation(libs.wear.ongoing)
    implementation(libs.androidx.fragment) // hebt das transitive fragment 1.2.4 aus androidx.wear:wear an

    // JVM-Tests der Session-Integration: SQLite über JDBC statt Android-Treiber
    testImplementation(libs.sqldelight.sqlite.driver)
}
