buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP's built-in Kotlin support uses the version selected on its classpath.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
