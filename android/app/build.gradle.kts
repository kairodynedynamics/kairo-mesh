import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kairodyne.mesh"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kairodyne.mesh"
        minSdk = 33 // X25519 (XDH) in the platform crypto provider
        targetSdk = 36
        versionCode = 9
        versionName = "0.6.1"
        // test automation drives the screen through intent extras (MainActivity); never in a build that is handed out
        buildConfigField("boolean", "OTOMASYON", "false")
        // Root keys that may approve authority codes (src/kurum-cli.js kok/onayla). Release builds trust only the
        // production root, whose private half is kept offline; debug builds also trust the public test root
        // (test/kok-test) so tests and emulators can mint codes.
        buildConfigField("String", "KOKLER", "\"KMrne+ak0Iqr3rLzZ9uI7Qic8fWv6o/Na2H15FF25So=\"")
        // release: authority servers only over https; debug: also http, for the emulator test against 10.0.2.2
        manifestPlaceholders["cleartext"] = "false"
        buildConfigField("String", "DEMO_KURUM", "\"\"")
    }
    // the upload key lives outside the repository (internal/anahtarlar/android/imza.properties); without it the
    // release and demo builds come out unsigned, as in CI
    val imza = rootProject.file("../internal/anahtarlar/android/imza.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (imza != null) create("yukleme") {
            storeFile = file(imza.getProperty("storeFile")); storePassword = imza.getProperty("storePassword")
            keyAlias = imza.getProperty("keyAlias"); keyPassword = imza.getProperty("keyPassword")
        }
    }
    // the operator demo (kairodynedynamics.com/operator), approved by the public test root
    val demoKurum = "\"mdUshEx3g4fN93giVHNo85Sj514ZAnJmwzN2mBU/bTU=;tIp0lLyJX6b7Co+dC7Ipt5BjJBNZXUzbnk9UVuDqQy4=;https://kairodynedynamics.com/operator;KairoMesh Operatör Demo - test;W5eevjIWZV2sLHFwyYrsWO9EjfyGC6fZYSf/Zm94HxcKPMr/1DnYSCW4QQpiTL/hm/fHl6EhtlcIXO6VMlWCBA==\""
    val ikiKok = "\"KMrne+ak0Iqr3rLzZ9uI7Qic8fWv6o/Na2H15FF25So=,mCZWxnPAXq0pTVoc5wwzaPbHgJvMARPQX626+hmR+zs=\""
    buildTypes {
        getByName("release") { if (imza != null) signingConfig = signingConfigs.getByName("yukleme") }
        getByName("debug") {
            buildConfigField("String", "KOKLER", ikiKok)
            manifestPlaceholders["cleartext"] = "true"
            buildConfigField("String", "DEMO_KURUM", demoKurum)
            buildConfigField("boolean", "OTOMASYON", "true")
        }
        // the build handed out for demos: a release build (not debuggable, https only, no automation extras) whose
        // only authority is the built-in operator demo. It trusts the test root because that is what approved the
        // demo code; nothing from outside can set another code, since the app has no field and takes no extras.
        create("demo") {
            initWith(getByName("release"))
            buildConfigField("String", "KOKLER", ikiKok)
            buildConfigField("String", "DEMO_KURUM", demoKurum)
            matchingFallbacks += "release"
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
