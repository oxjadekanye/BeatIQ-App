import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

private fun String.escapeForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private val keystorePropertiesFile = rootProject.file("keystore.properties")
private val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

/** Resolves storeFile from keystore.properties (absolute or project-relative). */
private fun resolveKeystoreFile(path: String): File {
    val trimmed = path.trim()
    return if (trimmed.startsWith("/")) {
        file(trimmed)
    } else {
        rootProject.file(trimmed)
    }
}

private fun configuredReleaseKeystore(): File? {
    if (!keystorePropertiesFile.exists()) return null
    val path = keystoreProperties.getProperty("storeFile")?.trim().orEmpty()
    if (path.isEmpty()) return null
    val ks = resolveKeystoreFile(path)
    return ks.takeIf { it.isFile }
}

android {
    namespace = "com.beatiq.music"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.beatiq.music"
        minSdk = 26
        targetSdk = 36
        // Increment versionCode for every Play upload (must strictly increase).
        versionCode = 8
        versionName = "1.0.7"

        val productionApi = "https://beatiq.onrender.com/api/v1/"
        buildConfigField("String", "API_BASE_URL", "\"${productionApi.escapeForBuildConfig()}\"")

        // Public service origins only; credentials remain server-side. Empty keeps each feature hidden.
        val musicMeAudioUrl = providers.gradleProperty("musicme.audio.url")
            .orElse(providers.environmentVariable("MUSICME_AUDIO_URL")).getOrElse("").trim()
        val musicMeSongwriterUrl = providers.gradleProperty("musicme.songwriter.url")
            .orElse(providers.environmentVariable("MUSICME_SONGWRITER_URL")).getOrElse("").trim()
        buildConfigField("String", "MUSICME_AUDIO_URL", "\"${musicMeAudioUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "MUSICME_SONGWRITER_URL", "\"${musicMeSongwriterUrl.escapeForBuildConfig()}\"")

        val auddToken =
            run {
                val f = rootProject.file("local.properties")
                if (!f.exists()) return@run ""
                val p = Properties()
                f.inputStream().use { p.load(it) }
                p.getProperty("audd.api.token", "")
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
            }
        buildConfigField("String", "AUDD_API_TOKEN", "\"$auddToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ks = configuredReleaseKeystore()
            if (ks != null) {
                storeFile = ks
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            val f = rootProject.file("local.properties")
            if (f.exists()) {
                val p = Properties()
                f.inputStream().use { p.load(it) }
                val url = p.getProperty("beatiq.api.base.url", "")?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    val normalized = if (url.endsWith("/")) url else "$url/"
                    buildConfigField(
                        "String",
                        "API_BASE_URL",
                        "\"${normalized.escapeForBuildConfig()}\"",
                    )
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig =
                if (configuredReleaseKeystore() != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
