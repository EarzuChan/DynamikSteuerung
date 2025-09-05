package me.earzuchan.dynactrl.exoplayer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.nio.ByteBuffer
import kotlin.math.pow

@OptIn(UnstableApi::class)
class DynamicsProcessor : BaseAudioProcessor() {
    companion object {
        private const val TAG = "DynamicsProcessor"
        private const val TARGET_LUFS = -14f // Spotify标准
        private const val LIMITER_THRESHOLD = 0.95f // 限制器阈值
        private const val LIMITER_ATTACK_TIME = 0.001f // 1ms攻击时间
        private const val LIMITER_RELEASE_TIME = 0.01f // 10ms释放时间
    }

    private var currentLoudnessInfo: AudioLoudnessInfo? = null
    private var gainScale = 1.0f
    private var sampleRate = 44100
    private var channelCount = 2
    private var bytesPerSample = 2 // 16-bit

    // 限制器状态
    private var limiterGainReduction = 1.0f
    private var attackCoeff = 0.0f
    private var releaseCoeff = 0.0f

    // 设置当前音轨的响度信息，以计算处理（增益和限制）参数
    fun setCurrentTrackLoudness(loudnessInfo: AudioLoudnessInfo) {
        currentLoudnessInfo = loudnessInfo
        calculateGainScale()
    }

    // 根据响度信息计算增益参数
    private fun calculateGainScale() {
        val loudnessInfo = currentLoudnessInfo ?: return
        val lufs = loudnessInfo.lufs

        gainScale = (if (lufs.isNaN() || lufs.isInfinite() || lufs < -70f) 1.0f
        else 10.0.pow((TARGET_LUFS - lufs) / 20.0).toFloat())

        Log.d(TAG, "Track LUFS: $lufs, Calculated gain scale: $gainScale")
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        // 计算限制器系数
        attackCoeff = kotlin.math.exp(-1.0 / (LIMITER_ATTACK_TIME * sampleRate)).toFloat()
        releaseCoeff = kotlin.math.exp(-1.0 / (LIMITER_RELEASE_TIME * sampleRate)).toFloat()

        Log.d(
            TAG,
            "Configured: ${inputAudioFormat.sampleRate}Hz, ${inputAudioFormat.channelCount}ch, ${inputAudioFormat.encoding}"
        )

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)

        when (bytesPerSample) {
            2 -> processInt16(inputBuffer, outputBuffer)

            4 -> processFloat32(inputBuffer, outputBuffer)

            // 对于其他格式，直接复制（不处理） TODO：或有问题
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    // 处理16位整数PCM数据
    private fun processInt16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val samplesCount = inputBuffer.remaining() / 2

        // CHECK：好像有超采样？
        repeat(samplesCount) {
            // 读取16位样本
            val sample = inputBuffer.short.toFloat() / Short.MAX_VALUE

            // 应用增益
            var processedSample = sample * gainScale

            // 应用限制器
            processedSample = applyLimiter(processedSample)

            // 转换回16位并写入输出
            val outputSample = (processedSample * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            outputBuffer.putShort(outputSample.toShort())
        }
    }

    // 处理32位浮点PCM数据
    private fun processFloat32(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val samplesCount = inputBuffer.remaining() / 4

        repeat(samplesCount) {
            // 读取浮点样本
            var sample = inputBuffer.float

            // 应用增益
            sample *= gainScale

            // 应用限制器
            sample = applyLimiter(sample)

            outputBuffer.putFloat(sample)
        }
    }

    // 应用限制器处理
    private fun applyLimiter(sample: Float): Float {
        val absSample = kotlin.math.abs(sample)

        if (absSample > LIMITER_THRESHOLD) {
            // 计算需要的增益衰减
            val targetGain = LIMITER_THRESHOLD / absSample

            // 平滑增益变化
            limiterGainReduction =
                if (targetGain < limiterGainReduction) targetGain + (limiterGainReduction - targetGain) * attackCoeff
                else targetGain + (limiterGainReduction - targetGain) * releaseCoeff
        } else limiterGainReduction = 1.0f + (limiterGainReduction - 1.0f) * releaseCoeff // 释放增益衰减

        return sample * limiterGainReduction
    }

    override fun isActive(): Boolean = currentLoudnessInfo != null && gainScale != 1.0f

    override fun onFlush() {
        // 重置限制器状态
        limiterGainReduction = 1.0f
    }

    override fun onReset() {
        // 重置所有状态
        currentLoudnessInfo = null
        gainScale = 1.0f

        limiterGainReduction = 1.0f
    }
}