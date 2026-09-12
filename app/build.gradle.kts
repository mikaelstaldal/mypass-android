plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "nu.staldal.pw"
    compileSdk = 36

    defaultConfig {
        applicationId = "nu.staldal.pw"
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
