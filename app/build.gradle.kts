plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.jorge.tradutorzh"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "br.jorge.tradutorzh"
        minSdk = 33          // Galaxy A55 vem com Android 14+
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // Galaxy A55 (Exynos 1480) é arm64 — só essa ABI deixa o APK menor
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug para instalar direto no celular
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // modelos ONNX sem compressão: carregam mais rápido do APK
    androidResources { noCompress += listOf("onnx") }

    packaging { jniLibs { useLegacyPackaging = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Reconhecimento de fala offline (sherpa-onnx: Zipformer streaming + SenseVoice)
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    // Legenda instantânea offline (chinês -> português)
    implementation("com.google.mlkit:translate:17.0.3")
}
