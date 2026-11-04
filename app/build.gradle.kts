plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clashtimers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clashtimers"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Local key (kept out of git) so every rebuild installs over the previous one.
    // Without it, builds fall back to the Android debug key.
    val sideloadKey = file("../keystore/clashtimers.jks")
    signingConfigs {
        if (sideloadKey.exists()) {
            create("sideload") {
                storeFile = sideloadKey
                storePassword = "clashtimers"
                keyAlias = "clashtimers"
                keyPassword = "clashtimers"
            }
        }
    }
    val signing = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
