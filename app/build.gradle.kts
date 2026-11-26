plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.5"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    signingConfigs {
        val ksPath = System.getenv("ANNI_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("anniRelease") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANNI_KEYSTORE_PASS") ?: "anni123456"
                keyAlias = System.getenv("ANNI_KEY_ALIAS") ?: "anni"
                keyPassword = System.getenv("ANNI_KEY_PASS") ?: "anni123456"
            }
        }
    }

    buildTypes {
        debug {
            val customSign = signingConfigs.findByName("anniRelease")
            if (customSign != null) {
                signingConfig = customSign
            }
        }
        release {
            isMinifyEnabled = false
            val customSign = signingConfigs.findByName("anniRelease")
            if (customSign != null) {
                signingConfig = customSign
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
