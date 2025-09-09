package me.earzuchan.dynactrl.native

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import me.earzuchan.dynactrl.native.utils.JniUtils.getString
import me.earzuchan.dynactrl.native.utils.JniUtils.isOk
import me.earzuchan.dynactrl.native.utils.Log
import platform.android.*
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
object DynaCtrl {
    private const val TAG = "DynaCtrlNative"

    /*@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
    @CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeInit")
    fun init(env: CPointer<JNIEnvVar>): jboolean {
        Log.i(TAG, "初始化一个个；DC HASH：${hashCode()}")

        return true.j
    }*/

    @OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
    @CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeAnalyzeFile")
    fun analyzeFile(env: CPointer<JNIEnvVar>, jClass: jclass, jFilePath: jstring): jfloat = memScoped {
        Log.d(TAG, "拓一个！$jClass；$jFilePath")

        val filePath = env.getString(jFilePath)

        Log.i(TAG, "计算一个个响度，文件：$filePath；DC HASH：${hashCode()}")

        return -70f
    }

    // FUCK：操你妈JNI绑定
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("JNI_OnLoad")
fun jniOnLoad(vm: CPointer<JavaVMVar>): jint {
    val ok = vm.isOk()

    println("JNI，OK：$ok；USIN：1.6")

    return JNI_VERSION_1_6
}
