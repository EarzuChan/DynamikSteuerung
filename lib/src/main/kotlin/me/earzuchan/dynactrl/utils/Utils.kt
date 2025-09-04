package me.earzuchan.dynactrl.utils

import java.util.*

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
    private val floatBuffers = ArrayDeque<FloatArray>(10)
    private const val maxPoolSize = 10

    fun borrowFloatArray(size: Int): FloatArray = synchronized(floatBuffers) {
        return runCatching { floatBuffers.removeFirst().takeIf { it.size >= size } }.getOrNull()
            ?: FloatArray(size)
    }

    fun returnFloatArray(array: FloatArray) = synchronized(floatBuffers) {
        if (floatBuffers.size < maxPoolSize) floatBuffers.addLast(array)
    }
}