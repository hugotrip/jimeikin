import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wanderwildwood.jimeikin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wanderwildwood.jimeikin"
        minSdk = 28
        targetSdk = 36
        versionCode = 28
        versionName = "2.8.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // The checked-in debug.keystore is the standard Android debug key, password "android" -
    // its private half is in this public repo, so anyone can build an APK that upgrades an
    // install in place. A real keystore in signing/ replaces it for every build type when one
    // is present; the release workflow writes it there from repository secrets.
    val signingPropertiesFile = rootProject.file("signing/signing.properties")
    val realSigningConfig = if (signingPropertiesFile.isFile) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file("signing/signing.keystore")
            storePassword = signingProperties.getProperty("STORE_PASSWORD")
            keyAlias = signingProperties.getProperty("KEY_ALIAS")
            keyPassword = signingProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        null
    }

    // Kept as the fallback so a fresh clone with no keystore still builds, and so a downloaded
    // release APK can upgrade a debug install in place on machines that have neither.
    signingConfigs {
        getByName("debug") {
            storeFile = layout.projectDirectory.file("debug.keystore").asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Both build types take the same key so a downloaded release APK still upgrades a local
        // debug install in place - it is just the real key now rather than the public one.
        debug {
            signingConfig = realSigningConfig ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = realSigningConfig ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.4")

    // Mudita Mindful Design Framework
    // 1.0.2, not 1.0.0. The scrollbar's thumb offset is (trackHeight - thumbHeight), handed
    // to coerceIn as its maximum. In 1.0.0 that subtraction has no floor: when the track
    // measures less than the 16px minimum thumb - which it does while the list is still
    // being laid out - the maximum goes negative and coerceIn throws
    // "maximum -16.0 is less than minimum 0.0" from inside draw, on the main thread, and
    // takes the app down. Confirmed by disassembly rather than by reading the source: at the
    // same offset 1.0.0 is `fsub; fstore` and 1.0.2 is `fsub; fconst_0; coerceAtLeast;
    // fstore`. 1.0.2 also guards the denominator against zero.
    implementation("com.mudita:MMD:1.0.2")

    // Room database for songs
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // SAF helper for working with picked folders
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Media3 (ExoPlayer) for local file playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "com.google.protobuf")
    }

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // meta tag library
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
}
