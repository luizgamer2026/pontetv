plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.pontetv.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.pontetv.tv"
        minSdk = 28 // BluetoothHidDevice (TV como teclado/mouse) exige Android 9+
        targetSdk = 34
        versionCode = 13
        versionName = "2.10"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
