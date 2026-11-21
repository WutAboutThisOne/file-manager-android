plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// Release signing. Secrets never live in the repo:
//  1. Preferred: a git-ignored `keystore.properties` in the project root (copy keystore.properties.example).
//  2. Fallback: `keystore/folio-files.jks` + `keystore/keystore.password` (both git-ignored; the original
//     key name is kept so existing installs keep updating).
// With neither present, assembleRelease produces an unsigned APK.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val legacyKeystore = rootProject.file("keystore/folio-files.jks")
val legacyPasswordFile = rootProject.file("keystore/keystore.password")
val releaseStoreFile: File? = signingProps.getProperty("storeFile")?.let { rootProject.file(it) }
    ?: legacyKeystore.takeIf { it.exists() }
val legacyPassword: String = if (legacyPasswordFile.exists()) legacyPasswordFile.readText().trim() else ""
val releaseStorePassword: String = signingProps.getProperty("storePassword") ?: legacyPassword
val releaseKeyAlias: String = signingProps.getProperty("keyAlias") ?: "folio-files"
val releaseKeyPassword: String = signingProps.getProperty("keyPassword") ?: releaseStorePassword

android {
    namespace = "local.files.explorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "local.files.explorer"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile?.exists() == true && releaseStorePassword.isNotEmpty()) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.systemProperty("screens.outDir", rootProject.file("dist/screens").absolutePath)
            it.systemProperty("roborazzi.test.record", "true")
            it.maxHeapSize = "2g"
            // Fresh JVM per test class: Robolectric sandboxes are shared across classes, and looper-clock
            // advances in the activity tests otherwise leak into later Compose screenshot tests.
            it.forkEvery = 1
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") // Robolectric ParcelFileDescriptor on JDK 17+
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 0.4.5: fold-aware layouts (window size classes + hinge/posture from Jetpack WindowManager).
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val libsu = "6.0.0"
    // Archives: zip (incl. AES) via zip4j; 7z / tar / gz / xz / bz2 via Commons Compress + XZ for Java.
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.topjohnwu.libsu:core:$libsu")
    implementation("com.github.topjohnwu.libsu:io:$libsu")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.20.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.20.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
