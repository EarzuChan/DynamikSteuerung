@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package me.earzuchan.dynactrl.native

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import me.earzuchan.dynactrl.native.utils.JniUtils.getFloatArray
import me.earzuchan.dynactrl.native.utils.JniUtils.getString
import me.earzuchan.dynactrl.native.utils.JniUtils.isOk
import me.earzuchan.dynactrl.native.utils.Log
import platform.android.*
import kotlin.experimental.ExperimentalNativeApi

private const val TAG = "DynaCtrlNative"

// 普通代码

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeAnalyzeFile")
fun analyzeFile(env: CPointer<JNIEnvVar>, jClass: jclass, jFilePath: jstring): jfloat {
    val filePath = env.getString(jFilePath) ?: return -70f

    Log.i(TAG, "计算响度，文件：$filePath")
    val result = LightweightLoudnessAnalyzer.analyzeFile(filePath)
    Log.i(TAG, "计算结果：$result")

    return result
}

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeCalculateLoudness")
fun calculateLoudness(
    env: CPointer<JNIEnvVar>, jClass: jclass,
    pcmData: jfloatArray, sampleRate: jint, channelCount: jint
): jfloat {
    val pcmArr = env.getFloatArray(pcmData)
    if (pcmArr.isEmpty()) return (-70f).also { Log.w(TAG, "空数组我计算勾八响度") }

    Log.i(TAG, "直接通过解码的数据计算响度")
    val result = LightweightLoudnessAnalyzer.calculateLoudness(AudioData(pcmArr, sampleRate, channelCount))
    Log.i(TAG, "计算结果：$result")

    return result
}

// 初始化

@CName("JNI_OnLoad")
fun jniOnLoad(vm: CPointer<JavaVMVar>): jint {
    val ok = vm.isOk()

    Log.d(TAG, "JNI，OK：$ok；USING：1.6")

    return JNI_VERSION_1_6
}
