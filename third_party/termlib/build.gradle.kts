import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Retain upstream's four native ABIs exactly. Only the reviewed Kotlin API
// visibility changes in this directory are recompiled; no NDK is required.
val upstreamBinary by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val extractNativeLibraries by tasks.registering(Sync::class) {
    val binary = upstreamBinary.singleFile
    inputs.file(binary)
    doFirst {
        val digest = MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "427dc409194fb9accd8e379254117e85d6f915635c58b1716dbd9d3095d98505") {
            "The pinned upstream terminal binary changed. Review it before updating the checksum."
        }
    }
    from(zipTree(binary)) { include("jni/**") }
    into(layout.buildDirectory.dir("upstream-native"))
}
val verifySourceText by tasks.registering {
    val sources = fileTree("src") { include("**/*.kt", "**/*.xml") }
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    val marker = layout.buildDirectory.file("verification/source-text.txt")
    outputs.file(marker)
    doLast {
        val controls = (0x202A..0x202E).toSet() + (0x2066..0x2069).toSet() + setOf(0x200E, 0x200F, 0x061C)
        sources.files.forEach { source ->
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source.readBytes())).toString()
            text.forEachIndexed { offset, character ->
                check(character.code !in controls) {
                    "${source.relativeTo(projectDir)}:${text.take(offset).count { it == '\n' } + 1}: bidirectional source control U+${character.code.toString(16).uppercase()} requires review"
                }
            }
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("${sources.files.size} source files verified: strict UTF-8, no bidirectional controls.\n") }
    }
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP9.4/Kotlin2.4's comment PSI walker spins in this detector on the
    // pinned upstream sources. The mandatory raw scan above is stricter and
    // avoids that AST path; every other library and app lint check stays enabled.
    lint { disable.add("BidirectionalText") }
}
tasks.named("preBuild") { dependsOn(extractNativeLibraries, verifySourceText) }
tasks.matching { it.name.startsWith("lint") }.configureEach { dependsOn(verifySourceText) }
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(layout.buildDirectory.dir("upstream-native/jni").get().asFile.path)
    }
}

dependencies {
    upstreamBinary("org.connectbot:termlib:0.3.11@aar")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
