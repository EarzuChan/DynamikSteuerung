plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val libId = "me.earzuchan.dynactrl"

android {
    namespace = libId
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        version = "1.0"

        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets { getByName("main") { jniLibs.srcDirs("src/main/jniLibs") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions { jvmTarget = "21" }
}

dependencies {
    val ktVer = "2.2.10"
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$ktVer")

    // AndroidX 核心库
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // 只是依赖下
    api("androidx.media3:media3-common-ktx:1.8.0")
}

// Rust target 到 Android ABI 的映射
val rustTargetToAndroidAbi = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "armv7-linux-androideabi" to "armeabi-v7a",
    "i686-linux-android" to "x86",
    "x86_64-linux-android" to "x86_64"
)

// 创建构建 Rust 库的任务
val buildRustLibrary = tasks.register("buildRustLibrary") {
    group = "rust"
    description = "Build Rust native library"

    dependsOn(cleanRustLibrary)

    // 使用 Provider API 来延迟计算路径
    val nativeDir = project.layout.projectDirectory.dir("../native")
    val jniLibsDir = project.layout.buildDirectory.dir("../src/main/jniLibs").get()

    // 设置 inputs 和 outputs
    inputs.dir(nativeDir.dir("src"))
    inputs.file(nativeDir.file("Cargo.toml"))

    rustTargetToAndroidAbi.values.forEach { outputs.file(jniLibsDir.file("$it/libdynactrl.so")) }

    doLast {
        val nativeDirFile = nativeDir.asFile
        val jniLibsDirFile = jniLibsDir.asFile

        rustTargetToAndroidAbi.forEach { (rustTarget, androidAbi) ->
            // 构建 Rust 库
            exec {
                workingDir = nativeDirFile
                commandLine("cargo", "build", "--target", rustTarget, "--release")
            }

            // 创建目标目录
            val targetDir = File(jniLibsDirFile, androidAbi)
            targetDir.mkdirs()

            // 复制生成的库文件
            val sourceFile = File(nativeDirFile, "target/$rustTarget/release/libdynactrl.so")
            val targetFile = File(targetDir, "libdynactrl.so")

            if (sourceFile.exists()) {
                sourceFile.copyTo(targetFile, overwrite = true)
                println("Copied $sourceFile to $targetFile")
            } else println("Warning: $sourceFile not found")
        }
    }
}

// 创建清理 Rust 构建产物的任务
val cleanRustLibrary = tasks.register("cleanRustLibrary") {
    group = "rust"
    description = "Clean Rust native library build artifacts"

    val nativeDir = project.layout.projectDirectory.dir("../native")
    val jniLibsDir = project.layout.buildDirectory.dir("../src/main/jniLibs").get()

    doLast {
        val nativeDirFile = nativeDir.asFile
        val jniLibsDirFile = jniLibsDir.asFile

        exec {
            workingDir = nativeDirFile
            commandLine("cargo", "clean")
        }

        // 也清理 jniLibs 目录
        if (jniLibsDirFile.exists()) jniLibsDirFile.deleteRecursively()
    }
}

// 让 Android 构建任务依赖 Rust 构建任务
// tasks.named("preBuild") { dependsOn(buildRustLibrary) }

// 让清理任务也清理 Rust 构建产物
// tasks.named("clean") { dependsOn(cleanRustLibrary) }