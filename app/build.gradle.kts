import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.xcprinter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xcprinter.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Hotwire Native
    implementation("dev.hotwire:core:1.3.1")
    implementation("dev.hotwire:navigation-fragments:1.3.1")

    // Material
    implementation("com.google.android.material:material:1.12.0")

    // AndroidX
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // 扫码
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
