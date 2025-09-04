// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    val agpVer="8.11.0"
    val ktVer="2.2.10"
    id("com.android.library") version agpVer apply false
    id("com.android.application") version agpVer apply false
    id("org.jetbrains.kotlin.android") version ktVer apply false
    id("org.jetbrains.kotlin.plugin.compose") version ktVer apply false
}