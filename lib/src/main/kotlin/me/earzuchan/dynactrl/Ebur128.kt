package me.earzuchan.dynactrl

import me.earzuchan.dynactrl.utils.CircularBuffer
import me.earzuchan.dynactrl.utils.CompleteKWeighting
import kotlin.math.*

/**
 * 轻量级的 EBU R128 实现
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