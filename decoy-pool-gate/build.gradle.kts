// SPDX-License-Identifier: Apache-2.0
plugins {
    java
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    // The library builds later as an Android.bp java_library (ADR-318 (3)): no Java 21 API, no unread warning.
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":decoy-content-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("org.oblivios.decoy.pool.gate.PoolGate") }

tasks.test { useJUnitPlatform() }
