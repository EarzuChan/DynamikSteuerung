package me.earzuchan.dynactrl.native

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import me.earzuchan.dynactrl.native.utils.JniUtils.isOk
import me.earzuchan.dynactrl.native.utils.JniUtils.j
import me.earzuchan.dynactrl.native.utils.Log
import platform.android.*
import kotlin.experimental.ExperimentalNativeApi

object DynaCtrl {
    private const val TAG = "DynaCtrlNative"

    @OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
    @CName("Java_me_earzuchan_dynactrl_DynaCtrl_nativeInit")
    fun init(env: CPointer<JNIEnvVar>): jboolean {
        Log.i(TAG, "初始化一个个；DC HASH：${hashCode()}")

        return true.j
    }
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("JNI_OnLoad")
fun jniOnLoad(vm: CPointer<JavaVMVar>): jint {
    val ok = vm.isOk()

    println("JNI_ON_LOAD：$ok；DC HASH：${DynaCtrl.hashCode()}")

    return JNI_VERSION_1_6
}