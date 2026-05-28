import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

val sdkDir = providers.gradleProperty("sdk.dir")
    .orElse(providers.provider {
        val localProps = file("local.properties")
        if (!localProps.exists()) return@provider ""
        localProps.readLines()
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("=")
            ?.replace("\\:", ":")
            ?.replace("\\\\", "\\")
            .orEmpty()
    })

val androidJar = sdkDir.map { "$it/platforms/android-35/android.jar" }
val buildToolsDir = sdkDir.map { "$it/build-tools/37.0.0" }
val d8Executable = buildToolsDir.map { "$it/d8.bat" }
val cmakeExecutable = sdkDir.map { "$it/cmake/3.22.1/bin/cmake.exe" }
val ninjaExecutable = sdkDir.map { "$it/cmake/3.22.1/bin/ninja.exe" }
val ndkDir = sdkDir.map { "$it/ndk/29.0.13599879" }
val toolchainFile = ndkDir.map { "$it/build/cmake/android.toolchain.cmake" }

val buildRoot = layout.buildDirectory
val javaSrcDir = layout.projectDirectory.dir("src/main/java")
val cppSrcDir = layout.projectDirectory.dir("src/main/cpp")
val moduleTemplateDir = layout.projectDirectory.dir("module")
val dexOutDir = buildRoot.dir("dex")
val classesOutDir = buildRoot.dir("classes")
val injectorJarDir = buildRoot.dir("libs")
val injectorJar = buildRoot.file("libs/wechatmonet-injector.jar")
val nativeBuildRoot = buildRoot.dir("cmake")
val nativeLibsRoot = buildRoot.dir("native-libs")
val moduleStagingDir = buildRoot.dir("magisk-module")
val releaseDir = buildRoot.dir("outputs/magisk")

val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

fun abiOutName(abi: String): String = "$abi.so"

tasks.register<Exec>("compileInjectorJava") {
    group = "build"
    description = "Compile Java injector classes with JDK 21."

    inputs.dir(javaSrcDir)
    outputs.dir(classesOutDir)

    doFirst {
        classesOutDir.get().asFile.mkdirs()
        val sources = fileTree(javaSrcDir).matching { include("**/*.java") }.files.map { it.absolutePath }
        if (sources.isEmpty()) {
            throw GradleException("No Java sources found under ${javaSrcDir.asFile.absolutePath}")
        }
        commandLine(
            "javac",
            "--release", "21",
            "-cp", androidJar.get(),
            "-d", classesOutDir.get().asFile.absolutePath,
            *sources.toTypedArray()
        )
    }
}

tasks.register<Exec>("buildInjectorDex") {
    group = "build"
    description = "Build injector dex payload."
    dependsOn("jarInjectorClasses")

    inputs.file(injectorJar)
    outputs.file(dexOutDir.map { it.file("wechatmonet-injector.dex") })

    doFirst {
        dexOutDir.get().asFile.mkdirs()
        commandLine(
            d8Executable.get(),
            "--release",
            "--min-api", "31",
            "--lib", androidJar.get(),
            "--output", dexOutDir.get().asFile.absolutePath,
            injectorJar.get().asFile.absolutePath
        )
    }
}

tasks.register<Jar>("jarInjectorClasses") {
    group = "build"
    description = "Package compiled injector classes into a jar for d8."
    dependsOn("compileInjectorJava")

    archiveFileName.set("wechatmonet-injector.jar")
    destinationDirectory.set(injectorJarDir)
    from(classesOutDir)
}

abis.forEach { abi ->
    val taskName = "buildNative${abi.replace("-", "").replace("_", "").replaceFirstChar { it.uppercase() }}"
    val abiBuildDir = nativeBuildRoot.map { it.dir(abi) }
    val abiOutDir = nativeLibsRoot.map { it.dir(abi) }

    tasks.register<Exec>(taskName) {
        group = "build"
        description = "Build native Zygisk library for $abi."

        inputs.dir(cppSrcDir)
        outputs.file(abiOutDir.map { it.file("libwechatmonet.so") })

        doFirst {
            abiBuildDir.get().asFile.mkdirs()
            abiOutDir.get().asFile.mkdirs()

            project.exec {
                commandLine(
                    cmakeExecutable.get(),
                    "-S", cppSrcDir.asFile.absolutePath,
                    "-B", abiBuildDir.get().asFile.absolutePath,
                    "-G", "Ninja",
                    "-DCMAKE_MAKE_PROGRAM=${ninjaExecutable.get()}",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-31",
                    "-DANDROID_NDK=${ndkDir.get()}",
                    "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.get()}",
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${abiOutDir.get().asFile.absolutePath}"
                )
            }

            commandLine(
                cmakeExecutable.get(),
                "--build", abiBuildDir.get().asFile.absolutePath
            )
        }
    }
}

tasks.register<Copy>("stageMagiskModule") {
    group = "build"
    description = "Stage a pure Zygisk Magisk module."
    dependsOn("buildInjectorDex")
    dependsOn(abis.map { abi -> "buildNative${abi.replace("-", "").replace("_", "").replaceFirstChar { it.uppercase() }}" })

    from(moduleTemplateDir)
    into(moduleStagingDir)

    doLast {
        val zygiskDir = moduleStagingDir.get().dir("zygisk").asFile
        zygiskDir.mkdirs()

        abis.forEach { abi ->
            val source = nativeLibsRoot.get().dir(abi).file("libwechatmonet.so").asFile
            if (!source.exists()) {
                throw GradleException("Missing native output for $abi: ${source.absolutePath}")
            }
            source.copyTo(zygiskDir.resolve(abiOutName(abi)), overwrite = true)
        }

        val frameworkDir = moduleStagingDir.get().dir("framework").asFile
        frameworkDir.mkdirs()
        dexOutDir.get().file("classes.dex").asFile.copyTo(
            frameworkDir.resolve("wechatmonet-injector.dex"),
            overwrite = true
        )
    }
}

tasks.register<Zip>("packageMagiskModule") {
    group = "build"
    description = "Package the pure Zygisk module zip."
    dependsOn("stageMagiskModule")

    archiveFileName.set("wechatmonet-zygisk.zip")
    destinationDirectory.set(releaseDir)
    from(moduleStagingDir)
}

tasks.named("assemble") {
    dependsOn("packageMagiskModule")
}
