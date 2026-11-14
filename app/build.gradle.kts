plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numeroVersao = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ao.multibrico.jarvisauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "ao.multibrico.jarvisauto"
        minSdk = 26
        targetSdk = 35
        versionCode = numeroVersao
        versionName = "1.0.$numeroVersao"
    }

    signingConfigs {
        create("ja") {
            storeFile = file("ja.jks")
            storePassword = "ja2026pedro"
            keyAlias = "ja"
            keyPassword = "ja2026pedro"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ja")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
