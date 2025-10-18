package me.earzuchan.dynactrl.native

import me.earzuchan.dynactrl.native.utils.CompleteKWeighting
import me.earzuchan.dynactrl.native.utils.Log
import kotlin.math.log10
import kotlin.math.pow

/**
 * 轻量级的 EBU R128 实现 - 改进版
 */
class LightweightEbuR128(private val channels: Int, sampleRate: Int) {
    private companion object {
        const val TAG = "EbuR128Native"
        const val ABSOLUTE_THRESHOLD_LUFS = -70f
        const val RELATIVE_THRESHOLD_LU = -10f
        const val BLOCK_SIZE_SEC = 0.4f // 400ms块
        const val OVERLAP_RATIO = 0.75f // 75% 重叠
    }

    private val blockSize = (sampleRate * BLOCK_SIZE_SEC).toInt()
    private val hopSize = (blockSize * (1f - OVERLAP_RATIO)).toInt()
    private val absoluteThresholdEnergy = 10f.pow((ABSOLUTE_THRESHOLD_LUFS + 0.691f) / 10f)
    private val kWeighting = CompleteKWeighting(sampleRate, channels)
    private val blockEnergies = mutableListOf<Float>()

    // 内部缓冲区，只存储处理所需的最小数据
    private var sampleBuffer = FloatArray(0)
    private var bufferUsed = 0
    private var finalized = false

    /**
     * 添加音频样本 - 内部自动处理分批
     * @param samples 任意大小的样本数组
     */
    fun addSamples(samples: FloatArray) {
        if (samples.isEmpty()) return

        finalized = false // 重置finalized状态

        // 应用K-weighting
        val weighted = kWeighting.process(samples)

        // 内部分批处理
        processSamplesInBatches(weighted)
    }

    private fun processSamplesInBatches(samples: FloatArray) {
        val samplesPerBlock = blockSize * channels
        val hopSamples = hopSize * channels

        var inputOffset = 0

        while (inputOffset < samples.size) {
            // 确保缓冲区足够大
            ensureBufferCapacity(samplesPerBlock)

            // 计算这次可以复制多少样本
            val spaceInBuffer = sampleBuffer.size - bufferUsed
            val remainingInput = samples.size - inputOffset
            val samplesToCopy = minOf(spaceInBuffer, remainingInput)

            // 复制样本到缓冲区
            samples.copyInto(sampleBuffer, bufferUsed, inputOffset, inputOffset + samplesToCopy)
            bufferUsed += samplesToCopy
            inputOffset += samplesToCopy

            // 处理完整的blocks
            while (bufferUsed >= samplesPerBlock) {
                val blockEnergy = calculateBlockEnergy()
                if (blockEnergy > absoluteThresholdEnergy) {
                    blockEnergies.add(blockEnergy)
                }

                // 移动数据，移除hop size的样本
                val remainingSamples = bufferUsed - hopSamples
                if (remainingSamples > 0) sampleBuffer.copyInto(
                    sampleBuffer, 0,
                    hopSamples, hopSamples + remainingSamples
                )
                bufferUsed = maxOf(0, remainingSamples)
            }
        }
    }

    private fun ensureBufferCapacity(requiredSize: Int) {
        if (sampleBuffer.size < requiredSize) {
            val newBuffer = FloatArray(requiredSize)
            if (bufferUsed > 0) sampleBuffer.copyInto(newBuffer, endIndex = bufferUsed)
            sampleBuffer = newBuffer
        }
    }

    private fun calculateBlockEnergy(): Float {
        var energy = 0f
        val samplesInBlock = blockSize * channels

        for (i in 0 until samplesInBlock) {
            val sample = sampleBuffer[i]
            energy += sample * sample
        }

        return energy / samplesInBlock
    }

    /**
     * 获取积分响度 - 自动处理剩余样本
     */
    fun getIntegratedLoudness(): Float {
        // 自动finalize
        if (!finalized) autoFinalize()

        if (blockEnergies.size < 2) return ABSOLUTE_THRESHOLD_LUFS.also { Log.e(TAG, "能量块儿太少") }

        // 相对门控
        val meanEnergy = blockEnergies.average().toFloat()
        val relativeThreshold = meanEnergy * 10f.pow(RELATIVE_THRESHOLD_LU / 10f)

        val gatedEnergies = blockEnergies.filter { it >= relativeThreshold }
        if (gatedEnergies.isEmpty()) return ABSOLUTE_THRESHOLD_LUFS.also { Log.e(TAG, "不响啊，很不响啊") }

        val gatedMeanEnergy = gatedEnergies.average().toFloat()

        return 10f * log10(gatedMeanEnergy) - 0.691f
    }

    private fun autoFinalize() {
        // 处理剩余样本（如果足够组成一个完整block）
        val samplesPerBlock = blockSize * channels
        if (bufferUsed >= samplesPerBlock) {
            val blockEnergy = calculateBlockEnergy()
            if (blockEnergy > absoluteThresholdEnergy) blockEnergies.add(blockEnergy)
        }

        finalized = true
    }
}