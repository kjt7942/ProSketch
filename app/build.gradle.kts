import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Version: "-PappVersion=1.2.3" (CI passes the git tag) or appVersion in gradle.properties.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.0.0"
val appVersionCode = appVersion.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Release signing: keystore.properties locally, environment variables on GitHub Actions.
// Every release must be signed with the same key, otherwise Android refuses to update in place.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val releaseStoreFile = signingValue("storeFile", "PROSKETCH_KEYSTORE")

android {
    namespace = "com.prosketch.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.prosketch.app"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }
    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = signingValue("storePassword", "PROSKETCH_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "PROSKETCH_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "PROSKETCH_KEY_PASSWORD")
        }
    }
    // "github": sideloaded APK that updates itself from GitHub Releases.
    // "play": Google Play build — no self-update and no network access at all.
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
}
