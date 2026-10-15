plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.phantom.spectre"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.phantom.spectre"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // Release signing: the keystore is restored from GitHub secrets by the workflow (see .github/workflows/build.yml).
    // Without it (a fork, a local build) the APK is signed with the debug key so the build still succeeds.
    val releaseKeystore = file("release.keystore")
    signingConfigs {
        create("spectre") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "nethex"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("spectre") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
