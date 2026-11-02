plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.jorge.tradutorzh"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.jorge.tradutorzh"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Samsung Galaxy A55 (Exynos 1480) é arm64 — só essa ABI deixa o APK menor
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug para poder instalar direto no celular
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Reconhecimento de fala offline (mandarim)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Tradução offline no aparelho (chinês -> português)
    implementation("com.google.mlkit:translate:17.0.3")
}
