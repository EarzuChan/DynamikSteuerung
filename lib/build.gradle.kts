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
        version = "1"
    }

    buildTypes {
        release {
            // isMinifyEnabled = true
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

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