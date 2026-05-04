plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sonar.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sonar.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Prevent ONNX model from being compressed inside APK
    androidResources {
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // ONNX Runtime — Android AAR for the app
    implementation(libs.onnxruntime.android)

    // ONNX Runtime — JVM JAR for local unit tests (replaces Android AAR in test scope)
    testImplementation(libs.onnxruntime.jvm)
    configurations.matching { it.name.startsWith("test") }.configureEach {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    // DataStore
    implementation(libs.datastore)

    // Coroutines
    implementation(libs.coroutines.android)

    // Lifecycle (LifecycleService)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime)

    // Android KTX
    implementation(libs.core.ktx)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
