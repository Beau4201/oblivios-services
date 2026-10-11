// SPDX-License-Identifier: Apache-2.0
plugins { application }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":guardian-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.sovereignos.guardian.reference.GuardianReferenceApplication")
}

tasks.test { useJUnitPlatform() }
