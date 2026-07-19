plugins { application }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":guardian-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass.set("org.aegisos.guardian.reference.GuardianReferenceApplication")
}

tasks.test { useJUnitPlatform() }
