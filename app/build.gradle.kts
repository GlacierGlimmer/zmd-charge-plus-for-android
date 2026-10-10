import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing.
 *
 * The keystore and its credentials are deliberately kept OUT of this repository. The build looks
 * for them in this order:
 *   1. the file named by the `ECP_KEYSTORE_PROPERTIES` environment variable (absolute path), or
 *   2. `keystore.properties` in the repository root (git-ignored).
 *
 * The properties file must define `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
 * When neither exists the release build still succeeds, but it produces an unsigned APK, and the
 * build prints a warning so an unsigned artifact can never be published by accident.
 */
val keystorePropertiesFile: File? = run {
    val fromEnvironment = System.getenv("ECP_KEYSTORE_PROPERTIES")
    when {
        !fromEnvironment.isNullOrBlank() -> File(fromEnvironment)
        rootProject.file("keystore.properties").exists() -> rootProject.file("keystore.properties")
        else -> null
    }
}

val keystoreProperties: Properties? = keystorePropertiesFile
    ?.takeIf { it.isFile }
    ?.let { file ->
        Properties().apply { FileInputStream(file).use { load(it) } }
    }

android {
    namespace = "com.glacierglimmer.endfieldchargeplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glacierglimmer.endfieldchargeplus"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "v0.1.1"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // minSdk 26 means every supported device understands APK Signature Scheme v2;
                // v1 (JAR signing) adds nothing here and only bloats the APK. v3 adds key rotation
                // support. Verified after every release build with `apksigner verify`.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    androidResources {
        // The product ships Simplified Chinese and English only (Chinese locales fall back to zh).
        localeFilters += listOf("en", "zh")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // User-controlled product version: Debug and repeated test builds keep the same name.
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "ECP: no release keystore configured (set ECP_KEYSTORE_PROPERTIES or add " +
                        "keystore.properties); :app:assembleRelease will produce an UNSIGNED apk.",
                )
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "OldTargetApi", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
