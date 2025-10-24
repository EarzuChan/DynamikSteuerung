package me.earzuchan.tetsu.native

import libsndfile.SF_INFO
import libsndfile.sf_close
import libsndfile.sf_open
import libsndfile.sf_read_float
import me.earzuchan.tetsu.native.utils.Log
import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned

class AudioData(
    val samples: FloatArray,
    val sampleRate: Int,
    val channelCount: Int
)

object LightweightLoudnessAnalyzer {
    private const val TAG = "LoudnessAnalyzerNative"

    // 性能优化参数
    private const val ULTRA_LIGHT_SAMPLE_KEEP_RATIO = 0.6f // 超轻模式保留60%的样本

    private var randomSeed = Random(114514).nextLong()

    /**
     * 用于封装音频解码结果的数据类
     */


    /**
     * 公共入口函数，协调解码和分析两个步骤。
     * 这是对外的推荐使用方式。
     *
     * @param audioFilePath 音频文件路径
     * @param ultraLight 是否启用超轻模式（减少采样）
     * @return 计算出的响度值 (LUFS)，失败时返回 -70f
     */
    fun analyzeFile(audioFilePath: String, ultraLight: Boolean = true): Float {
        // 步骤 1: 解码音频文件
        val audioData = decodeAudioFile(audioFilePath)
        if (audioData == null) {
            Log.e(TAG, "解码失败或文件不支持: $audioFilePath")
            return -70f // 解码失败，返回默认错误值
        }

        // 步骤 2: 使用解码后的数据进行响度计算
        return calculateLoudness(audioData, ultraLight)
    }

    /**
     * [拆分部分1]
     * 仅负责解码音频文件，将其转换为样本数组和元数据。
     * 这个函数处理所有I/O和C-interop相关的操作。
     *
     * @param audioFilePath 要解码的音频文件路径
     * @return 如果成功，返回一个包含样本和元数据的 AudioData 对象；否则返回 null。
     */
    @OptIn(ExperimentalForeignApi::class)
    fun decodeAudioFile(audioFilePath: String): AudioData? = memScoped {
        val audioInfo = alloc<SF_INFO>()
        val audioFile = sf_open(audioFilePath, 0x10 /* SFM_READ */, audioInfo.ptr)

        // 使用 try-finally 确保 sf_close 一定会被调用
        try {
            if (audioFile == null) {
                Log.e(TAG, "无法打开文件: $audioFilePath")
                return null
            }

            // 获取音频参数
            val frames = audioInfo.frames
            val sampleRate = audioInfo.samplerate
            val channelCount = audioInfo.channels

            if (frames == 0L || sampleRate == 0 || channelCount == 0) {
                Log.e(TAG, "文件格式不支持或文件为空: $audioFilePath")
                return null
            }

            val totalSamples = frames * channelCount
            Log.d(TAG, "解码中：${totalSamples}个样本，${sampleRate}Hz，${channelCount}ch")

            val samples = FloatArray(totalSamples.toInt())
            samples.usePinned { pinnedSamples ->
                val readCount = sf_read_float(audioFile, pinnedSamples.addressOf(0), totalSamples)
                Log.d(TAG, "读取了: $readCount 样本, 预期: $totalSamples")

                // 不过好像并无什么不行
                if (readCount != totalSamples) Log.d(TAG, "实际读取的样本数与预期不符！")
            }

            return AudioData(samples, sampleRate, channelCount)
        } catch (e: Exception) {
            Log.e(TAG, "解码过程中发生未知错误", e)
            return null
        } finally {
            // 确保无论成功还是失败，文件句柄都会被关闭
            audioFile?.let { sf_close(it) }
        }
    }

    /**
     * [拆分部分2]
     * 接收解码后的音频数据，进行响度计算。
     * 这个函数是纯计算逻辑，不涉及任何I/O。
     *
     * @param audioData 包含样本、采样率和声道数的 AudioData 对象
     * @param ultraLight 是否启用超轻模式（减少采样）
     * @return 计算出的响度值 (LUFS)
     */
    fun calculateLoudness(audioData: AudioData, ultraLight: Boolean = true): Float {
        try {
            Log.d(
                TAG,
                "开始计算：${audioData.samples.size}个样本，${audioData.sampleRate}Hz，${audioData.channelCount}ch；超轻模式：$ultraLight"
            )

            val loudnessCalculator = LightweightEbuR128(audioData.channelCount, audioData.sampleRate)

            // 根据模式决定是否减少样本
            val samplesToProcess = if (ultraLight) reduceSamples(audioData.samples, audioData.channelCount) else audioData.samples
            Log.d(TAG, "处理样本数：${samplesToProcess.size}，原始样本数：${audioData.samples.size}")

            loudnessCalculator.addSamples(samplesToProcess)

            val loudness = loudnessCalculator.getIntegratedLoudness()

            Log.d(TAG, "分析完成: $loudness LUFS")
            return loudness
        } catch (e: Exception) {
            Log.e(TAG, "响度计算失败", e)
            return -70f // 计算过程出错，返回默认错误值
        }
    }

    /**
     * 私有辅助函数，用于根据超轻模式减少样本数量。
     */
    private fun reduceSamples(samples: FloatArray, channelCount: Int): FloatArray = when {
        samples.size < 100 -> samples // 样本太少就不减少了
        else -> randomSubsample(samples, channelCount)
    }

    /**
     * 私有辅助函数，执行随机二次采样。
     */
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

            if (randomValue < ULTRA_LIGHT_SAMPLE_KEEP_RATIO && outputIndex < outputSize)
                for (ch in 0 until channelCount) {
                    if (outputIndex < outputSize) { // 再次检查边界，防止溢出
                        output[outputIndex] = samples[frame * channelCount + ch]
                        outputIndex++
                    }
                }
        }

        return output.copyOfRange(0, outputIndex)
    }
}