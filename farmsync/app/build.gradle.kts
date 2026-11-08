plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.farmsync.companion"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.farmsync.companion"
        minSdk = 33
        targetSdk = 36
        // Bump on every service change: Shizuku restarts the UserService when this differs.
        versionCode = 20
        versionName = "1.0.0"
    }

    // One fixed household key for all builds. Its SHA-1 is registered for the Google Drive
    // login, and keeping it means updates never wipe the app's snapshots.
    // The key lives outside the project: ~/.farmsync/farmsync-household.keystore
    signingConfigs {
        create("household") {
            storeFile = file(System.getProperty("user.home") + "/.farmsync/farmsync-household.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("household")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("household")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.org.json)
}
