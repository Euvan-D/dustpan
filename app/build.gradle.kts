import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is optional. Put a keystore.properties file in the project root
// (it's git-ignored) to sign release builds with your own key. Without it, release
// builds fall back to the debug key so they still install on an emulator.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val identityUri = providers.gradleProperty("dustpan.identityUri")
    .getOrElse("https://github.com/Euvan-D/dustpan")

android {
    namespace = "app.dustpan"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dustpan"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "IDENTITY_URI", "\"$identityUri\"")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.all { test ->
            // Opt-in end-to-end test against a running validator, see README.
            System.getenv("DUSTPAN_E2E_RPC")?.let { test.environment("DUSTPAN_E2E_RPC", it) }
            test.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.solana.mobile.wallet.adapter) {
        // The MWA artifact lists androidx.test as a runtime dependency, but none of its code uses it.
        // Excluding it keeps test libraries (and the REORDER_TASKS permission) out of the APK.
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.ext")
        exclude(group = "junit")
        exclude(group = "org.hamcrest")
    }

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
