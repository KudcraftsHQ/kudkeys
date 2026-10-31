import com.android.build.api.variant.ApplicationVariant

// Kud Keys: release signing and version come from the environment (CI sets them from the tag and the
// KC_* secrets): KC_KEYSTORE_FILE / KC_KEYSTORE_PASSWORD / KC_KEY_ALIAS / KC_KEY_PASSWORD,
// KC_VERSION_NAME / KC_VERSION_CODE. Without KC_KEYSTORE_FILE the release build stays unsigned.
val keystoreFile: String? = System.getenv("KC_KEYSTORE_FILE")

plugins {
    id("com.android.application")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
}

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kudcrafts.kudkeys"
        minSdk = 21
        targetSdk = 37
        versionCode = (System.getenv("KC_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("KC_VERSION_NAME") ?: "0.0.0-dev"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    signingConfigs {
        create("kudcrafts") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storeType = "pkcs12"
                storePassword = System.getenv("KC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KC_KEY_ALIAS")
                keyPassword = System.getenv("KC_KEY_PASSWORD") ?: System.getenv("KC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("kudcrafts")
        }
        debug {
            // no minify: faster builds, and unit tests run against this variant
            isMinifyEnabled = false
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // Kud Keys voice client (android/voice)
    implementation(project(":voice"))

    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Kud Keys sync and backup (v0.4); 2.10 is the last WorkManager on minSdk 21
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01")) // newer requires minSdk 23
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors, newer requires minSdk 23

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.work:work-testing:2.10.5")
}

// Kud Keys: the native dictionary code built for the host, so JVM tests can load real dictionaries
// (KudSyncedDictionaryTest). Needs g++ and a JDK with JNI headers; without g++ those tests are skipped.
val hostJniDir = layout.buildDirectory.dir("hostjni")
val hostJni = tasks.register<Exec>("hostJni") {
    val jni = file("src/main/jni")
    val javaHome = File(System.getProperty("java.home"))
    val out = hostJniDir.get().file("libjni_latinime.so").asFile
    inputs.dir(jni)
    outputs.file(out)
    onlyIf { System.getProperty("os.name").lowercase().contains("linux") && File("/usr/bin/g++").canExecute() }
    doFirst { out.parentFile.mkdirs() }
    workingDir = jni
    val sources = jni.walkTopDown().filter { it.isFile && it.extension == "cpp" && "/tests/" !in it.path }
        .map { it.relativeTo(jni).path }.sorted().toList()
    commandLine(listOf("g++", "-shared", "-fPIC", "-O1", "-std=c++17", "-w", "-Isrc",
        "-I${javaHome}/include", "-I${javaHome}/include/linux", "-o", out.absolutePath) + sources)
}
tasks.withType<Test>().configureEach {
    dependsOn(hostJni)
    systemProperty("kudkeys.hostJni", hostJniDir.get().file("libjni_latinime.so").asFile.absolutePath)
}
