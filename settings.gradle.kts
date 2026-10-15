pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "advantly"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Pure Kotlin (alle Targets inkl. watchOS)
include(":shared:core-model")
include(":shared:core-scoring")
include(":shared:sync-protocol")
// Infrastruktur
include(":shared:device-link")
include(":shared:data")
include(":shared:entitlements")
// Match-Session: Orchestrierung Engine + Store + Geräte-Sync (ohne UI/DI)
include(":shared:match-session")
// Präsentation
include(":shared:presentation")
include(":shared:ui-phone")
// Apple-Frameworks (XCFrameworks werden nur auf macOS gebaut)
include(":shared:umbrella-ios")
include(":shared:umbrella-watch")
// Apps
include(":androidApp")
include(":wearApp")
