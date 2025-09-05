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

    // 只是依赖下
    val m3ver = "1.8.0"
    api("androidx.media3:media3-common-ktx:${m3ver}")
}

val buildAndCopyNativeLibs = tasks.register("buildAndCopyNativeLibs") {
    group = "build"
    description = "Build native libraries and copy them"

    val nativeProject = project(":native")

    // 依赖 native 项目的架构构建任务
    dependsOn(nativeProject.tasks.named("linkReleaseSharedAndroidNativeX64"))
    dependsOn(nativeProject.tasks.named("linkReleaseSharedAndroidNativeX86"))
    dependsOn(nativeProject.tasks.named("linkReleaseSharedAndroidNativeArm32"))
    dependsOn(nativeProject.tasks.named("linkReleaseSharedAndroidNativeArm64"))

    doLast {
        val libJNILibsDir = project.layout.projectDirectory.dir("src/main/jniLibs").asFile

        // 先删除旧的 CHECK：暂时不
        // libJNILibsDir.takeIf { it.exists() }?.deleteRecursively()

        // 定义架构映射（从 native build path 到 JNI libs path）
        val architectureMappings = mapOf(
            "androidNativeX64" to "x86_64",
            "androidNativeX86" to "x86",
            "androidNativeArm32" to "armeabi-v7a",
            "androidNativeArm64" to "arm64-v8a"
        )

        // 对于每个架构，复制 libdynactrl.so 到对应的 JNI libs 目录
        architectureMappings.forEach { (buildArch, jniArch) ->
            val sourceFile = nativeProject.file("build/bin/$buildArch/releaseShared/libdynactrl.so")

            if (!sourceFile.exists()) println("${sourceFile.path}不存在，${jniArch}的构建可能失败了")
            else {
                val targetDir = File(libJNILibsDir,jniArch)

                println("正在把${sourceFile.path}复制到$targetDir")

                // 确保目标目录存在
                targetDir.mkdirs()

                copy {
                    from(sourceFile)
                    into(targetDir)
                }
            }
        }

        // 清理Build目录 CHECK：必要吗
        // nativeProject.file("build/bin").takeIf { it.exists() }?.deleteRecursively()
    }
}