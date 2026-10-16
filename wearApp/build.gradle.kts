// Wear-OS-App (Compose for Wear OS, Material 3). Logik aus shared/presentation, Zählen über shared/match-session.
plugins {
    alias(libs.plugins.advantly.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.advantly.wear"
    // Compose-UI-Tests unter Robolectric (src/test) brauchen die App-Ressourcen (Strings, Drawables, Locales)
    testOptions.unitTests.isIncludeAndroidResources = true
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
    implementation(libs.kotlinx.serialization.json)
    // Workout: ExerciseClient, Ambient, Ongoing Activity (aus Spike S5)
    implementation(libs.health.services.client)
    implementation(libs.wear.ambient)
    implementation(libs.wear.ongoing)
    implementation(libs.androidx.fragment) // hebt das transitive fragment 1.2.4 aus androidx.wear:wear an

    // JVM-Tests der Session-Integration: SQLite über JDBC statt Android-Treiber
    testImplementation(libs.sqldelight.sqlite.driver)
    // Layout-/Screenshot-Tests der Wear-UI: Compose-UI-Test unter Robolectric (runde Uhr-Displays über Qualifier)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
