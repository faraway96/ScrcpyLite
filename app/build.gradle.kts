plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.faraway96.scrcpylite"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.faraway96.scrcpylite"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val storeFile = System.getenv("RELEASE_STORE_FILE")
            if (!storeFile.isNullOrBlank()) {
                this.storeFile = file(storeFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val storeFile = System.getenv("RELEASE_STORE_FILE")
            signingConfig = if (storeFile.isNullOrBlank())
                signingConfigs.getByName("debug") else signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "META-INF/*"
    }
}

// 零第三方依赖: 只有 Kotlin 标准库
dependencies {
}
