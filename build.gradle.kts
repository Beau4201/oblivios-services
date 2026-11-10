// SPDX-License-Identifier: Apache-2.0
plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.11.0" apply false
}

allprojects {
    group = "org.oblivios"
    version = "0.1.0-SNAPSHOT"
}
