// SPDX-License-Identifier: Apache-2.0
plugins {
    java
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":decoy-content-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("org.oblivios.decoy.pool.gate.PoolGate") }

tasks.test { useJUnitPlatform() }
