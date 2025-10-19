package me.earzuchan.dynactrl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

object LightweightLoudnessAnalyzer {
    private const val TAG = "LoudnessAnalyzer"

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        val filePath = audioFile.absolutePath

        val result = if (filePath.endsWith(".m4a")) {
            Log.w(TAG, "走备用分析器")

            FallBackAnalyzer().analyze(audioFile)
        } else DynaCtrl.nativeAnalyzeFile(filePath)

        Log.i(TAG, "分析结果：$result")

        return AudioLoudnessInfo(result)
    }
}

private class FallBackAnalyzer {
    private companion object {
        const val TAG = "FallBackAnalyzer"
    }

    // Buffer 池，复用减少 GC
    private val bufferPool = object {
        private val pool = ArrayDeque<ByteBuffer>()
        private val maxPoolSize = 4

        fun acquire(): ByteBuffer = synchronized(pool) {
            return pool.removeFirstOrNull() ?: createBuffer()
        }

        fun release(buffer: ByteBuffer) {
            buffer.clear()
            synchronized(pool) {
                if (pool.size < maxPoolSize) pool.addLast(buffer)
            }
        }

        // 2MB DirectBuffer，足够大以减少传输次数
        private fun createBuffer(): ByteBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            .order(ByteOrder.nativeOrder())
    }

    /**
     * 分析音频文件响度
     * @param filePath 文件路径
     * @return 响度值 (LUFS)，失败返回 -70
     */
    fun analyze(file: File): Float {
        if (!file.exists() || !file.canRead())
            return (-70f).also { Log.w(TAG, "文件不存在或不可读") }

        return try {
            analyzeInternal(file.absolutePath)
        } catch (e: Exception) {
            (-70f).also { Log.w(TAG, "替代流分析失败", e) }
        }
    }

    private fun analyzeInternal(filePath: String): Float {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(filePath)

            // 找到音频轨道
            val audioTrackIndex = findAudioTrack(extractor)
                ?: return (-70f).also { Log.w(TAG, "找不到音频轨道") }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            // 获取音频参数
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return (-70f).also { Log.w(TAG, "获取不到音频参数") }
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // 初始化 EBU R128
            DynaCtrl.sNewEbuR128(sampleRate, channelCount)

            // 创建并配置解码器
            codec = createOptimizedDecoder(mime, format)

            // 使用异步模式解码
            return decodeAsync(codec, extractor)
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
    }

    private fun createOptimizedDecoder(mime: String, format: MediaFormat): MediaCodec {
        val codec = MediaCodec.createDecoderByType(mime)

        // TODO：优化配置
        /*format.apply {
            // 设置最高优先级
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // 低延迟模式
            try {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            } catch (e: Exception) {
                Log.w(TAG, "有些设备不支持低延迟", e)
            }

            // 增大输入缓冲区
            try {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            } catch (e: Exception) {
                Log.w(TAG, "有些设备不支持大缓冲区", e)
            }
        }*/

        codec.configure(format, null, null, 0)

        return codec
    }

    private fun decodeAsync(
        codec: MediaCodec,
        extractor: MediaExtractor
    ): Float {
        val latch = CountDownLatch(1)
        val tempBuffer = bufferPool.acquire()
        var hasError = false

        // 关键修复：创建后台 Handler 用于回调
        val handlerThread = HandlerThread("MediaCodecCallback").apply { start() }
        val callbackHandler = Handler(handlerThread.looper)

        try {
            // 异步回调处理
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    try {
                        val inputBuffer = codec.getInputBuffer(index) ?: return
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            // 输入结束
                            codec.queueInputBuffer(
                                index, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            codec.queueInputBuffer(
                                index, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "输入可用时错误", e)
                        hasError = true
                        latch.countDown()
                    }
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    try {
                        if (info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(index) ?: return

                            // 直接处理或通过临时 buffer

                            // 最优：MediaCodec 输出就是 Direct，直接传
                            if (outputBuffer.isDirect) feedToNative(outputBuffer)
                            // 需要转换：拷贝到 DirectBuffer
                            else transferToDirectBuffer(outputBuffer, tempBuffer)
                        }

                        codec.releaseOutputBuffer(index, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) latch.countDown()
                    } catch (e: Exception) {
                        Log.w(TAG, "输出可用时错误", e)
                        hasError = true
                        latch.countDown()
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "耍赖", e)
                    hasError = true
                    latch.countDown()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // 格式变化，通常不需要处理
                }
            }, callbackHandler)

            codec.start()

            // 等待解码完成，设置超时防止卡死
            val timeout = latch.await(5, TimeUnit.MINUTES)

            return if (!timeout || hasError) (-70f).also { Log.w(TAG, "超时或有错") }
            else DynaCtrl.sFinalizeEbuR128()
        } finally {
            bufferPool.release(tempBuffer)
            handlerThread.quitSafely() // 清理后台线程
        }
    }

    /**
     * 将数据喂给 native (优化版：分块传输大 buffer)
     */
    private fun feedToNative(buffer: ByteBuffer) {
        // 如果 buffer 太大，分块传输避免 native 层一次性处理太多
        val maxChunkSize = 1024 * 1024 // 1MB per chunk

        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()

        var position = originalPosition
        while (position < originalLimit) {
            val remaining = originalLimit - position
            val chunkSize = min(remaining, maxChunkSize)

            buffer.position(position)
            buffer.limit(position + chunkSize)

            // 传给 native
            DynaCtrl.sAddEbuR128Samples(buffer)

            position += chunkSize
        }

        // 恢复原始状态
        buffer.position(originalPosition)
        buffer.limit(originalLimit)
    }

    /**
     * 将非 Direct 的 ByteBuffer 转换到 DirectBuffer
     */
    private fun transferToDirectBuffer(source: ByteBuffer, directBuffer: ByteBuffer) {
        val floatSource = source.asFloatBuffer()

        while (floatSource.hasRemaining()) {
            directBuffer.clear()

            // 计算本次能拷贝多少
            val remainingFloats = floatSource.remaining()
            val bufferCapacityFloats = directBuffer.remaining() / 4
            val chunkFloats = min(remainingFloats, bufferCapacityFloats)

            // 批量拷贝
            repeat(chunkFloats) { directBuffer.putFloat(floatSource.get()) }

            directBuffer.flip()
            DynaCtrl.sAddEbuR128Samples(directBuffer)
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }

        return null
    }
}
