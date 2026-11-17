plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vkturn.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vkturn.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    bundle { language { enableSplit = false } }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
dependencies {
    implementation(files("libs/backend.aar"))
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
