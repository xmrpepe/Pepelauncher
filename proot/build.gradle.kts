// Builds termux/proot and talloc from source (third_party/) with the NDK and ships the results as
// jniLibs: libproot.so, libproot-loader.so, libproot-loader32.so. See build-proot.sh.
import javax.inject.Inject

plugins {
    id("com.android.library")
}

val prootAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")

android {
    namespace = "io.pepe.launcher.proot"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        create("beta") {
            matchingFallbacks += "release"
        }
    }
}

abstract class BuildProotTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val script: RegularFileProperty

    @get:Input
    abstract val ndkDir: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        execOperations.exec {
            commandLine(
                listOf(
                    "sh",
                    script.get().asFile.absolutePath,
                    ndkDir.get(),
                    workDir.get().asFile.absolutePath,
                    out.absolutePath
                ) + abis.get()
            )
        }
    }
}

val buildProot = tasks.register<BuildProotTask>("buildProot") {
    sources.from(
        fileTree("third_party") { exclude("**/.git") },
        file("build-proot.sh"),
        file("talloc-config.h")
    )
    script.set(file("build-proot.sh"))
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    abis.set(prootAbis)
    workDir.set(layout.buildDirectory.dir("proot-work"))
    outputDir.set(layout.buildDirectory.dir("generated/proot/jniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildProot, BuildProotTask::outputDir)
    }
}
