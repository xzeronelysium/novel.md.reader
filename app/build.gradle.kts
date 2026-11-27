import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mdreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mdreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "2.0"
        // AndroidX ships ~85 locales; keep only the ones the app could use.
        resourceConfigurations += listOf("en", "ja")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Self-signed so the release variant installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jsoup:jsoup:1.18.3")
    // The comic layer, for sites behind Cloudflare: HTTP/2 and browser-like header order.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // The android.jar stubs of org.json throw on the JVM; tests need the real thing.
    testImplementation("org.json:json:20240303")
}
