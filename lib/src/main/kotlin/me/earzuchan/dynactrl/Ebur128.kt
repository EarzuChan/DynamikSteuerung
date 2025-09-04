package me.earzuchan.dynactrl

import kotlin.math.*

/**
 * 简化的EBU R128实现，专注于正确性
 */
class EbuR128Analyzer(private val channels: Int, sampleRate: Int) {
    companion object {
        private const val ABSOLUTE_THRESHOLD = -70.0 // LUFS
        private const val RELATIVE_THRESHOLD_OFFSET = -10.0 // LU
        private const val BLOCK_SIZE_MS = 400.0 // 毫秒
    }

    private val blockSize = (sampleRate * BLOCK_SIZE_MS / 1000.0).toInt()
    private val overlap = blockSize * 3 / 4 // 75% overlap

    // K-weighting 滤波器 (简化实现)
    private val preFilter = KWeightingFilter(sampleRate, channels)

    // 存储所有块的能量值用于门控
    private val blockEnergies = mutableListOf<Double>()

    // 累积音频样本
    private val audioBuffer = mutableListOf<Float>()

    fun addSamples(samples: FloatArray, frames: Int) {
        // 应用K-weighting滤波器
        val filteredSamples = preFilter.process(samples, frames)

        // 添加到缓冲区
        for (i in 0 until frames * channels) audioBuffer.add(filteredSamples[i])

        // 处理完整的块
        processBlocks()
    }

    private fun processBlocks() {
        val totalFrames = audioBuffer.size / channels
        var frameOffset = 0

        while (frameOffset + blockSize <= totalFrames) {
            val blockEnergy = calculateBlockEnergy(frameOffset, blockSize)
            if (blockEnergy > absoluteThresholdEnergy()) blockEnergies.add(blockEnergy)
            frameOffset += overlap
        }

        // 保留最后一个块的数据
        if (frameOffset > 0) {
            val keepFrames = totalFrames - frameOffset
            if (keepFrames > 0) {
                val keepSamples = mutableListOf<Float>()
                for (i in frameOffset * channels until audioBuffer.size) keepSamples.add(audioBuffer[i])

                audioBuffer.clear()
                audioBuffer.addAll(keepSamples)
            } else audioBuffer.clear()
        }
    }

    private fun calculateBlockEnergy(frameOffset: Int, numFrames: Int): Double {
        var energy = 0.0

        for (frame in 0 until numFrames) {
            for (ch in 0 until channels) {
                val sampleIndex = (frameOffset + frame) * channels + ch
                if (sampleIndex < audioBuffer.size) {
                    val sample = audioBuffer[sampleIndex].toDouble()
                    energy += sample * sample
                }
            }
        }

        return energy / numFrames
    }

    private fun absoluteThresholdEnergy(): Double = 10.0.pow((ABSOLUTE_THRESHOLD + 0.691) / 10.0)

    fun getIntegratedLoudness(): Float {
        if (blockEnergies.isEmpty()) return Float.NEGATIVE_INFINITY

        // 第一轮门控：绝对阈值已在添加时应用

        // 第二轮门控：相对阈值
        val meanEnergy = blockEnergies.average()
        val relativeThreshold = meanEnergy * 10.0.pow(RELATIVE_THRESHOLD_OFFSET / 10.0)

        val gatedEnergies = blockEnergies.filter { it >= relativeThreshold }
        if (gatedEnergies.isEmpty()) return Float.NEGATIVE_INFINITY

        val gatedMeanEnergy = gatedEnergies.average()
        val lufs = 10.0 * log10(gatedMeanEnergy) - 0.691

        return lufs.toFloat()
    }
}

/**
 * 简化的K-weighting滤波器
 */
class KWeightingFilter(private val sampleRate: Int, private val channels: Int) {
    // 预滤波器系数 (简化的高通+高频提升滤波器)
    private val b = doubleArrayOf(1.0, -2.0, 1.0) // 高通
    private val a = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621)

    // 每个声道的滤波器状态
    private val filterState = Array(channels) { DoubleArray(3) }

    fun process(input: FloatArray, frames: Int): FloatArray {
        val output = FloatArray(frames * channels)

        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val inputIndex = frame * channels + ch
                val sample = input[inputIndex].toDouble()

                // 应用IIR滤波器
                val state = filterState[ch]

                // 输入延迟线
                state[0] = sample

                // 计算输出
                val filteredSample = b[0] * state[0] + b[1] * state[1] + b[2] * state[2] -
                        a[1] * state[1] - a[2] * state[2]

                // 更新状态
                state[2] = state[1]
                state[1] = filteredSample

                output[inputIndex] = filteredSample.toFloat()
            }
        }

        return output
    }
}