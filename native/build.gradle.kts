plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    //生成android平台下的 .so
    val where = project.rootDir.toString().replace('\\', '/')

    mapOf(
        androidNativeArm32() to "armeabi-v7a",
        androidNativeArm64() to "arm64-v8a",
        androidNativeX64() to "x86_64",
    ).forEach { (target, archName) ->
        target.compilations.getByName("main") {
            cinterops {
                val libsndfile by creating {
                    header("${where}/native/include/sndfile.h")
                }
            }
        }

        target.binaries {
            sharedLib {
                baseName = "dynactrl"
                linkerOpts.add("-L${where}/lib/src/main/jniLibs/$archName")
                linkerOpts.add("-lsndfile")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {}
        arrayOf(androidNativeArm32Main, androidNativeArm64Main, androidNativeX64Main).forEach {
            it.get().dependsOn(nativeMain.get())
        }
    }
}
