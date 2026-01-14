plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rp.rptranscription"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rp.rptranscription"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "libs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            pickFirsts.add("lib/arm64-v8a/libonnxruntime.so")
        }
        resources {
            excludes += listOf("META-INF/*.kotlin_module")
        }
    }
    
    // Force extraction of native libraries from AAR dependencies
    androidResources {
        noCompress += listOf("so")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.9.0")
    implementation(libs.appcompat)
    implementation(libs.material)
//    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // ONNX Runtime dependencies - keep Java API but native libs will come from jniLibs
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
//    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.3")
    
    implementation("com.google.guava:guava:33.0.0-android")
    implementation("com.google.mlkit:language-id:17.0.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}