import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val giphySdkKey = Properties().run {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    getProperty("GIPHY_SDK_KEY", "").also {
        require(it.matches(Regex("[A-Za-z0-9_-]*"))) { "Invalid GIPHY_SDK_KEY" }
    }
}

android {
    namespace = "com.kingzcheung.xime"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rime.icekeyboard"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "GIPHY_SDK_KEY", "\"$giphySdkKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with this machine's debug key so release APKs install as updates of each other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The Kotlin API only loads libsherpa-onnx-jni.so (which needs libonnxruntime.so); the C/C++ APIs are unused.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        // Compress native libraries: onnxruntime would otherwise add ~50 MB uncompressed to the download.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("com.giphy.sdk:ui:2.5.3")
    implementation("androidx.core:core:1.17.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
}
