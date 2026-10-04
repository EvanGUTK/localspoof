plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.localspoof.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.localspoof.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            // Android 16's Robolectric runtime needs Java 21; production bytecode remains Java 17.
            providers.environmentVariable("LOCALSPOOF_TEST_JAVA_HOME").orNull?.let { javaRoot ->
                val binary = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
                it.executable = file("$javaRoot/bin/$binary").absolutePath
            }
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../assets", "../../resources")
    androidResources { ignoreAssetsPatterns.add("*.md") }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
}
dependencies {
    implementation(project(":session-domain"))
    implementation(project(":platform-location"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
