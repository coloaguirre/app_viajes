// whisper.cpp compiled with the NDK (DISENO §8.3, PLAN 3.4). Pure CPU build, 16 KB page
// aligned, optimised even in debug (unoptimised whisper is unusable). ABIs: arm64-v8a for
// phones, x86_64 for the emulator.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.bitacoraviaje.whisper"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared")
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
