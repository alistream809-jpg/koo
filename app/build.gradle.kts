plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedKeystore = file("debug.keystore")

android {
    namespace = "com.iosmotion.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iosmotion.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("shared") {
            if (sharedKeystore.exists()) {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (sharedKeystore.exists()) signingConfigs.getByName("shared") else signingConfigs.getByName("debug")
        }
        debug {
            if (sharedKeystore.exists()) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
