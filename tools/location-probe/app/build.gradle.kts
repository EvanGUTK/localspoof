plugins { id("com.android.application") }
android {
    namespace = "dev.localspoof.locationprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.localspoof.locationprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation("com.google.android.gms:play-services-location:21.4.0") }
