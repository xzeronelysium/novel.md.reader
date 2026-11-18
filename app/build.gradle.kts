plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("application")
}

group = "com.mdreader"
version = "1.1"

application {
    mainClass.set("com.mdreader.MainKt")
}

tasks.jar {
    archiveBaseName.set("novel-md-reader")
    manifest {
        attributes["Main-Class"] = "com.mdreader.MainKt"
    }
}

tasks.register("printRuntimeClasspath") {
    doLast {
        configurations.getByName("runtimeClasspath").forEach { println(it.absolutePath) }
    }
}

val composeVersion = "1.8.0"

dependencies {
    implementation("org.jetbrains.compose.desktop:desktop:$composeVersion")
    implementation("org.jetbrains.compose.material3:material3:$composeVersion")
    implementation("org.jetbrains.compose.animation:animation:$composeVersion")
    implementation("org.json:json:20240303")

    // Skiko ships its native Skia library in a separate per-platform artifact.
    // Without it on the classpath the app dies at startup with
    // "Cannot find libskiko-linux-x64.so.sha256".
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.9.4")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}
