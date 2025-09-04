package me.earzuchan.dynactrl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File
import java.nio.ByteBuffer

class LightweightLoudnessAnalyzer {
    companion object {
        private const val TAG = "LoudnessAnalyzer"

        // 性能优化参数
        private const val TIMEOUT_US = 10_000L // 10ms 超时
        private const val SAMPLE_INTERVAL = 32 // 每32个样本取1个
        private const val BUFFER_SIZE = 4096 // 缓冲区大小
        private const val MAX_ANALYSIS_DURATION_US = 240_000_000L // 最多分析240秒
    }

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        if (!audioFile.exists() || !audioFile.canRead()) {
            Log.e(TAG, "File not accessible: ${audioFile.absolutePath}")
            return AudioLoudnessInfo(-70f) // 返回静音级别
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

            Log.d(TAG, "Audio format: $mime, ${sampleRate}Hz, ${channelCount}ch")

            // 3. 初始化解码器
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // 4. 初始化响度计算器（使用降采样后的采样率）
            val effectiveSampleRate = sampleRate / SAMPLE_INTERVAL
            val loudnessCalculator = LightweightEbuR128(channelCount, effectiveSampleRate)

            // 5. 解码并处理音频
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var totalSamplesProcessed = 0L
            val maxSamples = (MAX_ANALYSIS_DURATION_US * sampleRate / 1_000_000L).toInt()

            // 复用的样本缓冲区
            val sampleBuffer = FloatArray(BUFFER_SIZE)

            while (!isEOS && totalSamplesProcessed < maxSamples) {
                // 输入缓冲区处理
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            // 输入结束
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
                                sampleBuffer
                            )

                            // 批量添加样本到响度计算器
                            if (samples.isNotEmpty()) {
                                loudnessCalculator.addSamples(samples)
                                totalSamplesProcessed += samples.size / channelCount
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isEOS = true
                    }

                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Output format changed")

                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {} // 继续等待

                    else -> {
                        Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                    }
                }
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

    private fun processPcmData(buffer: ByteBuffer, size: Int, sampleBuffer: FloatArray): FloatArray {
        // 假设输入是16位PCM
        val sampleCount = size / 2 // 每个样本2字节
        val downsampledCount = (sampleCount + SAMPLE_INTERVAL - 1) / SAMPLE_INTERVAL

        // 确保缓冲区足够大
        val outputBuffer = if (downsampledCount <= sampleBuffer.size) sampleBuffer else FloatArray(downsampledCount)

        var outputIndex = 0
        var inputIndex = 0

        // 间隔抽样：每SAMPLE_INTERVAL个样本取一个
        while (inputIndex < sampleCount && outputIndex < outputBuffer.size) {
            // 读取16位有符号整数并转换为浮点数（-1.0 到 1.0）
            val sample = buffer.getShort(inputIndex * 2)
            outputBuffer[outputIndex] = sample / 32768f

            inputIndex += SAMPLE_INTERVAL
            outputIndex++
        }

        // 返回实际填充的部分
        return if (outputBuffer === sampleBuffer) outputBuffer.copyOfRange(0, outputIndex)
        else outputBuffer.copyOf(outputIndex)
    }
}