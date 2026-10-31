plugins {
    id("com.android.library")
}

android {
    namespace = "com.kudcrafts.kudkeys.voice"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // Opt-in live run against production (see VoiceLiveTest).
                it.environment("KUDKEYS_LIVE", System.getenv("KUDKEYS_LIVE") ?: "")
                // Opt-in run against a local server (see AccountLocalTest).
                for (k in listOf("KUDKEYS_LOCAL_BASE", "KUDKEYS_LOCAL_EMAIL", "KUDKEYS_LOCAL_PASSWORD", "KUDKEYS_LOCAL_WAV"))
                    it.environment(k, System.getenv(k) ?: "")
                it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
            }
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
