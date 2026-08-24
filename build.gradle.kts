// Plugins are declared by each module rather than pre-loaded here with `apply false`.
// That keeps the two modules independent: `:core` is a plain Kotlin/JVM library, so
// `./gradlew :core:test --configure-on-demand` runs the parser and CSV tests without
// needing the Android Gradle Plugin or the Android SDK on the machine at all.
