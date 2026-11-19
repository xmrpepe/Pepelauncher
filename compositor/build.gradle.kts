// The pepelauncher Wayland compositor (C, see README.pepelauncher.md) and its JNI wrapper.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.pepe.launcher.compositor"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                // Only loaded on Android 10+ (the Linux runtime's minimum); libwayland needs API 23+.
                arguments += listOf("-DANDROID_PLATFORM=android-29", "-DANDROID_STL=none")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        create("beta") {
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
