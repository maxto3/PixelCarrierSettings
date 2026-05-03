import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.github.maxto3.pixelims"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.maxto3.pixelims"
        minSdk = 34
        targetSdk = 36
        versionCode = 20260504
        versionName = "20260504"
        setProperty("archivesBaseName", "PixelCarrierSettings")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.hiddenapibypass)
}
