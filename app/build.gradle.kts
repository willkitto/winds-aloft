import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Side-by-side variants: `./gradlew assembleRelease -Pvariant=big-arrows` builds an app with its own
 * id (dev.windsaloft.big_arrows) and name ("Winds Aloft (big-arrows)"), so several experiments can be
 * installed next to each other on one phone or emulator. CI sets this from the branch name.
 */
val variantLabel: String? = (findProperty("variant") as String?)?.trim()?.takeIf { it.isNotEmpty() && it != "main" }
val variantIdSuffix: String? = variantLabel
    ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')
    ?.let { if (it.first().isDigit()) "v_$it" else it }

android {
    namespace = "dev.windsaloft"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.windsaloft"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"

        resValue("string", "app_name", if (variantLabel == null) "Winds Aloft" else "Winds Aloft ($variantLabel)")
        if (variantIdSuffix != null) {
            applicationIdSuffix = ".$variantIdSuffix"
            versionNameSuffix = "-$variantIdSuffix"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be sideloaded directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    // Pass through optional -D flags: screenshot output dir, and a Maven mirror for Robolectric's runtime jars.
    listOf("screenshot.dir", "robolectric.dependency.repo.url").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
