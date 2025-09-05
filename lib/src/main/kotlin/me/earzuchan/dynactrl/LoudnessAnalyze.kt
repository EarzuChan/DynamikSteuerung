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
        private const val BUFFER_SIZE = 4096 // 缓冲区大小
        // zTIPS：暂时禁用降采样
        private const val DOWNSAMPLE_RATIO = 8 // 降采样比例 WHY：降采样导致响度降低 TODO：有优化方案，到时看看
        private const val ULTRA_LIGHT_DOWNSAMPLE_RATIO = 12 // 超轻模式降采样比例 WHY：16会导致-INF
        private const val MAX_ANALYSIS_DURATION_US = 180_000_000L // 最多分析180秒
        private const val ULTRA_LIGHT_MAX_ANALYSIS_DURATION_US = 120_000_000L // 超轻模式最多分析120秒
        private const val SKIP_HEAD_RATIO = 0.05f // 跳过前5%
        private const val ULTRA_LIGHT_SKIP_HEAD_RATIO = 0.15f // 超轻模式跳过前15%
        private const val SKIP_TAIL_RATIO = 0.05f // 跳过后5%
        private const val ULTRA_LIGHT_SKIP_TAIL_RATIO = 0.15f // 超轻模式跳过后15%
        private const val ULTRA_LIGHT_FRAME_SKIP_RATIO = 3 // 超轻模式每3帧处理1帧
        private const val ULTRA_LIGHT_SAMPLE_KEEP_RATIO = 0.6f // 超轻模式保留60%的样本
        // private const val ULTRA_LIGHT_UNIFORM_SKIP = 3 // 或者用均匀跳跃：每3个样本取2个
    }

    // 在类中添加一个随机数生成器（避免每次创建）
    // private val random = Random(System.currentTimeMillis())

    fun analyzeFile(audioFile: File, ultraLightMode: Boolean = true): AudioLoudnessInfo {
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
            val originalChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)

            // 验证MIME类型
            if (mime.isNullOrEmpty()) {
                Log.e(TAG, "Missing MIME type: $mime")
                return AudioLoudnessInfo(-70f)
            }

            val pcmFormat = detectPcmFormat(format)

            // 超轻模式：强制单声道处理
            val newChannelCount = if (ultraLightMode) 1 else originalChannelCount
            // TODO：byd 越降响度计算出来越低了，可能要乘个系数？
            // val downsampleRatio = if (ultraLightMode) ULTRA_LIGHT_DOWNSAMPLE_RATIO else DOWNSAMPLE_RATIO TIPS：暂时禁用降采样
            val maxAnalysisDuration =
                if (ultraLightMode) ULTRA_LIGHT_MAX_ANALYSIS_DURATION_US else MAX_ANALYSIS_DURATION_US

            Log.d(
                TAG,
                "Audio format: $mime, ${sampleRate}Hz, ${originalChannelCount}ch->${newChannelCount}ch, ${pcmFormat}, ultra: $ultraLightMode"
            )

            // 3. 初始化解码器
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // 4. 超轻模式：计算跳过的时间范围
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val (skipStartUs, skipEndUs) = if (ultraLightMode && durationUs > 0) {
                val skipStart = (durationUs * ULTRA_LIGHT_SKIP_HEAD_RATIO).toLong()
                val skipEnd = durationUs - (durationUs * ULTRA_LIGHT_SKIP_TAIL_RATIO).toLong()
                Pair(skipStart, skipEnd)
            } else if (durationUs > 0) {
                val skipStart = (durationUs * SKIP_HEAD_RATIO).toLong()
                val skipEnd = durationUs - (durationUs * SKIP_TAIL_RATIO).toLong()
                Pair(skipStart, skipEnd)
            } else Pair(0L, Long.MAX_VALUE) // 如果无法获取时长，处理全部

            Log.d(
                TAG,
                "Processing range: ${skipStartUs / 1000}ms - ${skipEndUs / 1000}ms (duration: ${durationUs / 1000}ms)"
            )

            // 5. 初始化降采样器和响度计算器 TIPS：暂时禁用降采样
            /*val downsampledSampleRate = sampleRate / downsampleRatio
            val downsampler = AntiAliasingDownsampler(downsampleRatio, newChannelCount, sampleRate)*/
            val loudnessCalculator = LightweightEbuR128(newChannelCount, sampleRate /*downsampledSampleRate*/)

            // 6. 解码并处理音频
            val bufferInfo = MediaCodec.BufferInfo()
            var isEnded = false
            var totalSamplesProcessed = 0L
            var frameSkipCounter = 0 // 用于跳帧处理
            val maxSamples = (maxAnalysisDuration * sampleRate / 1_000_000L).toInt()

            // 使用对象池减少内存分配
            val sampleBuffer = BufferPool.borrowFloatArray(BUFFER_SIZE)
            val monoBuffer = if (ultraLightMode && originalChannelCount > 1) {
                BufferPool.borrowFloatArray(BUFFER_SIZE / originalChannelCount)
            } else null

            try {
                while (!isEnded && totalSamplesProcessed < maxSamples) {
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
                                isEnded = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime

                                if (presentationTimeUs !in skipStartUs..skipEndUs) {
                                    // 跳过这个帧，但仍要推进extractor和释放buffer
                                    extractor.advance()
                                    codec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, 0)

                                    // 如果已经超过结束时间，直接看作结束
                                    if (presentationTimeUs > skipEndUs) isEnded = true
                                    continue
                                }

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
                                // 超轻模式：跳帧处理
                                if (ultraLightMode) {
                                    frameSkipCounter++
                                    if (frameSkipCounter % ULTRA_LIGHT_FRAME_SKIP_RATIO != 0) {
                                        codec.releaseOutputBuffer(outputBufferIndex, false)
                                        continue
                                    }
                                }

                                // 处理PCM数据
                                val samples = processPcmData(outputBuffer, bufferInfo.size, pcmFormat, sampleBuffer)

                                // 检查样本数据是否有效
                                if (samples.isEmpty()) {
                                    codec.releaseOutputBuffer(outputBufferIndex, false)
                                    continue
                                }

                                // 超轻模式：转换为单声道
                                val finalSamples = if (ultraLightMode && originalChannelCount > 1 && monoBuffer != null)
                                    convertToMono(samples, originalChannelCount, monoBuffer)
                                else samples

                                // 降采样 TIPS：暂时禁用降采样
                                // val downsampledSamples = downsampler.process(finalSamples)

                                // 超轻模式：进一步减少样本量
                                val finalProcessedSamples = if (ultraLightMode && /*downsampledSamples*/finalSamples.isNotEmpty())
                                    reduceSamples(/*downsampledSamples*/finalSamples, newChannelCount)
                                else /*downsampledSamples*/finalSamples

                                // 添加到响度计算器
                                if (finalProcessedSamples.isNotEmpty()) {
                                    loudnessCalculator.addSamples(finalProcessedSamples)
                                    totalSamplesProcessed += finalSamples.size / newChannelCount
                                }
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isEnded = true
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            Log.d(TAG, "Output format changed")

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {} // 继续等待

                        else -> Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                    }
                }
            } finally {
                BufferPool.returnFloatArray(sampleBuffer)
                monoBuffer?.let { BufferPool.returnFloatArray(it) }
            }

            // 7. 计算最终响度
            var loudness = if (totalSamplesProcessed > 0) loudnessCalculator.getIntegratedLoudness()
            else {
                Log.w(TAG, "No samples processed!")
                -70f
            }

            // 根据降采样倍率进行补偿 TIPS：暂时禁用降采样
            // TODO：要不要经典解方程？还有就是EBUR里面那个魔数他妈的？另外要不要移到EBUR里
            // loudness += if (ultraLightMode) 5.4f else 2.7f

            Log.d(
                TAG,
                "Analysis complete: $loudness LUFS, processed $totalSamplesProcessed samples (ultra: $ultraLightMode)"
            )
            return AudioLoudnessInfo(loudness)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing file", e)
            return AudioLoudnessInfo(-70f)
        } finally {
            // 8. 清理资源
            try {
                codec?.stop()
                codec?.release()
                extractor?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources", e)
            }
        }
    }

    /**
     * 超轻模式样本减少策略
     */
    private fun reduceSamples(samples: FloatArray, channelCount: Int): FloatArray = when {
        samples.size < 100 -> samples // 样本太少就不减少了
        else -> {
            // 方法1：随机抽样（推荐，统计特性最好）
            // randomSubsample(samples, channelCount, ULTRA_LIGHT_SAMPLE_KEEP_RATIO)

            // 方法2：均匀抽样（性能最好）
            // uniformSubsample(samples, channelCount, ULTRA_LIGHT_UNIFORM_SKIP)

            // 方法3：伪随机抽样（性能和统计特性的平衡）
            pseudoRandomSubsample(samples, channelCount)
        }
    }

    /**
     * 方法1：随机抽样 - 统计特性最好，但有一定计算开销
     */
    /*private fun randomSubsample(samples: FloatArray, channelCount: Int, keepRatio: Float): FloatArray {
        val frames = samples.size / channelCount
        val keepFrames = (frames * keepRatio).toInt()

        if (keepFrames >= frames) return samples

        val outputSize = keepFrames * channelCount
        val output = FloatArray(outputSize)

        // 生成随机帧索引
        val selectedFrames = (0 until frames).shuffled(random).take(keepFrames).sorted()

        for ((outputFrame, sourceFrame) in selectedFrames.withIndex()) for (ch in 0 until channelCount)
            output[outputFrame * channelCount + ch] = samples[sourceFrame * channelCount + ch]

        return output
    }*/

    /**
     * 方法2：均匀抽样 - 性能最好，规律性强
     */
    /*private fun uniformSubsample(samples: FloatArray, channelCount: Int, skipRatio: Int): FloatArray {
        val frames = samples.size / channelCount
        val keepFrames = frames / skipRatio * (skipRatio - 1) // 每skipRatio帧保留(skipRatio-1)帧

        if (keepFrames >= frames) return samples

        val outputSize = keepFrames * channelCount
        val output = FloatArray(outputSize)
        var outputIndex = 0

        // 跳过每skipRatio帧中的第1帧
        for (frame in 0 until frames) if (frame % skipRatio != 0)
            for (ch in 0 until channelCount) if (outputIndex < outputSize) {
                output[outputIndex] = samples[frame * channelCount + ch]
                outputIndex++
            }

        return output.copyOfRange(0, minOf(outputIndex, outputSize))
    }*/

    /**
     * 方法3：伪随机抽样 - 性能和随机性的平衡
     * 使用简单的线性同余生成器避免Random类的开销
     */
    private var pseudoRandomSeed = System.currentTimeMillis() and 0xFFFFFF

    private fun pseudoRandomSubsample(samples: FloatArray, channelCount: Int): FloatArray {
        val frames = samples.size / channelCount
        val keepFrames = (frames * ULTRA_LIGHT_SAMPLE_KEEP_RATIO).toInt()

        if (keepFrames >= frames) return samples

        val outputSize = keepFrames * channelCount
        val output = FloatArray(outputSize)
        var outputIndex = 0

        for (frame in 0 until frames) {
            // 简单的伪随机判断
            pseudoRandomSeed = (pseudoRandomSeed * 1103515245 + 12345) and 0x7FFFFFFF
            val randomValue = (pseudoRandomSeed % 1000) / 1000f

            if (randomValue < ULTRA_LIGHT_SAMPLE_KEEP_RATIO && outputIndex < outputSize - channelCount)
                for (ch in 0 until channelCount) {
                    output[outputIndex] = samples[frame * channelCount + ch]
                    outputIndex++
                }
        }

        return output.copyOfRange(0, outputIndex)
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

    // 优化的PCM数据处理
    private fun processPcmData(
        buffer: ByteBuffer, size: Int, format: PcmFormat, outputBuffer: FloatArray
    ): FloatArray {
        val bytesPerSample = when (format) {
            PcmFormat.PCM_16BIT -> 2
            PcmFormat.PCM_24BIT -> 3
            PcmFormat.PCM_32BIT, PcmFormat.PCM_FLOAT -> 4
        }

        val sampleCount = size / bytesPerSample
        if (sampleCount <= 0) return floatArrayOf()

        val actualOutputSize = minOf(sampleCount, outputBuffer.size)

        // 直接在输出缓冲区中工作，避免额外分配
        when (format) {
            PcmFormat.PCM_16BIT -> for (i in 0 until actualOutputSize)
                outputBuffer[i] = buffer.getShort(i * 2) / 32768f

            PcmFormat.PCM_24BIT -> for (i in 0 until actualOutputSize) {
                val offset = i * 3
                val byte1 = buffer.get(offset).toInt() and 0xFF
                val byte2 = buffer.get(offset + 1).toInt() and 0xFF
                val byte3 = buffer.get(offset + 2).toInt()
                val sample = (byte3 shl 16) or (byte2 shl 8) or byte1
                outputBuffer[i] = sample / 8388608f
            }

            PcmFormat.PCM_32BIT -> for (i in 0 until actualOutputSize)
                outputBuffer[i] = buffer.getInt(i * 4) / 2.1474836E9f

            PcmFormat.PCM_FLOAT -> for (i in 0 until actualOutputSize)
                outputBuffer[i] = buffer.getFloat(i * 4)
        }

        return outputBuffer.copyOfRange(0, actualOutputSize)
    }

    // 将多声道转换为单声道（取平均值）
    private fun convertToMono(input: FloatArray, channelCount: Int, outputBuffer: FloatArray): FloatArray {
        val frames = input.size / channelCount
        if (frames <= 0) return floatArrayOf()

        val actualOutputSize = minOf(frames, outputBuffer.size)

        for (frame in 0 until actualOutputSize) {
            var sum = 0f
            for (ch in 0 until channelCount) sum += input[frame * channelCount + ch]
            outputBuffer[frame] = sum / channelCount
        }

        return outputBuffer.copyOfRange(0, actualOutputSize)
    }
}