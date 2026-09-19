// Not java.util.Properties inline below: in the Kotlin DSL `java` is the Java plugin's extension,
// so a fully-qualified reference to the package does not resolve.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Signing key shared by the nu.staldal.* apps. Enabling guarding services by a signature-level permission,
// so the two apps must be signed with the same key for it to be granted. The default
// ~/.android/debug.keystore would satisfy that, but it is a poor trust anchor: world-readable,
// fixed password "android", and shared by every debug APK built on the machine.
//
// Configure it in local.properties (kept out of version control), or through the matching
// environment variables for CI:
//
//     debugKeystore=/path/to/staldal-apps.keystore   DEBUG_KEYSTORE
//     debugKeystorePassword=…                        DEBUG_KEYSTORE_PASSWORD
//     debugKeyAlias=staldal-apps                     DEBUG_KEY_ALIAS
//     debugKeyPassword=…                             DEBUG_KEY_PASSWORD
//
// Absent or incomplete, the build still works but falls back to the default debug key and says so;
// the integration keeps working (both apps fall back alike) — it is the trust boundary that weakens,
// which must not happen quietly.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(key: String, env: String): String? =
    (localProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "nu.staldal.mypass"
    compileSdk = 36

    signingConfigs {
        // Overrides the built-in debug config, which debug and androidTest builds already use.
        getByName("debug") {
            val store = signingProperty("debugKeystore", "DEBUG_KEYSTORE")?.let(::file)
            val storePw = signingProperty("debugKeystorePassword", "DEBUG_KEYSTORE_PASSWORD")
            val alias = signingProperty("debugKeyAlias", "DEBUG_KEY_ALIAS")
            val keyPw = signingProperty("debugKeyPassword", "DEBUG_KEY_PASSWORD")
            if (store?.exists() == true && storePw != null && alias != null && keyPw != null) {
                storeFile = store
                storeType = "PKCS12"
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
            } else {
                logger.warn(
                    "mypass: no shared debug signing key configured (see app/build.gradle.kts); " +
                        "falling back to the default debug keystore."
                )
            }
        }
    }

    defaultConfig {
        applicationId = "nu.staldal.mypass"
        // API 28 is the first release where AssistStructure.ViewNode exposes
        // getWebScheme(). Without it the autofill service cannot tell an
        // https: page from an http: one, and the browser integration's
        // eligibility rule (see Matching.eligibleWebHost) could not be
        // enforced — so this app does not run below it.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            // ICU4J is here for one thing — UTS #46 hostname normalization —
            // and ships 38 MiB of data for everything else it can do:
            // collation tables, break-iterator dictionaries for Khmer and
            // Burmese, transliteration, currency, time zones, and 944 locale
            // resource bundles. None of it is reachable from IDNA, and R8
            // cannot tell, because these are Java resources rather than
            // classes. Dropping them takes the release APK from 16.2 MiB to
            // 4.8 MiB, which makes UTS #46 cost about 180 KiB rather than
            // 11 MiB.
            //
            // The seven data files UTS #46 does consult are kept by naming
            // what to remove rather than what to keep, so a file a future ICU
            // adds survives by default instead of silently vanishing.
            //
            // Verified against icu4j 76.1 with tools/verify-icu-data.sh, which
            // converts every assigned code point (288 735 of them) through
            // both the full and the trimmed data and requires identical
            // output, errors included. **Re-run it when bumping ICU.**
            excludes += setOf(
                "com/ibm/icu/impl/data/icudata/*/**",
                "com/ibm/icu/impl/data/icudata/*.res",
                "com/ibm/icu/impl/data/icudata/*.spp",
                "com/ibm/icu/impl/data/icudata/confusables.cfu",
                "com/ibm/icu/impl/data/icudata/nfkc_cf.nrm",
                "com/ibm/icu/impl/data/icudata/nfkc_scf.nrm",
                "com/ibm/icu/impl/data/icudata/uemoji.icu",
                "com/ibm/icu/impl/data/icudata/ulayout.icu",
                "com/ibm/icu/impl/data/icudata/unames.icu",
            )
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Biometric 1.1.0 otherwise pulls Fragment 1.2.5, whose legacy
    // startActivityForResult override rejects ActivityResultRegistry request
    // codes above 16 bits and crashes document pickers.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // Inline (keyboard-strip) autofill suggestions, API 30+.
    implementation(libs.androidx.autofill)
    implementation(libs.kotlinx.serialization.json)
    // scrypt KDF. Only org.bouncycastle.crypto.generators.SCrypt is used; the
    // AES-CTR and HMAC-SHA256 halves of the file format come from the
    // platform JCE. R8 strips the rest of the provider in release builds.
    implementation(libs.bouncycastle.provider)
    // Public Suffix List, via InternetDomainName.topPrivateDomain(). Bounds
    // how far a parent-domain match may climb — see Matching.
    implementation(libs.guava)
    implementation(libs.icu4j)
    // Read-only KeePass KDBX 3.x/4.x import.
    implementation(libs.kotpass)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
