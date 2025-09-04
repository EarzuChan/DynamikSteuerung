package me.earzuchan.dynactrl.utils

import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 环形缓冲区
 */
class CircularBuffer(private val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private var head = 0
    private var tail = 0
    private var currentSize = 0

    val size: Int get() = currentSize

    fun addAll(samples: FloatArray) {
        for (sample in samples) add(sample)
    }

    fun add(sample: Float) {
        if (currentSize < capacity) {
            buffer[tail] = sample
            tail = (tail + 1) % capacity
            currentSize++
        } else {
            // 缓冲区满了，覆盖最老的数据
            buffer[tail] = sample
            tail = (tail + 1) % capacity
            head = (head + 1) % capacity
        }
    }

    fun removeFirst(count: Int) {
        val actualCount = minOf(count, currentSize)
        head = (head + actualCount) % capacity
        currentSize -= actualCount
    }

    fun get(index: Int): Float {
        if (index >= currentSize) throw IndexOutOfBoundsException()
        return buffer[(head + index) % capacity]
    }
}

/**
 * 对象池减少内存分配
 */
object BufferPool {
    private const val MAX_POOL_SIZE = 10
    private val floatBuffers = ArrayDeque<FloatArray>(MAX_POOL_SIZE)

    fun borrowFloatArray(size: Int): FloatArray = synchronized(floatBuffers) {
        return runCatching { floatBuffers.removeFirst().takeIf { it.size >= size } }.getOrNull()
            ?: FloatArray(size)
    }

    fun returnFloatArray(array: FloatArray) = synchronized(floatBuffers) {
        if (floatBuffers.size < MAX_POOL_SIZE) floatBuffers.addLast(array)
    }
}

// TODO：这有提升性能的地方吗
/**
 * 带抗混叠的降采样器
 */
class AntiAliasingDownsampler(
    private val ratio: Int, private val channels: Int, sampleRate: Int
) {
    private val lowpassFilter = LowpassFilter(
        sampleRate = sampleRate,
        channels = channels, // 留点余量
        freq = sampleRate.toFloat() / (2 * ratio * 1.1f)
    )

    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return floatArrayOf()

        // 先低通滤波防混叠
        val filtered = lowpassFilter.process(input)

        // 然后降采样
        val frames = filtered.size / channels
        val outputFrames = frames / ratio
        val output = FloatArray(outputFrames * channels)

        for (frame in 0 until outputFrames) {
            val sourceFrame = frame * ratio
            for (ch in 0 until channels) output[frame * channels + ch] = filtered[sourceFrame * channels + ch]
        }

        return output
    }
}

/**
 * 完整的 K-weighting 实现
 */
class CompleteKWeighting(sampleRate: Int, channels: Int) {
    private val highpass = HighpassFilter(sampleRate, channels)
    private val shelf = HighFreqShelf(sampleRate, channels)

    fun process(input: FloatArray): FloatArray {
        val afterHighpass = highpass.process(input)
        return shelf.process(afterHighpass)
    }
}

/**
 * 低通滤波器
 */
class LowpassFilter(
    sampleRate: Int,
    private val channels: Int,
    freq: Float
) {
    private val alpha = exp(-2f * PI * freq / sampleRate).toFloat()
    private val prevOutput = FloatArray(channels)

    fun process(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        val frames = input.size / channels

        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val idx = frame * channels + ch
                output[idx] = alpha * prevOutput[ch] + (1f - alpha) * input[idx]
                prevOutput[ch] = output[idx]
            }
        }

        return output
    }
}

/**
 * 高通滤波器
 */
class HighpassFilter(sampleRate: Int, private val channels: Int, freq: Float = 38f) {
    private val alpha = exp(-2f * PI * freq / sampleRate).toFloat() // 高通
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
 * 高频搁架滤波器
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