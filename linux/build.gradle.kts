plugins {
    id("com.android.library")
}

android {
    namespace = "io.pepe.launcher.linux"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
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
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":bohio"))
    implementation(project(":proot"))
    implementation(project(":terminal"))
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
