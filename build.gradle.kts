// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // Upgrade KGP from AGP 9's bundled default to the version pinned in libs.versions.toml
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
