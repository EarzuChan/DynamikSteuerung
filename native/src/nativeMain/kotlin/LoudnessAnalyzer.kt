package me.earzuchan.dynactrl.native

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import libsndfile.SF_INFO
import libsndfile.sf_close
import libsndfile.sf_open
import libsndfile.sf_read_float
import me.earzuchan.dynactrl.native.utils.Log
import platform.posix.loff_tVar
import kotlin.random.Random
import kotlin.random.nextLong

class LightweightLoudnessAnalyzer {
    companion object {
        private const val TAG = "LnsAnalNative"

        // 性能优化参数
        private const val TIMEOUT_US = 10_000L // 10ms 超时
        private const val BUFFER_SIZE = 4096 // 缓冲区大小
        private const val MAX_ANALYSIS_DURATION_US = 180_000_000L // 最多分析180秒
        private const val ULTRA_LIGHT_MAX_ANALYSIS_DURATION_US = 120_000_000L // 超轻模式最多分析120秒
        private const val SKIP_HEAD_RATIO = 0.05f // 跳过前5%
        private const val ULTRA_LIGHT_SKIP_HEAD_RATIO = 0.15f // 超轻模式跳过前15%
        private const val SKIP_TAIL_RATIO = 0.05f // 跳过后5%
        private const val ULTRA_LIGHT_SKIP_TAIL_RATIO = 0.15f // 超轻模式跳过后15%
        private const val ULTRA_LIGHT_FRAME_SKIP_RATIO = 3 // 超轻模式每3帧处理1帧
        private const val ULTRA_LIGHT_SAMPLE_KEEP_RATIO = 0.6f // 超轻模式保留60%的样本
    }

    @OptIn(ExperimentalForeignApi::class)
    fun analyzeFile(audioFilePath: String, ultraLight: Boolean = true): Float = memScoped {
        val audioInfo = alloc<SF_INFO>()
        val audioFile = sf_open(audioFilePath, 0x10 /*READ*/, audioInfo.ptr)

        try {
            // 获取音频参数
            val frames = audioInfo.frames
            val sampleRate = audioInfo.samplerate
            val oriChannelCount = audioInfo.channels

            val totalSamples = frames * oriChannelCount

            // TODO：超轻模式：强制单声道处理
            // val newChannelCount = if (ultraLightMode) 1 else oriChannelCount

            Log.d(TAG, "音频：${totalSamples}样本，${sampleRate}Hz，${oriChannelCount}ch；超轻：$ultraLight")

            val loudnessCalculator = LightweightEbuR128(oriChannelCount, sampleRate)

            val samples = FloatArray(totalSamples.toInt()) // 可能是这样

            samples.usePinned {
                val readCount = sf_read_float(audioFile, it.addressOf(0), totalSamples)
                Log.d(TAG, "读了：$readCount，本需要：$totalSamples")
            }

            // 能读到，不过是正负一吗？
            Log.d(TAG,"测试MAX：${samples.maxOrNull()}")

            // TODO：调用掐头去尾和减少样本

            loudnessCalculator.addSamples(samples)
            val loudness = loudnessCalculator.getIntegratedLoudness()

            Log.d(TAG, "Analysis complete: $loudness LUFS")

            sf_close(audioFile)

            return loudness
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing file", e)
            return -70f
        }
    }

    private fun reduceSamples(samples: FloatArray, channelCount: Int): FloatArray = when {
        samples.size < 100 -> samples // 样本太少就不减少了
        else -> pseudoRandomSubsample(samples, channelCount)
    }

    private var pseudoRandomSeed = Random(114514).nextLong()

    private fun pseudoRandomSubsample(samples: FloatArray, channelCount: Int): FloatArray {
        val frames = samples.size / channelCount
        val keepFrames = (frames * ULTRA_LIGHT_SAMPLE_KEEP_RATIO).toInt()

        if (keepFrames >= frames) return samples

        val outputSize = keepFrames * channelCount
        val output = FloatArray(outputSize)
        var outputIndex = 0

        for (frame in 0 until frames) {
            // 简单的伪随机判断
            pseudoRandomSeed = (pseudoRandomSeed * 1103515245 + 12345) and 0x7FFFFFFF
            val randomValue = (pseudoRandomSeed % 1000) / 1000f

            if (randomValue < ULTRA_LIGHT_SAMPLE_KEEP_RATIO && outputIndex < outputSize - channelCount)
                for (ch in 0 until channelCount) {
                    output[outputIndex] = samples[frame * channelCount + ch]
                    outputIndex++
                }
        }

        return output.copyOfRange(0, outputIndex)
    }

    // 将多声道转换为单声道（取平均值）
    private fun convertToMono(input: FloatArray, channelCount: Int, outputBuffer: FloatArray): FloatArray {
        val frames = input.size / channelCount
        if (frames <= 0) return floatArrayOf()

        val actualOutputSize = minOf(frames, outputBuffer.size)

        for (frame in 0 until actualOutputSize) {
            var sum = 0f
            for (ch in 0 until channelCount) sum += input[frame * channelCount + ch]
            outputBuffer[frame] = sum / channelCount
        }

        return outputBuffer.copyOfRange(0, actualOutputSize)
    }
}