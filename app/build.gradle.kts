plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Permanent signing key, provided by the build server (GitHub secrets). Without it, builds use a
// temporary debug key, and Android won't install them over a copy signed with a different key.
val horizonKeystore = System.getenv("HORIZON_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.horizon.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.horizon.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = 14
        versionName = "0.14"
    }

    signingConfigs {
        if (horizonKeystore != null) {
            create("horizon") {
                storeFile = horizonKeystore
                storePassword = System.getenv("HORIZON_KEY_PASSWORD")
                keyAlias = "horizon"
                keyPassword = System.getenv("HORIZON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("horizon")?.let { signingConfig = it }
        }
        // The build you install: optimized by R8 (smaller, faster), with a startup profile so
        // Android pre-compiles the hot paths. Signed with the permanent key when it's available.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("horizon") ?: signingConfigs.getByName("debug")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Installs the Compose libraries' built-in startup profiles: faster launch and smoother scrolling.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
