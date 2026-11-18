import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
}

@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
dependencies {
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.mdreader.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "novel-md-reader"
            packageVersion = "1.1.0"
            description = "Read novels from folders of markdown chapters"
            vendor = "novel.md.reader"
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                debMaintainer = "novel.md.reader"
                menuGroup = "Office;TextEditor;"
            }
        }
    }
}
