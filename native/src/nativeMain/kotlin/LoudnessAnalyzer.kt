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

class LightweightLoudnessAnalyzer {
    companion object {
        private const val TAG = "LoudnessAnalyzer"

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
        val audioFile = sf_open(audioFilePath, 10 /*READ*/, audioInfo.ptr)

        try {
            // 获取音频参数
            val frames = audioInfo.frames
            val sampleRate = audioInfo.samplerate
            val oriChannelCount = audioInfo.channels

            val totalSamples = frames * sampleRate

            // TODO：超轻模式：强制单声道处理
            // val newChannelCount = if (ultraLightMode) 1 else oriChannelCount

            Log.d(TAG, "音频：${sampleRate}Hz，${oriChannelCount}ch；超轻：$ultraLight")

            val loudnessCalculator = LightweightEbuR128(oriChannelCount, sampleRate)

            val samples = FloatArray(totalSamples.toInt())
            samples.usePinned {
                val readCount = sf_read_float(audioFile, it.addressOf(0), totalSamples)
                Log.d(TAG, "读了：${readCount.toInt()}，本需要：$totalSamples")
            }

            // TODO：调用掐头去尾和减少样本

            // 计算最终响度
            loudnessCalculator.addSamples(samples)
            val loudness = loudnessCalculator.getIntegratedLoudness()

            Log.d(
                TAG,
                "Analysis complete: $loudness LUFS"
            )

            sf_close(audioFile)

            return loudness
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing file", e)
            return -70f
        }
    }

    /**
     * 超轻模式样本减少策略
     */
    private fun reduceSamples(samples: FloatArray, channelCount: Int): FloatArray = when {
        samples.size < 100 -> samples // 样本太少就不减少了
        else -> {
            // 方法1：随机抽样（推荐，统计特性最好）
            // randomSubsample(samples, channelCount, ULTRA_LIGHT_SAMPLE_KEEP_RATIO)

            // 方法2：均匀抽样（性能最好）
            // uniformSubsample(samples, channelCount, ULTRA_LIGHT_UNIFORM_SKIP)

            // 方法3：伪随机抽样（性能和统计特性的平衡）
            pseudoRandomSubsample(samples, channelCount)
        }
    }

    /**
     * 方法3：伪随机抽样 - 性能和随机性的平衡
     * 使用简单的线性同余生成器避免Random类的开销
     */
    private var pseudoRandomSeed = 1145141919 and 0xFFFFFF

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