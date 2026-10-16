plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ethan.localagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ethan.localagent"
        // 31: dynamic colour, on-device SpeechRecognizer, and wireless debugging (11+) everywhere.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The built-in Linux shell (PRoot) and the NPU runtime are arm64-only, and so is every
        // phone this is meant for. x86_64 is kept so the UI still runs on the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Extracted (not mmap'd from the APK) so PRoot can be exec'd from nativeLibraryDir and the
        // Hexagon DSP can load its skel library from a real path.
        jniLibs.useLegacyPackaging = true
        // qnn-runtime ships every Hexagon generation (~220 MB). Keep only the HTP backend for
        // V79 (Snapdragon 8 Elite) and V81 (8 Elite Gen 5).
        jniLibs.excludes += listOf(
            "**/libQnnDsp*.so",
            "**/libQnnGpu.so",
            "**/libQnnHtpPrepare.so",
            "**/libQnnHtpV68*.so",
            "**/libQnnHtpV69*.so",
            "**/libQnnHtpV73*.so",
            "**/libQnnHtpV75*.so",
        )
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "com.google.ai.edge.litertlm.ExperimentalApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    implementation(libs.litertlm)
    implementation(libs.litert.npu.qualcomm)
    implementation(libs.qnn.runtime)

    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
}
