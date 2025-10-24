plugins {
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val appId = "me.earzuchan.tetsu.demoapp"

android {
    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 21
        targetSdk = 36
        versionCode = 3
        versionName = "2"

        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            packaging {
                resources {
                    excludes += listOf(
                        "kotlin/**",
                        "assets/**",
                        "DebugProbesKt.bin",
                        "kotlin-tooling-metadata.json"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }
}

kotlin{
    jvmToolchain(21)
}

dependencies {
    implementation(libs.coreKtx)
    implementation(libs.lifecycleRuntimeKtx)
    implementation(libs.activityCompose)

    implementation(platform(libs.composeBom))
    implementation(libs.composeMaterial3)
    implementation(libs.composeUi.toolingPreview)
    debugImplementation(libs.composeUi.tooling)

    implementation(libs.media3commonCtx)
    implementation(libs.media3exoplayer)
    implementation(libs.media3ui)

    implementation(project(":lib"))
}