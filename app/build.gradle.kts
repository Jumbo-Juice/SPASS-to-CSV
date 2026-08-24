import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jumbojuice.spasstocsv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jumbojuice.spasstocsv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // No instrumentation runner: everything worth testing lives in :core and runs
        // as a plain JVM test.
    }

    signingConfigs {
        // Optional real signing. Drop a `keystore.properties` next to this file with
        // storeFile / storePassword / keyAlias / keyPassword to sign properly; the file
        // is git-ignored so no signing material is ever committed.
        create("release") {
            val propertiesFile = rootProject.file("keystore.properties")
            if (propertiesFile.exists()) {
                val properties = Properties()
                propertiesFile.inputStream().use { properties.load(it) }
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: the app is small and has no reflection, so shrinking
            // buys little while adding a way for the release build to break. Turn it on
            // with the rules in proguard-rules.pro if you want a smaller APK.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                // Fall back to the debug key so `./gradlew assembleRelease` still produces
                // an APK you can install straight away. Never publish such a build.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

// Top-level Kotlin extension: `kotlin { }` is a project extension, not part of `android { }`.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Guards the offline promise: fails the build if anything -- our manifest or a library's
 * merged into it -- asks for a network permission.
 */
val verifyNoNetworkPermissions by tasks.registering {
    group = "verification"
    description = "Fails if the merged manifest requests INTERNET or network-state permissions."

    val manifests = fileTree(layout.buildDirectory.dir("intermediates/merged_manifest")) {
        include("**/AndroidManifest.xml")
    }
    val sourceManifest = file("src/main/AndroidManifest.xml")
    inputs.files(manifests, sourceManifest)

    doLast {
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )
        val checked = (manifests.files + sourceManifest).filter { it.isFile }
        require(checked.isNotEmpty()) { "No manifest found to verify." }

        for (manifest in checked) {
            val text = manifest.readText()
            for (permission in forbidden) {
                check(!text.contains(permission)) {
                    "$manifest requests $permission. This app must work fully offline."
                }
            }
        }
        logger.lifecycle("Offline check passed: no network permissions in ${checked.size} manifest(s).")
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach {
    finalizedBy(verifyNoNetworkPermissions)
}
