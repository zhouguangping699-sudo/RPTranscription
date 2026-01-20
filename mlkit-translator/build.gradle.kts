plugins {
    id("com.android.library")
}

android {
    namespace = "com.rp.mlkittranslator"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        targetSdk = 35
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
}
