plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sakura.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakura.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("sakura.keystore")
            storePassword = "sakura123"
            keyAlias = "sakura"
            keyPassword = "sakura123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
}

dependencies {
    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // HTML parsing
    implementation("org.jsoup:jsoup:1.17.2")
    // Video player
    implementation("androidx.media3:media3-exoplayer:1.3.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.0")
    implementation("androidx.media3:media3-ui:1.3.0")
    // Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    // DocumentFile for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
    // GSYVideoPlayer
    implementation("io.github.carguo:gsyvideoplayer:11.1.0")
    implementation("io.github.carguo:gsyvideoplayer-exo2:11.1.0")
}
