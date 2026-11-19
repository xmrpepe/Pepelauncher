pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pepelauncher"
include(":app")
include(":bohio")
// pepelauncher modules
include(":linux")
include(":proot")
include(":terminal")
include(":compositor")

// Activate For Release
project(":bohio").projectDir = file("bohio")

// Activate For Bohio Development (and locate your local source)
//project(":bohio").projectDir = file("../25_mod__bohio")