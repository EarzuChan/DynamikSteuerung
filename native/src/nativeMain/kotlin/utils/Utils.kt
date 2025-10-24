package me.earzuchan.tetsu.native.utils

import kotlinx.cinterop.*
import platform.android.*
import kotlin.math.*

object Log {
    fun d(tag: String, msg: String) = __android_log_print(ANDROID_LOG_DEBUG.toInt(), tag, msg)

    fun e(tag: String, msg: String) = __android_log_print(ANDROID_LOG_ERROR.toInt(), tag, msg)
    fun e(tag: String, msg: String, e: Throwable) =
        __android_log_print(ANDROID_LOG_ERROR.toInt(), tag, "$msg\n${e.stackTraceToString()}")

    fun i(tag: String, msg: String) = __android_log_print(ANDROID_LOG_INFO.toInt(), tag, msg)
    fun w(tag: String, msg: String) = __android_log_print(ANDROID_LOG_WARN.toInt(), tag, msg)
}

@OptIn(ExperimentalForeignApi::class)
object JniUtils {
    private const val TAG = "JniUtils"

    fun CPointer<JavaVMVar>.isOk(): Boolean = memScoped {
        val envStorage = alloc<CPointerVar<JNIEnvVar>>()
        val vmValue = this@isOk.pointed.pointed!!

        val result = vmValue.GetEnv!!(this@isOk, envStorage.ptr.reinterpret(), JNI_VERSION_1_6)

        return result == JNI_OK
    }

    val Boolean.j: UByte get() = if (this) 1u else 0u

    // 定义一个接受 JNIEnv* 和 jstring 的 native 函数
    fun CPointer<JNIEnvVar>.getString(jStr: jstring?): String? {
        val nonNullJStr = jStr ?: return null.also { Log.w(TAG, "Input jStr was null") }
        val realEnv = pointed.pointed!!

        val utfChars = realEnv.GetStringUTFChars!!(this, nonNullJStr, null) ?: return null.also {
            Log.e(TAG, "GetStrUTF failed to return a valid pointer")
        }


        // 在 try 块中，安全地将 C 字符串转换为 Kotlin 字符串。
        val kStr = runCatching { utfChars.toKStringFromUtf8() }.onFailure {
            // 如果转换失败，记录详细的异常信息。
            Log.e(TAG, "Failed to convert UTF8 cStr to kStr", it)
        }.getOrNull()

        realEnv.ReleaseStringUTFChars!!(this, nonNullJStr, utfChars)

        return kStr
    }

    fun CPointer<JNIEnvVar>.getFloatArray(jArr: jfloatArray?): FloatArray {
        // 处理 null 输入，返回一个空数组，这比返回 null 更安全，避免了调用方的空检查
        val nonNullJArr = jArr ?: return floatArrayOf().also {
            Log.w(TAG, "Input jfloatArray was null, returning empty array")
        }

        // 获取实际的 JNIEnv 结构体指针
        val realEnv = pointed.pointed!!

        // 调用 JNI 函数获取指向数组元素的 C 指针
        // 第三个参数(isCopy)在这里我们不关心，传入 null 即可

        // 失败时返回空数组
        val elementsPtr = realEnv.GetFloatArrayElements!!.invoke(this, nonNullJArr, null)
            ?: return floatArrayOf().also { Log.e(TAG, "JNI GetFloatArrayElements failed to get pointer") }

        // 检查 JNI 调用是否失败 (例如，内存不足)
        // 使用 try...finally 确保资源总是被释放
        try {
            // 获取数组的长度
            val length = realEnv.GetArrayLength!!.invoke(this, nonNullJArr)
            if (length == 0) return floatArrayOf()

            // 创建一个 Kotlin FloatArray
            val kotlinArray = FloatArray(length)
            // 将数据从 C 指针复制到 Kotlin 数组
            for (i in 0 until length) kotlinArray[i] = elementsPtr[i]

            return kotlinArray
        } finally {
            //释放 C 指针
            // 因为我们只是读取数据，没有做任何修改，所以使用 JNI_ABORT 是最高效的。
            // 它告诉 JVM：“我没有修改任何东西，请直接释放内存，无需将数据复制回去。”
            realEnv.ReleaseFloatArrayElements!!.invoke(this, nonNullJArr, elementsPtr, JNI_ABORT)
        }
    }
}

/**
 * 完整的 K-weighting 实现
 */
class CompleteKWeighting(sampleRate: Int, channels: Int) {
    private val highpass = HighpassFilter(sampleRate, channels)
    private val shelf = HighFreqShelf(sampleRate, channels)

    fun process(input: FloatArray): FloatArray {
        val afterHighpass = highpass.process(input)
        return shelf.process(afterHighpass)
    }
}

/**
 * 高通滤波器
 */
class HighpassFilter(sampleRate: Int, private val channels: Int, freq: Float = 38f) {
    private val alpha = exp(-2f * PI * freq / sampleRate).toFloat() // 高通
    private val prevOutput = FloatArray(channels)
    private val prevInput = FloatArray(channels)

    fun process(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        val frames = input.size / channels

        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = frame * channels + ch
                val currentInput = input[idx]

                output[idx] = alpha * (prevOutput[ch] + currentInput - prevInput[ch])

                prevOutput[ch] = output[idx]
                prevInput[ch] = currentInput
            }
        }

        return output
    }
}

/**
 * 高频搁架滤波器
 */
class HighFreqShelf(
    sampleRate: Int, private val channels: Int,
    centerFreq: Float = 1500f, gainDb: Float = 4f
) {
    private val gainLinear = 10f.pow(gainDb / 20f)
    private val omega = 2f * PI * centerFreq / sampleRate
    private val cosOmega = cos(omega).toFloat()
    private val sinOmega = sin(omega).toFloat()

    // 计算双二阶滤波器系数
    private val A = gainLinear
    private val S = 1f
    private val beta = sqrt(A) / S

    private val b0 = A * ((A + 1f) + (A - 1f) * cosOmega + beta * sinOmega)
    private val b1 = -2f * A * ((A - 1f) + (A + 1f) * cosOmega)
    private val b2 = A * ((A + 1f) + (A - 1f) * cosOmega - beta * sinOmega)
    private val a0 = (A + 1f) - (A - 1f) * cosOmega + beta * sinOmega
    private val a1 = 2f * ((A - 1f) - (A + 1f) * cosOmega)
    private val a2 = (A + 1f) - (A - 1f) * cosOmega - beta * sinOmega

    // 归一化系数
    private val nb0 = b0 / a0
    private val nb1 = b1 / a0
    private val nb2 = b2 / a0
    private val na1 = a1 / a0
    private val na2 = a2 / a0

    // 历史样本
    private val x1 = FloatArray(channels)
    private val x2 = FloatArray(channels)
    private val y1 = FloatArray(channels)
    private val y2 = FloatArray(channels)

    fun process(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        val frames = input.size / channels

        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = frame * channels + ch
                val x0 = input[idx]

                // 双二阶滤波器方程
                val y0 = nb0 * x0 + nb1 * x1[ch] + nb2 * x2[ch] - na1 * y1[ch] - na2 * y2[ch]

                output[idx] = y0

                // 更新历史样本
                x2[ch] = x1[ch]
                x1[ch] = x0
                y2[ch] = y1[ch]
                y1[ch] = y0
            }
        }

        return output
    }
}