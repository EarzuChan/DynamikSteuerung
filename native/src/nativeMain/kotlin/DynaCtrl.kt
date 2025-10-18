@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package me.earzuchan.dynactrl.native


import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import me.earzuchan.dynactrl.native.utils.JniUtils.getString
import me.earzuchan.dynactrl.native.utils.JniUtils.isOk
import me.earzuchan.dynactrl.native.utils.Log
import platform.android.*
import kotlin.experimental.ExperimentalNativeApi

private const val TAG = "DynaCtrlNative"

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeAnalyzeFile")
fun analyzeFile(env: CPointer<JNIEnvVar>, jClass: jclass, jFilePath: jstring): jfloat {
    val filePath = env.getString(jFilePath) ?: return -70f

    Log.i(TAG, "计算响度，文件：$filePath")
    val result = LightweightLoudnessAnalyzer.analyzeFile(filePath)
    Log.i(TAG, "计算结果：$result")

    return result
}

@CName("JNI_OnLoad")
fun jniOnLoad(vm: CPointer<JavaVMVar>): jint {
    val ok = vm.isOk()

    Log.d(TAG, "JNI，OK：$ok；USING：1.6")

    return JNI_VERSION_1_6
}
