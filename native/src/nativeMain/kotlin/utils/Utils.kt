package me.earzuchan.dynactrl.native.utils

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.android.ANDROID_LOG_DEBUG
import platform.android.ANDROID_LOG_ERROR
import platform.android.ANDROID_LOG_INFO
import platform.android.ANDROID_LOG_WARN
import platform.android.JNIEnvVar
import platform.android.JNI_OK
import platform.android.JNI_VERSION_1_6
import platform.android.JavaVMVar
import platform.android.__android_log_print

object Log {
    fun d(tag: String, msg: String) = __android_log_print(ANDROID_LOG_DEBUG.toInt(), tag, msg)
    fun e(tag: String, msg: String) = __android_log_print(ANDROID_LOG_ERROR.toInt(), tag, msg)
    fun i(tag: String, msg: String) = __android_log_print(ANDROID_LOG_INFO.toInt(), tag, msg)
    fun w(tag: String, msg: String) = __android_log_print(ANDROID_LOG_WARN.toInt(), tag, msg)
}

@OptIn(ExperimentalForeignApi::class)
object JniUtils {
    fun CPointer<JavaVMVar>.isOk(): Boolean = memScoped {
        val envStorage = alloc<CPointerVar<JNIEnvVar>>()
        val vmValue = this@isOk.pointed.pointed!!

        val result = vmValue.GetEnv!!(this@isOk, envStorage.ptr.reinterpret(), JNI_VERSION_1_6)

        return result == JNI_OK
    }

    val Boolean.j: UByte get() = if (this) 1u else 0u
}