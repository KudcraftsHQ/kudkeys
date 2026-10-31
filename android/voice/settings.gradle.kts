// STANDALONE ONLY. Lets android/voice build and test on its own before it joins the
// keyboard's Gradle root. The integrator deletes this file (see README.md).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // Same AGP as HeliBoard's root build.gradle.kts.
            if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:9.1.1")
        }
    }
}

// HeliBoard's root puts Kotlin 2.4.0 on the buildscript classpath; do the same here so AGP's
// built-in Kotlin compiles with the same version.
gradle.beforeProject {
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0") }
    }
    repositories { google(); mavenCentral() }
}

rootProject.name = "voice"
