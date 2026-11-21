import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keyDir = System.getProperty("user.home") + "/.farmsync"
val releaseProps = Properties().also { p ->
    val f = File("$keyDir/release-signing.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
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
        versionCode = 22
        versionName = "1.0.2"
    }

    // Signing keys live outside the project, in ~/.farmsync/:
    // - "household": the original key of 1.0.0 (a debug-style key), still used for debug builds.
    // - "release": the release key since 1.0.1. Its password is only in
    //   ~/.farmsync/release-signing.properties (never in this repo). Google Play can't use a
    //   debug-style key, so 1.0.1 hands the app over from the household key to this one
    //   (APK key rotation, see scripts/sign-release.sh); installed copies update normally.
    signingConfigs {
        create("household") {
            storeFile = file("$keyDir/farmsync-household.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseProps.isNotEmpty()) create("release") {
            storeFile = file(releaseProps.getProperty("storeFile"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("household")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
