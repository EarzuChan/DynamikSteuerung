package me.earzuchan.dynactrl

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import me.earzuchan.dynactrl.utils.BufferPool
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp

/**
 * 轻量级响度分析器 - 完整优化版本
 */
class LightweightLoudnessAnalyzer {
    companion object {
        private const val TAG = "LoudnessAnalyzer"

        // 性能优化参数
        private const val TIMEOUT_US = 10_000L // 10ms 超时
        private const val DOWNSAMPLE_RATIO = 8 // 降采样比例
        private const val BUFFER_SIZE = 4096 // 缓冲区大小
        private const val MAX_ANALYSIS_DURATION_US = 240_000_000L // 最多分析240秒
    }

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
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
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val pcmFormat = detectPcmFormat(format)

            Log.d(TAG, "Audio format: $mime, ${sampleRate}Hz, ${channelCount}ch, ${pcmFormat}")

            // 3. 初始化解码器
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // 4. 初始化降采样器和响度计算器
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

                                // 添加到响度计算器
                                if (downsampledSamples.isNotEmpty()) {
                                    loudnessCalculator.addSamples(downsampledSamples)
                                    totalSamplesProcessed += samples.size / channelCount
                                }
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                isEOS = true
                            }
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Output format changed")
                        }

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {} // 继续等待

                        else -> {
                            Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                        }
                    }
                }
            } finally {
                BufferPool.returnFloatArray(sampleBuffer)
            }

            // 6. 计算最终响度
            val loudness = if (totalSamplesProcessed > 0) loudnessCalculator.getIntegratedLoudness() else -70f

            Log.d(TAG, "Analysis complete: ${loudness} LUFS, processed $totalSamplesProcessed samples")
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
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun detectPcmFormat(format: MediaFormat): PcmFormat {
        val pcmEncoding = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) try {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } catch (_: Exception) {
            AudioFormat.ENCODING_PCM_16BIT
        } else AudioFormat.ENCODING_PCM_16BIT

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmFormat.PCM_16BIT
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmFormat.PCM_24BIT
            AudioFormat.ENCODING_PCM_32BIT -> PcmFormat.PCM_32BIT
            AudioFormat.ENCODING_PCM_FLOAT -> PcmFormat.PCM_FLOAT
            else -> PcmFormat.PCM_16BIT
        }
    }

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

/**
 * PCM 格式枚举
 */
enum class PcmFormat {
    PCM_16BIT,
    PCM_24BIT,
    PCM_32BIT,
    PCM_FLOAT
}

/**
 * 带抗混叠的降采样器
 */
class AntiAliasingDownsampler(
    private val ratio: Int, private val channels: Int, sampleRate: Int
) {
    private val lowpassFilter = LowpassFilter(
        sampleRate = sampleRate,
        cutoffFreq = sampleRate.toFloat() / (2 * ratio * 1.1f), // 留点余量
        channels = channels
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
 * 简单的低通滤波器
 */
class LowpassFilter(
    sampleRate: Int,
    cutoffFreq: Float,
    private val channels: Int
) {
    private val alpha = exp(-2f * PI * cutoffFreq / sampleRate).toFloat()
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