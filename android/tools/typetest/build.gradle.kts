// Research-only: a plain text field for the SwiftKey comparison (.github/workflows/swiftkey-compare.yaml).
plugins { id("com.android.application") }

android {
    namespace = "com.kudcrafts.typetest"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.kudcrafts.typetest"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
}
