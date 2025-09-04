package me.earzuchan.dynactrl

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import me.earzuchan.dynactrl.utils.AntiAliasingDownsampler
import me.earzuchan.dynactrl.utils.BufferPool
import java.io.File
import java.nio.ByteBuffer

/**
 * 轻量级响度分析器
 */
class LightweightLoudnessAnalyzer {
    /**
     * 内置的PCM 格式枚举
     */
    enum class PcmFormat {
        PCM_16BIT,
        PCM_24BIT,
        PCM_32BIT,
        PCM_FLOAT
    }

    companion object {
        private const val TAG = "LoudnessAnalyzer"

        // 性能优化参数
        private const val TIMEOUT_US = 10_000L // 10ms 超时
        private const val DOWNSAMPLE_RATIO = 8 // 降采样比例 TODO：超轻模式下是否需要更狠的降采样？
        private const val BUFFER_SIZE = 4096 // 缓冲区大小
        private const val MAX_ANALYSIS_DURATION_US = 180_000_000L // 最多分析180秒 TODO：要不要改成按音频长度掐头（按比例跳过一定的开头）去尾计算一个分析时长？超轻模式下是否需要更短的分析？
    }

    // TODO：整体再看看哪里能提升性能，和加入超轻模式进一步“偷鸡”
    fun analyzeFile(audioFile: File, ultraLightMode: Boolean = true /*TODO：超轻模式*/): AudioLoudnessInfo {
        if (!audioFile.exists() || !audioFile.canRead()) {
            Log.e(TAG, "File not accessible: ${audioFile.absolutePath}")
            return AudioLoudnessInfo(-70f)
        }

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            // 1. 初始化 MediaExtractor
            extractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            // 2. 查找音频轨道
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found")
                return AudioLoudnessInfo(-70f)
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            // 获取音频参数
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "" // TODO：如果为空字符串怎么办，可能需处理或提早返回？
            val pcmFormat = detectPcmFormat(format)

            Log.d(TAG, "Audio format: $mime, ${sampleRate}Hz, ${channelCount}ch, ${pcmFormat}")

            // 3. 初始化解码器
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // 4. 初始化降采样器和响度计算器
            // TODO：超轻模式下应该降为单声道处理（或者只取一声道）
            val downsampledSampleRate = sampleRate / DOWNSAMPLE_RATIO
            val downsampler = AntiAliasingDownsampler(DOWNSAMPLE_RATIO, channelCount, sampleRate)
            val loudnessCalculator = LightweightEbuR128(channelCount, downsampledSampleRate)

            // 5. 解码并处理音频
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var totalSamplesProcessed = 0L
            val maxSamples = (MAX_ANALYSIS_DURATION_US * sampleRate / 1_000_000L).toInt()

            // 使用对象池减少内存分配
            val sampleBuffer = BufferPool.borrowFloatArray(BUFFER_SIZE)
            try {
                while (!isEOS && totalSamplesProcessed < maxSamples) {
                    // TODO：看看超轻模式下怎么合理“偷工减料”提升性能

                    // 输入缓冲区处理
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)

                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize,
                                    presentationTimeUs, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    // 输出缓冲区处理
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // 处理PCM数据
                                val samples = processPcmData(
                                    outputBuffer,
                                    bufferInfo.size,
                                    pcmFormat,
                                    sampleBuffer
                                )

                                // 降采样
                                val downsampledSamples = downsampler.process(samples)

                                // TODO：应该加本次不采样（看和上次采样的音轨时间间隔）、样本减量（平均地抽掉一半或一部分）的逻辑

                                // 添加到响度计算器
                                if (downsampledSamples.isNotEmpty()) {
                                    loudnessCalculator.addSamples(downsampledSamples)
                                    totalSamplesProcessed += samples.size / channelCount
                                }
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isEOS = true
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            Log.d(TAG, "Output format changed")

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {} // 继续等待

                        else -> Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                    }
                }
            } finally {
                BufferPool.returnFloatArray(sampleBuffer)
            }

            // 6. 计算最终响度
            val loudness = if (totalSamplesProcessed > 0) loudnessCalculator.getIntegratedLoudness() else -70f

            Log.d(TAG, "Analysis complete: $loudness LUFS, processed $totalSamplesProcessed samples")
            return AudioLoudnessInfo(loudness)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing file", e)
            return AudioLoudnessInfo(-70f)
        } finally {
            // 7. 清理资源
            try {
                codec?.stop()
                codec?.release()
                extractor?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources", e)
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        repeat(extractor.trackCount) {
            val format = extractor.getTrackFormat(it)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return it
        }
        return -1
    }

    private fun detectPcmFormat(format: MediaFormat): PcmFormat {
        val pcmEncoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
            .getOrNull()

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmFormat.PCM_24BIT
            AudioFormat.ENCODING_PCM_32BIT -> PcmFormat.PCM_32BIT
            AudioFormat.ENCODING_PCM_FLOAT -> PcmFormat.PCM_FLOAT
            else -> PcmFormat.PCM_16BIT // 缺省就是16
        }
    }

    // TODO：这里能提升性能吗？
    private fun processPcmData(
        buffer: ByteBuffer, size: Int,
        format: PcmFormat, outputBuffer: FloatArray
    ): FloatArray {
        val sampleCount = when (format) {
            PcmFormat.PCM_16BIT -> size / 2
            PcmFormat.PCM_24BIT -> size / 3
            PcmFormat.PCM_32BIT -> size / 4
            PcmFormat.PCM_FLOAT -> size / 4
        }

        val actualOutputSize = minOf(sampleCount, outputBuffer.size)
        val resultBuffer = if (actualOutputSize <= outputBuffer.size) outputBuffer else FloatArray(actualOutputSize)

        when (format) {
            PcmFormat.PCM_16BIT -> for (i in 0 until actualOutputSize) {
                val sample = buffer.getShort(i * 2)
                resultBuffer[i] = sample / 32768f
            }

            PcmFormat.PCM_24BIT -> for (i in 0 until actualOutputSize) {
                val byte1 = buffer.get(i * 3).toInt() and 0xFF
                val byte2 = buffer.get(i * 3 + 1).toInt() and 0xFF
                val byte3 = buffer.get(i * 3 + 2).toInt()
                val sample = (byte3 shl 16) or (byte2 shl 8) or byte1
                resultBuffer[i] = sample / 8388608f
            }

            PcmFormat.PCM_32BIT -> for (i in 0 until actualOutputSize) {
                val sample = buffer.getInt(i * 4)
                resultBuffer[i] = sample / 2.1474836E9f
            }

            PcmFormat.PCM_FLOAT -> for (i in 0 until actualOutputSize) resultBuffer[i] = buffer.getFloat(i * 4)
        }

        return if (resultBuffer === outputBuffer) resultBuffer.copyOfRange(0, actualOutputSize)
        else resultBuffer.copyOf(actualOutputSize)
    }
}