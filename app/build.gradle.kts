plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.nestbox"
    compileSdk = 35

    defaultConfig {
        // 8 characters on purpose: Classic mode rewrites Twoyi's built-in "/data/data/io.twoyi" paths
        // to "/data/data/app.nbox", which must be exactly the same length.
        applicationId = "app.nbox"
        minSdk = 29
        // 28 like Twoyi: Classic mode runs the guest's ./init from app storage, which Android blocks at 29+.
        targetSdk = 28
        versionCode = 6
        versionName = "0.4.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // Engine programs (libvine_helper.so, libloader.so…) are executed, so they must be real files on disk.
        jniLibs { useLegacyPackaging = true }
    }
    lint {
        // targetSdk 28 is deliberate (see defaultConfig).
        disable += "ExpiredTargetSdkVersion"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    // In-app wireless debugging pairing (Xiaomi setup › Background process limit)
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    implementation(libs.conscrypt.android)
    // Classic mode: unpack Twoyi's rootfs.7z
    implementation(libs.commons.compress)
    implementation(libs.xz)
}
