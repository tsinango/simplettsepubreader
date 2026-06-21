import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun signingValue(name: String): String? =
    (localProperties.getProperty(name) ?: providers.environmentVariable(name).orNull)
        ?.takeIf { it.isNotBlank() }

val fixedDebugStoreFile = signingValue("TTS_READER_KEYSTORE")?.let { rootProject.file(it) }
val hasFixedDebugSigning = fixedDebugStoreFile?.isFile == true &&
    signingValue("TTS_READER_KEYSTORE_PASSWORD")?.isNotBlank() == true &&
    signingValue("TTS_READER_KEY_ALIAS")?.isNotBlank() == true &&
    signingValue("TTS_READER_KEY_PASSWORD")?.isNotBlank() == true

android {
    namespace = "com.example.epubreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.epubreader"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasFixedDebugSigning) {
            create("fixedDebug") {
                storeFile = fixedDebugStoreFile
                storePassword = signingValue("TTS_READER_KEYSTORE_PASSWORD")
                keyAlias = signingValue("TTS_READER_KEY_ALIAS")
                keyPassword = signingValue("TTS_READER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (hasFixedDebugSigning) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
        release {
            if (hasFixedDebugSigning) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
