import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// ── Keystore config (never committed to git) ──────────────────────────────
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.simhadri.winentry"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.simhadri.winentry"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.4.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("Boolean", "FORCE_UPDATE", "true")
    }

    signingConfigs {
        create("release") {
            storeFile     = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias      = keystoreProps["keyAlias"] as String
            keyPassword   = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ── R8 / ProGuard ─────────────────────────────────────────────
            // Enables code shrinking, obfuscation and resource shrinking.
            // Expected saving: 3-5 MB by removing unused SDK code.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Keep debug builds fast — no minification
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // ── ABI Splits ────────────────────────────────────────────────────────
    // Generates one APK per CPU architecture instead of a fat universal APK.
    // Expected saving: 2-4 MB per APK (arm64-v8a covers ~95% of modern devices).
    // For direct APK installs (sideloading to testers), use the arm64-v8a build.
    // For Play Store, upload the AAB (bundleRelease) — Google serves the right ABI per device.
    // Must be disabled when building an .aab: AGP 9.0.1 fails buildReleasePreBundle with
    // "Multiple shrunk-resources files found" if abi splits are enabled during bundling.
    // https://issuetracker.google.com/402800800
    splits {
        abi {
            isEnable = !gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // ── Core Android ──────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ── Room Database ─────────────────────────────────────────────────────
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── Lifecycle ─────────────────────────────────────────────────────────
    val lifecycleVersion = "2.7.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")

    // ── Navigation ────────────────────────────────────────────────────────
    val navVersion = "2.7.6"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    // ── Coroutines ────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Enables task.await() for Firebase and Google API Tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // ── WorkManager ───────────────────────────────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // ── Apache POI (Excel import) ─────────────────────────────────────────
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // ── Google Sign-In (needed for Firebase Auth) ──────────────────────────
    // 20.7.0 is the last version that supports the legacy GoogleSignIn /
    // GoogleSignInClient intent-based API used throughout this app.
    // 21.x requires migration to the Credential Manager API.
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // ── Play In-App Update ────────────────────────────────────────────────
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // ── Firebase BOM + SDKs ───────────────────────────────────────────────
    // BOM manages all Firebase versions — do not add version numbers below
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    // ── Testing ───────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
}
