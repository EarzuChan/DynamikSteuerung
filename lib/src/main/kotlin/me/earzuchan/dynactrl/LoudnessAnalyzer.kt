package me.earzuchan.dynactrl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioData(
    val samples: FloatArray,
    val sampleRate: Int,
    val channelCount: Int
)

object LightweightLoudnessAnalyzer {
    private const val TAG = "LoudnessAnalyzer"

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        val filePath = audioFile.absolutePath

        val result = if (filePath.endsWith(".m4a")) {
            Log.w(TAG, "走JVM解码")

            val data = FallBackDecoder.decode(filePath)

            if (data != null) DynaCtrl.nativeCalculateLoudness(data.samples, data.sampleRate, data.channelCount)
            else (-70f).also { Log.w(TAG, "解码出了个Null") }
        } else DynaCtrl.nativeAnalyzeFile(filePath)

        Log.i(TAG, "分析结果：$result")

        return AudioLoudnessInfo(result)
    }
}


// 性能比较差，会OOM。未来想办法优化成多次推给本地：没办法了，K/N丢我对象字段这一块
private object FallBackDecoder {
    private const val TAG = "FallBackDecoder"
    private const val TIMEOUT_US = 5000L // 5ms

    /**
     * 解码指定的音频文件。
     *
     * @param filePath 音频文件的绝对路径。
     * @return 如果解码成功，返回一个包含 PCM 数据的 AudioData 对象；否则返回 null。
     */
    fun decode(filePath: String): AudioData? {
        val audioFile = File(filePath)
        if (!audioFile.exists() || !audioFile.canRead()) {
            Log.e(TAG, "无权限（恼）：$filePath")
            return null
        }

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            // 1. 初始化 MediaExtractor 并查找音频轨道
            extractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            val (trackIndex, format) = findAudioTrackFormat(extractor)
                ?: run {
                    Log.e(TAG, "无啊音轨：$filePath")
                    return null
                }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "解码：$filePath，$mime，${sampleRate}Hz，${channelCount}ch")
            // 2. 初始化并启动解码器
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // 3. 执行解码循环
            val pcmChunks = decodeAllFrames(extractor, codec)
            // 4. 合并解码后的数据块
            if (pcmChunks.isEmpty()) {
                Log.w(TAG, "啊空一个：$filePath")
                return null
            }

            val totalSamples = pcmChunks.sumOf { it.size }
            val finalPcmData = FloatArray(totalSamples)
            var currentPosition = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, finalPcmData, currentPosition, chunk.size)
                currentPosition += chunk.size
            }

            Log.d(TAG, "成功啊：${finalPcmData.size}个")
            return AudioData(finalPcmData, sampleRate, channelCount)
        } catch (e: Exception) {
            Log.e(TAG, "妈的解码：$filePath", e)
            return null
        } finally {
            // 5. 确保资源被释放
            codec?.stop()
            codec?.release()
            extractor?.release()
        }
    }

    /**
     * 循环处理所有音频帧，直到流结束。
     */
    private fun decodeAllFrames(extractor: MediaExtractor, codec: MediaCodec): List<FloatArray> {
        val decodedChunks = mutableListOf<FloatArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputStreamEnded = false
        var isOutputStreamEnded = false
        while (!isOutputStreamEnded) {
            // 向解码器提供输入数据
            if (!isInputStreamEnded) {
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isInputStreamEnded = true
                    } else {
                        codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 从解码器获取输出数据
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    if (bufferInfo.size > 0) {
                        // 假设输出为 16-bit PCM，这是最常见的情况
                        val pcmChunk = convertPcm16BitToFloat(outputBuffer, bufferInfo.size)
                        decodedChunks.add(pcmChunk)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) isOutputStreamEnded = true
                }

                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.d(TAG, "格式啊改变：${codec.outputFormat}")
            }
        }
        return decodedChunks
    }

    /**
     * 查找第一个音频轨道并返回其索引和格式。
     */
    private fun findAudioTrackFormat(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i to format
        }

        return null
    }

    /**
     * 将解码器输出的 16-bit PCM ByteBuffer 转换为 FloatArray。
     * 每个样本从 [-32768, 32767] 范围的 short 转换为 [-1.0, 1.0] 范围的 float。
     */
    private fun convertPcm16BitToFloat(buffer: ByteBuffer, size: Int): FloatArray {
        val shortBuffer = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val numSamples = size / 2
        val floatSamples = FloatArray(numSamples)

        for (i in 0 until numSamples) floatSamples[i] = shortBuffer.get(i) / 32768f

        return floatSamples
    }
}