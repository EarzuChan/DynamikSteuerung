plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    //生成android平台下的 .so
    arrayOf(androidNativeArm32(), androidNativeArm64(), androidNativeX86(), androidNativeX64()).forEach {
        it.binaries {
            // executable()
            sharedLib { baseName = "dynactrl" }
        }
    }

    sourceSets {
        nativeMain.dependencies {}
        arrayOf(androidNativeArm32Main, androidNativeArm64Main, androidNativeX86Main, androidNativeX64Main).forEach {
            it.get().dependsOn(nativeMain.get())
        }
    }
}
