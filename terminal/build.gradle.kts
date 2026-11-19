// Vendored Termux terminal-emulator + terminal-view (Apache-2.0), see README.pepelauncher.md.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 21
        externalNativeBuild {
            cmake {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector")
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation(libs.junit)
}
