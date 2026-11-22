import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: read from keystore.properties (local) or environment variables (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.glowled.app"
    compileSdk = 35

    // Only the languages Glow LED is translated into (libraries ship many more).
    androidResources {
        localeFilters += listOf("en", "pt-rBR", "es", "fr", "de", "it", "ru", "tr", "ar", "hi", "in", "ja", "zh-rCN")
    }

    defaultConfig {
        applicationId = "com.glowled.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 36
        versionName = "2.11.1"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "GLOWLED_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "GLOWLED_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GLOWLED_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GLOWLED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSign = signingConfigs.getByName("release")
            when {
                releaseSign.storeFile != null -> signingConfig = releaseSign
                // Pull requests from other forks see no signing secrets: build a clearly marked
                // test APK instead of failing the check. It is debug-signed and the CI publish
                // gate (SIGNED != 'release' in build.yml) never publishes it.
                System.getenv("GITHUB_EVENT_NAME") == "pull_request" -> {
                    signingConfig = signingConfigs.getByName("debug")
                    versionNameSuffix = "-test"
                    logger.warn("Glow LED: no release keystore on CI - building a TEST APK signed with the debug key; never publish it.")
                }
                // Everywhere else a release build without the release keystore fails loudly:
                // a debug-signed 'release' breaks in-app updates (the updater pins the release
                // certificate) and looks like a trustworthy build while it is not.
                else -> throw GradleException(
                    "Glow LED: no release keystore found (keystore.properties locally, or the " +
                        "GLOWLED_KEYSTORE env variables on CI). Refusing to build a release APK signed " +
                        "with the debug key. Configure release signing first, or build assembleDebug instead.",
                )
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
