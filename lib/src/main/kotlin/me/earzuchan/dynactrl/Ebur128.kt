package me.earzuchan.dynactrl

import android.util.Log
import kotlin.math.*

/**
 * 超轻量级的EBU R128实现
 */
class LightweightEbuR128(private val channels: Int, sampleRate: Int) {
    companion object {
        private const val TAG = "EbuR128"
        private const val ABSOLUTE_THRESHOLD_LUFS = -70f
        private const val RELATIVE_THRESHOLD_LU = -10f
        private const val BLOCK_SIZE_SEC = 0.4f // 400ms块
    }

    private val blockSize = (sampleRate * BLOCK_SIZE_SEC).toInt()
    private val absoluteThresholdEnergy = 10f.pow((ABSOLUTE_THRESHOLD_LUFS + 0.691f) / 10f)

    // 简化的K-weighting：只是一个简单的高通滤波器
    private val highpassFilter = SimpleHighpass(sampleRate, channels)
    private val blockEnergies = mutableListOf<Float>()
    private val sampleBuffer = mutableListOf<Float>()

    fun addSamples(samples: FloatArray) {
        Log.d(TAG, "添加采样：${samples.size}")

        // 应用简化的K-weighting
        val filtered = highpassFilter.process(samples)
        sampleBuffer.addAll(filtered.toTypedArray())

        // 处理完整的块
        while (sampleBuffer.size >= blockSize * channels) {
            val blockEnergy = calculateBlockEnergy()
            if (blockEnergy > absoluteThresholdEnergy) blockEnergies.add(blockEnergy)

            // 移除已处理的样本（保留50%重叠）
            val removeCount = blockSize * channels / 2
            repeat(removeCount) { sampleBuffer.removeFirstOrNull() }
        }
    }

    private fun calculateBlockEnergy(): Float {
        var energy = 0f
        val framesToProcess = minOf(blockSize, sampleBuffer.size / channels)

        for (frame in 0 until framesToProcess) for (ch in 0 until channels) {
            val sample = sampleBuffer[frame * channels + ch]
            energy += sample * sample
        }

        return energy / framesToProcess
    }

    fun getIntegratedLoudness(): Float {
        if (blockEnergies.size < 2) return Float.NEGATIVE_INFINITY

        // 相对门控
        val meanEnergy = blockEnergies.average().toFloat()
        val relativeThreshold = meanEnergy * 10f.pow(RELATIVE_THRESHOLD_LU / 10f)

        val gatedEnergies = blockEnergies.filter { it >= relativeThreshold }
        if (gatedEnergies.isEmpty()) return Float.NEGATIVE_INFINITY

        val gatedMeanEnergy = gatedEnergies.average().toFloat()
        return 10f * log10(gatedMeanEnergy) // - 0.691f <- 这是什么魔数？求解答
    }
}

/**
 * 超简单的高通滤波器（K-weighting近似）
 */
class SimpleHighpass(sampleRate: Int, private val channels: Int) {
    private val alpha = exp(-2f * PI * 38f / sampleRate).toFloat() // 38Hz 高通
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