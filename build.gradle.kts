import name.remal.gradle_plugins.sonarlint.SonarLint
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("name.remal.sonarlint") version "7.2.0"
}

group = "dk.kodeologi.virtualrace"
version = "1.0.0"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dk.kodeologi.virtualrace.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "KayakVirtualRace"
            packageVersion = "1.0.0"
            description = "Kayak Virtual Race - Arcade Kayak Sprint Edition"
            vendor = "Kodeologi"
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

sonarLint {
    ignoreFailures.set(true)
}

tasks.withType<SonarLint>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.register("sonarlintReport") {
    group = "verification"
    description = "Runs SonarLint analysis for all source sets and generates HTML and XML reports."
    dependsOn(tasks.withType<SonarLint>())
}
