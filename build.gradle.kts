import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

// ---------- 读取 SDK 路径 ----------
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

// ---------- 动态查找所有 SDK 组件（兼容任意版本） ----------
// 1. Android Jar（自动取最高 platform）
val androidJar = providers.provider {
    val sdk = sdkDir.get()
    val platformsDir = File(sdk, "platforms")
    require(platformsDir.exists()) { "SDK platforms 目录不存在: $platformsDir" }
    val platformDirs = platformsDir.listFiles { file -> file.isDirectory && file.name.startsWith("android-") }
    require(!platformDirs.isNullOrEmpty()) { "未找到任何 platform 目录" }
    // 按版本号降序（字符串排序，对 "android-35" 等数字有效）
    val sorted = platformDirs.sortedByDescending { it.name }
    val chosen = sorted.first()
    val jarFile = File(chosen, "android.jar")
    require(jarFile.exists()) { "在 ${chosen.absolutePath} 中未找到 android.jar" }
    jarFile.absolutePath
}

// 2. build-tools 目录（最高版本）
val buildToolsDir = providers.provider {
    val sdk = sdkDir.get()
    val btDir = File(sdk, "build-tools")
    require(btDir.exists()) { "build-tools 目录不存在: $btDir" }
    val versionDirs = btDir.listFiles { file -> file.isDirectory && file.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
    require(!versionDirs.isNullOrEmpty()) { "未找到任何 build-tools 版本目录" }
    versionDirs.sortedByDescending { it.name }.first().absolutePath
}

// 3. d8 可执行文件（Windows 下优先 d8.bat，否则 d8）
val d8Executable = providers.provider {
    val btPath = buildToolsDir.get()
    val btDir = File(btPath)
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val candidates = if (isWindows) {
        listOf(File(btDir, "d8.bat"), File(btDir, "d8"))
    } else {
        listOf(File(btDir, "d8"))
    }
    val d8File = candidates.firstOrNull { it.exists() }
    requireNotNull(d8File) { "在 $btPath 中未找到 d8 可执行文件（尝试了 ${candidates.map { it.name }}）" }
    d8File.absolutePath
}

// 4. CMake 可执行文件（最高版本）
val cmakeExecutable = providers.provider {
    val sdk = sdkDir.get()
    val cmakeBase = File(sdk, "cmake")
    require(cmakeBase.exists()) { "CMake 目录不存在: $cmakeBase" }
    val versionDirs = cmakeBase.listFiles { file -> file.isDirectory && file.name.startsWith("3.") }
    require(!versionDirs.isNullOrEmpty()) { "未找到任何 CMake 版本目录" }
    val chosen = versionDirs.sortedByDescending { it.name }.first()
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val exe = if (isWindows) "cmake.exe" else "cmake"
    val cmakeFile = File(chosen, "bin/$exe")
    require(cmakeFile.exists()) { "在 ${chosen.absolutePath}/bin 中未找到 $exe" }
    cmakeFile.absolutePath
}

// 5. Ninja 可执行文件（优先在 CMake 目录下找，若缺失则搜索其他版本）
val ninjaExecutable = providers.provider {
    val sdk = sdkDir.get()
    val cmakeBase = File(sdk, "cmake")
    require(cmakeBase.exists()) { "CMake 目录不存在: $cmakeBase" }
    val versionDirs = cmakeBase.listFiles { file -> file.isDirectory && file.name.startsWith("3.") }
    require(!versionDirs.isNullOrEmpty()) { "未找到任何 CMake 版本目录" }
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val exe = if (isWindows) "ninja.exe" else "ninja"
    // 优先取最高版本，若找不到则遍历所有版本
    val sorted = versionDirs.sortedByDescending { it.name }
    var found = sorted.firstOrNull { File(it, "bin/$exe").exists() }
    if (found == null) {
        found = versionDirs.firstOrNull { File(it, "bin/$exe").exists() }
    }
    requireNotNull(found) { "在所有 CMake 目录中均未找到 $exe" }
    File(found, "bin/$exe").absolutePath
}

// 6. NDK 目录（最高版本）
val ndkDir = providers.provider {
    val sdk = sdkDir.get()
    val ndkBase = File(sdk, "ndk")
    require(ndkBase.exists()) { "NDK 目录不存在: $ndkBase" }
    val versionDirs = ndkBase.listFiles { file -> file.isDirectory && file.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
    require(!versionDirs.isNullOrEmpty()) { "未找到任何 NDK 版本目录" }
    versionDirs.sortedByDescending { it.name }.first().absolutePath
}

// 7. NDK toolchain 文件
val toolchainFile = providers.provider {
    val ndkPath = ndkDir.get()
    val toolchain = File(ndkPath, "build/cmake/android.toolchain.cmake")
    require(toolchain.exists()) { "未找到 toolchain 文件: ${toolchain.absolutePath}" }
    toolchain.absolutePath
}

// ---------- 原有路径定义（保持不变） ----------
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

// ---------- 任务定义（保持原样） ----------
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

tasks.register<Jar>("jarInjectorClasses") {
    group = "build"
    description = "Package compiled injector classes into a jar for d8."
    dependsOn("compileInjectorJava")

    archiveFileName.set("wechatmonet-injector.jar")
    destinationDirectory.set(injectorJarDir)
    from(classesOutDir)
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
