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
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("spectre") {
            storeFile = file("spectre.keystore")
            storePassword = "spectre123"
            keyAlias = "spectre"
            keyPassword = "spectre123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("spectre")
        }
        debug {
            signingConfig = signingConfigs.getByName("spectre")
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
