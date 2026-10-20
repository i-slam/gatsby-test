plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ramcleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ramcleaner"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // QUERY_ALL_PACKAGES / DUMP / usage-stats are intentional for a sideloaded tool.
        abortOnError = false
        checkReleaseBuilds = false
    }
}
