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
        versionCode = 5
        versionName = "0.4.1"
        // Root keys that may approve authority codes (src/kurum-cli.js kok/onayla). Release builds trust only the
        // production root, whose private half is kept offline; debug builds also trust the public test root
        // (test/kok-test) so tests and emulators can mint codes.
        buildConfigField("String", "KOKLER", "\"KMrne+ak0Iqr3rLzZ9uI7Qic8fWv6o/Na2H15FF25So=\"")
        // release: authority servers only over https; debug: also http, for the emulator test against 10.0.2.2
        manifestPlaceholders["cleartext"] = "false"
        buildConfigField("String", "DEMO_KURUM", "\"\"")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "KOKLER", "\"KMrne+ak0Iqr3rLzZ9uI7Qic8fWv6o/Na2H15FF25So=,mCZWxnPAXq0pTVoc5wwzaPbHgJvMARPQX626+hmR+zs=\"")
            manifestPlaceholders["cleartext"] = "true"
            // demo authority (test root, no real server): calls are queued and carried, never delivered
            buildConfigField("String", "DEMO_KURUM", "\"mdUshEx3g4fN93giVHNo85Sj514ZAnJmwzN2mBU/bTU=;tIp0lLyJX6b7Co+dC7Ipt5BjJBNZXUzbnk9UVuDqQy4=;https://kairodynedynamics.com/operator;KairoMesh Operatör Demo - test;W5eevjIWZV2sLHFwyYrsWO9EjfyGC6fZYSf/Zm94HxcKPMr/1DnYSCW4QQpiTL/hm/fHl6EhtlcIXO6VMlWCBA==\"")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // on-device triage: the character model (triyaj/model.py agirlik, 3.4 MB) and the Turkish encoder
    // (triyaj/kodlayici.py telefon, 23.5 MB); stored uncompressed so they load without inflating
    sourceSets["main"].assets.srcDirs("../../triyaj/telefon")
    androidResources { noCompress += "bin" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
}

// TriyajTest reads the exported model at run time: a new export must re-run the tests
tasks.withType<Test>().configureEach { inputs.dir("../../triyaj/telefon") }

// generated Java sources (BuildConfig) are UTF-8; the system code page here is not
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
