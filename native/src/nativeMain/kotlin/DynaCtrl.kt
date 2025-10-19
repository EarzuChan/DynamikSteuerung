@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package me.earzuchan.dynactrl.native

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import me.earzuchan.dynactrl.native.utils.JniUtils.directFloatBufferToArray
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

// 测试

private var theNumber = 0

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeIncreaseAndGet")
fun increaseAndGet(env: CPointer<JNIEnvVar>, jClass: jclass): jint = ++theNumber

// 特别领域

private var ebuR128: LightweightEbuR128? = null

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_sNewEbuR128")
fun newEbuR128(env: CPointer<JNIEnvVar>, jClass: jclass, sampleRate: jint, channelCount: jint) {
    Log.i(TAG, "申请新EbuR128")
    ebuR128 = LightweightEbuR128(sampleRate, channelCount)
}

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_sAddEbuR128Samples")
fun addEbuR128Samples(env: CPointer<JNIEnvVar>, jClass: jclass, directBuffer: jobject) {
    if (ebuR128 == null) {
        Log.w(TAG, "妈的，没初始化EbuR128就来添加采样；NOP")
    }

    // TODO：节省采样没做

    val floatArray = env.directFloatBufferToArray(directBuffer)
    Log.i(TAG, "添加到现有：${floatArray.size}")
    ebuR128!!.addSamples(floatArray)
}

@CName("Java_me_earzuchan_dynactrl_DynaCtrl_sFinalizeEbuR128")
fun finalizeEbuR128(env: CPointer<JNIEnvVar>, jClass: jclass): jfloat {
    if (ebuR128 == null) {
        Log.w(TAG, "妈的，没初始化EbuR128就来敲定")
        return -70f
    }

    val result = ebuR128!!.getIntegratedLoudness()
    Log.i(TAG, "敲定：$result")
    ebuR128 = null

    return result
}

// 初始化

@CName("JNI_OnLoad")
fun jniOnLoad(vm: CPointer<JavaVMVar>): jint {
    val ok = vm.isOk()

    Log.d(TAG, "JNI，OK：$ok；USING：1.6")

    return JNI_VERSION_1_6
}
