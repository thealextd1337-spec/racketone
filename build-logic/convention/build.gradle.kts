plugins {
    `kotlin-dsl`
}

group = "eu.advantly.buildlogic"

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("buildInfo") {
            id = "advantly.build.info"
            implementationClass = "eu.advantly.buildlogic.BuildInfoConventionPlugin"
        }
        register("kmpBase") {
            id = "advantly.kmp.base"
            implementationClass = "eu.advantly.buildlogic.KmpBaseConventionPlugin"
        }
        register("kmpLibrary") {
            id = "advantly.kmp.library"
            implementationClass = "eu.advantly.buildlogic.KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "advantly.kmp.compose"
            implementationClass = "eu.advantly.buildlogic.KmpComposeConventionPlugin"
        }
        register("androidApplication") {
            id = "advantly.android.application"
            implementationClass = "eu.advantly.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "advantly.android.compose"
            implementationClass = "eu.advantly.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
