plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":guardian-core"))
    implementation(project(":guardian-reference"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(compose.desktop.uiTestJUnit4)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}

application {
    mainClass.set("org.aegisos.guardian.ui.GuardianReferenceDesktopApplicationKt")
}

tasks.test { useJUnitPlatform() }
