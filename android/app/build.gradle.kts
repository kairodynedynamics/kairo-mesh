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
        versionCode = 2
        versionName = "0.2.0"
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
            buildConfigField("String", "DEMO_KURUM", "\"l4vew+gipwYt/slp5jbANEAJUMUlh8kIaJrqtm4qoTI=;2Yf1lkYhSXUsE63q0Y2QludMtFtN5/5kvEeHDnPYXho=;https://demo.kairodynedynamics.com;Deneme Kurumu - test;0J+695S+ON5pK6u3HEdWqCqYQ6yssnwJDuW3VY6HW2iQlG4zMOFJEC39+ntGOw4rf51trwv0mVq4DWDg6BSICw==\"")
        }
    }
    buildFeatures { buildConfig = true }
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

// TriyajTest reads the exported model at run time: a new export must re-run the tests
tasks.withType<Test>().configureEach { inputs.dir("../../triyaj/telefon") }

// generated Java sources (BuildConfig) are UTF-8; the system code page here is not
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
