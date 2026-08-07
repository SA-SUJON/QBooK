import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Signing configuration.
//
// The certificate must never change: Android refuses to install an update
// signed by a different key, so every existing install would be stranded.
//
// The keystore is not in the repository. It is supplied either by a local
// keystore.properties (ignored by git) or, on CI, by environment variables
// populated from repository secrets.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val keystorePath = signingValue("storeFile", "KEYSTORE_FILE") ?: "dustbook-release.jks"
val keystoreFile = rootProject.file(keystorePath)
val hasSigningKey = keystoreFile.exists() &&
    signingValue("storePassword", "KEYSTORE_PASSWORD") != null

android {
    namespace = "com.dustbook.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dustbook.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 123
        versionName = "5.2.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
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
            // Without the keystore the build still succeeds; the APK is then
            // unsigned and cannot be published, which is the correct outcome
            // for a fork or a contributor who has no key.
            if (hasSigningKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
            // Same key and same applicationId as release, so a debug build can
            // be replaced by a release build without uninstalling.
            if (hasSigningKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
