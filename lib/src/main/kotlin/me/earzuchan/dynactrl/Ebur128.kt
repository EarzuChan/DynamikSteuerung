package me.earzuchan.dynactrl

import me.earzuchan.dynactrl.utils.CircularBuffer
import kotlin.math.*

/**
 * 优化版的 EBU R128 实现
 */
class LightweightEbuR128(private val channels: Int, sampleRate: Int) {
    companion object {
        private const val TAG = "EbuR128"
        private const val ABSOLUTE_THRESHOLD_LUFS = -70f
        private const val RELATIVE_THRESHOLD_LU = -10f
        private const val BLOCK_SIZE_SEC = 0.4f // 400ms块
        private const val OVERLAP_RATIO = 0.75f // 75% 重叠
    }

    private val blockSize = (sampleRate * BLOCK_SIZE_SEC).toInt()
    private val hopSize = (blockSize * (1f - OVERLAP_RATIO)).toInt()
    private val absoluteThresholdEnergy = 10f.pow((ABSOLUTE_THRESHOLD_LUFS + 0.691f) / 10f)

    // 使用环形缓冲区提高效率
    private val circularBuffer = CircularBuffer(blockSize * channels * 2)
    private val kWeighting = CompleteKWeighting(sampleRate, channels)
    private val blockEnergies = mutableListOf<Float>()

    fun addSamples(samples: FloatArray) {
        if (samples.isEmpty()) return

        // 应用完整的 K-weighting
        val weighted = kWeighting.process(samples)
        circularBuffer.addAll(weighted)

        // 处理完整的块
        while (circularBuffer.size >= blockSize * channels) {
            val blockEnergy = calculateBlockEnergy()
            if (blockEnergy > absoluteThresholdEnergy) blockEnergies.add(blockEnergy)

            // 移除 hop size 的样本
            circularBuffer.removeFirst(hopSize * channels)
        }
    }

    private fun calculateBlockEnergy(): Float {
        var energy = 0f
        val totalSamples = blockSize * channels

        for (i in 0 until totalSamples) {
            val sample = circularBuffer.get(i)
            energy += sample * sample
        }

        return energy / (blockSize * channels)
    }

    fun getIntegratedLoudness(): Float {
        if (blockEnergies.size < 2) return Float.NEGATIVE_INFINITY

        // 相对门控
        val meanEnergy = blockEnergies.average().toFloat()
        val relativeThreshold = meanEnergy * 10f.pow(RELATIVE_THRESHOLD_LU / 10f)

        val gatedEnergies = blockEnergies.filter { it >= relativeThreshold }
        if (gatedEnergies.isEmpty()) return Float.NEGATIVE_INFINITY

        val gatedMeanEnergy = gatedEnergies.average().toFloat()

        // 重要：恢复标准的 -0.691f 校准值
        return 10f * log10(gatedMeanEnergy) - 0.691f
    }
}

/**
 * 完整的 K-weighting 实现
 */
class CompleteKWeighting(sampleRate: Int, channels: Int) {
    private val highpass = SimpleHighpass(sampleRate, channels)
    private val shelf = HighFreqShelf(sampleRate, channels)

    fun process(input: FloatArray): FloatArray {
        val afterHighpass = highpass.process(input)
        return shelf.process(afterHighpass)
    }
}

/**
 * 高通滤波器（K-weighting 的一部分）
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

/**
 * 高频搁架滤波器（K-weighting 的另一部分）
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