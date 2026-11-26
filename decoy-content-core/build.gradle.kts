// SPDX-License-Identifier: Apache-2.0
plugins { java }

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
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// The checkers over many plans: ./gradlew :decoy-content-core:harness --args="--seeds 300 --pool <file> --out <tsv> [--audit]"
tasks.register<JavaExec>("harness") {
    description = "Runs both checkers of the decoy generator over many plans (PlanHarness)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.oblivios.decoy.content.harness.PlanHarness")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
