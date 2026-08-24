plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM/Kotlin module: parsing, decryption and CSV generation live here so the
// whole conversion pipeline can be unit tested on the JVM without a device or emulator.
// It deliberately has no Android and no third-party dependencies -- only the Kotlin
// standard library and the JDK/Android `javax.crypto` primitives.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Target Java 17 bytecode (what AGP 8.x expects) without pinning a toolchain, so the
// module builds on any JDK 17 or newer without needing a toolchain download.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    // Lets SampleFileTest find samples/sample.spass regardless of the working directory.
    systemProperty("spass.sampleDir", rootProject.file("samples").absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
