import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val signProps = Properties().apply {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        localProperties.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.github.maxto3.pixelims"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.maxto3.pixelims"
        minSdk = 34
        targetSdk = 36
        versionCode = 20261122
        versionName = "20261122"
        setProperty("archivesBaseName", "PixelCarrierSettings")
    }

    signingConfigs {
        create("release") {
            val storePath = signProps.getProperty("SIGN_KEY_STORE_FILE")
                ?: System.getenv("SIGN_KEY_STORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = signProps.getProperty("SIGN_KEY_STORE_PASSWORD")
                    ?: System.getenv("SIGN_KEY_STORE_PASSWORD")
                keyAlias = signProps.getProperty("SIGN_KEY_ALIAS")
                    ?: System.getenv("SIGN_KEY_ALIAS")
                keyPassword = signProps.getProperty("SIGN_KEY_PASSWORD")
                    ?: System.getenv("SIGN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
