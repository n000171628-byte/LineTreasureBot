plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.linebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.linebot"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.5.2"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 第二步辨識圖案時使用
    implementation("org.opencv:opencv:4.9.0")
    testImplementation("junit:junit:4.13.2")
}
