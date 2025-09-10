package me.earzuchan.dynactrl.native

import kotlinx.cinterop.*
import libsndfile.SF_INFO
import libsndfile.sf_close
import libsndfile.sf_open
import libsndfile.sf_read_float
import me.earzuchan.dynactrl.native.utils.Log
import kotlin.random.Random

class LightweightLoudnessAnalyzer {
    companion object {
        private const val TAG = "LoudnessAnalyzerNative"

        // 性能优化参数
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

            if (frames == 0L && sampleRate == 0) return (-70f).also { Log.e(TAG, "Not supported：$audioFilePath") }

            val channelCount = audioInfo.channels
            val totalSamples = frames * channelCount

            Log.d(TAG, "音频：${totalSamples}样本，${sampleRate}Hz，${channelCount}ch；超轻：$ultraLight")

            val loudnessCalculator = LightweightEbuR128(channelCount, sampleRate)

            val samples = FloatArray(totalSamples.toInt()) // 可能是这样

            samples.usePinned {
                val readCount = sf_read_float(audioFile, it.addressOf(0), totalSamples)
                Log.d(TAG, "读了：$readCount，本需要：$totalSamples")
            }

            /*// 能读到，不过是正负一吗？
            Log.d(TAG, "测试MAX：${samples.maxOrNull()}")*/

            // 减少样本
            val reducedSamples = if (ultraLight) reduceSamples(samples, channelCount) else samples
            Log.d(TAG, "处理：${reducedSamples.size}，原：${samples.size}")

            loudnessCalculator.addSamples(reducedSamples)
            val loudness = loudnessCalculator.getIntegratedLoudness()

            Log.d(TAG, "分析完成：$loudness LUFS")

            sf_close(audioFile)

            return loudness
        } catch (e: Exception) {
            Log.e(TAG, "分析失败", e)
            return -70f
        }
    }

    private fun reduceSamples(samples: FloatArray, channelCount: Int): FloatArray = when {
        samples.size < 100 -> samples // 样本太少就不减少了
        else -> randomSubsample(samples, channelCount)
    }

    private var randomSeed = Random(114514).nextLong()

    private fun randomSubsample(samples: FloatArray, channelCount: Int): FloatArray {
        val frames = samples.size / channelCount
        val keepFrames = (frames * ULTRA_LIGHT_SAMPLE_KEEP_RATIO).toInt()

        if (keepFrames >= frames) return samples

        val outputSize = keepFrames * channelCount
        val output = FloatArray(outputSize)
        var outputIndex = 0

        for (frame in 0 until frames) {
            // 简单的伪随机判断
            randomSeed = (randomSeed * 1103515245 + 12345) and 0x7FFFFFFF
            val randomValue = (randomSeed % 1000) / 1000f

            if (randomValue < ULTRA_LIGHT_SAMPLE_KEEP_RATIO && outputIndex < outputSize - channelCount)
                for (ch in 0 until channelCount) {
                    output[outputIndex] = samples[frame * channelCount + ch]
                    outputIndex++
                }
        }

        return output.copyOfRange(0, outputIndex)
    }
}