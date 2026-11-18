plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mdreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mdreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // CommonMark core plus the GitHub-Flavored Markdown extensions. The parser
    // is pure Java/Kotlin, so the whole rendering pipeline stays testable on the JVM.
    implementation("org.commonmark:commonmark:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.1")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.1")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.1")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.1")
    implementation("org.commonmark:commonmark-ext-footnotes:0.30.1")
    implementation("org.commonmark:commonmark-ext-ins:0.30.1")
    implementation("org.commonmark:commonmark-ext-yaml-front-matter:0.30.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
