plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kairodyne.mesh"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kairodyne.mesh"
        minSdk = 33 // X25519 (XDH) in the platform crypto provider
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // on-device triage model (triyaj/model.py agirlik): weights and labels, about 3.4 MB
    sourceSets["main"].assets.srcDirs("../../triyaj/telefon")
    androidResources { noCompress += "bin" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
}
