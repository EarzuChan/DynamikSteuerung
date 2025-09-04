package me.earzuchan.dynactrl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File
import java.nio.ByteOrder

class LightweightLoudnessAnalyzer(private val maxAnalysisDuration: Long = 600L) {
    enum class Precision {
        FAST, STANDARD, HIGH
    }

    fun analyzeFile(audioFile: File, precision: Precision = Precision.STANDARD): AudioLoudnessInfo {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)

            val format = getAudioFormat(extractor) ?: return createErrorResult()
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            // 简化精度处理
            val analysisChannels = minOf(sourceChannels, 2) // 最多处理立体声
            val analysisSampleRate = when (precision) {
                Precision.FAST -> minOf(sourceSampleRate, 22050)
                Precision.STANDARD -> minOf(sourceSampleRate, 48000)
                Precision.HIGH -> sourceSampleRate
            }

            decoder = configureDecoder(format)
            decoder.start()

            // 创建EBU R128测量状态
            val ebur128 = EbuR128Analyzer(analysisChannels, analysisSampleRate)

            val maxSamples = if (maxAnalysisDuration > 0) maxAnalysisDuration * analysisSampleRate
            else durationUs * analysisSampleRate / 1000000L

            var totalSamplesProcessed = 0L
            var inputEos = false
            var outputEos = false

            while (!outputEos && totalSamplesProcessed < maxSamples) {
                // 输入数据
                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(1000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(buffer, 0)

                        if (sampleSize < 0) {
                            inputEos = true
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 处理输出
                val bufferInfo = MediaCodec.BufferInfo()
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000)

                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // 获取解码器输出格式
                            val outputFormat = decoder.outputFormat
                            val pcmEncoding = 2 // outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, 2) // <- 无该API // 默认16bit

                            // 转换为float数组
                            val floatSamples = convertToFloatSamples(
                                outputBuffer,
                                bufferInfo.size,
                                pcmEncoding
                            )

                            if (floatSamples.isNotEmpty()) {
                                val framesToProcess = minOf(
                                    floatSamples.size / analysisChannels,
                                    (maxSamples - totalSamplesProcessed).toInt()
                                )

                                val processedSamples = floatSamples.copyOfRange(0, framesToProcess * analysisChannels)
                                ebur128.addSamples(processedSamples, framesToProcess)
                                totalSamplesProcessed += framesToProcess
                            }
                        }

                        decoder.releaseOutputBuffer(outputIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 输出格式改变，可以在这里获取新格式
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 稍后重试
                    }
                }
            }

            val lufs = ebur128.getIntegratedLoudness()
            AudioLoudnessInfo(lufs = if (lufs.isFinite()) lufs else -70f)
        } catch (e: Exception) {
            e.printStackTrace()
            createErrorResult()
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
                extractor?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun convertToFloatSamples(
        buffer: java.nio.ByteBuffer,
        size: Int,
        pcmEncoding: Int
    ): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.rewind()

        return when (pcmEncoding) {
            2 -> { // 16-bit PCM
                val shortSamples = size / 2
                val samples = FloatArray(shortSamples)
                for (i in 0 until shortSamples) samples[i] = buffer.getShort().toFloat() / 32768f
                samples
            }

            3, 22 -> { // 8-bit PCM
                val samples = FloatArray(size)
                for (i in 0 until size) samples[i] = (buffer.get().toInt() and 0xFF - 128) / 128f
                samples
            }

            4 -> { // 32-bit float PCM
                val floatSamples = size / 4
                val samples = FloatArray(floatSamples)
                for (i in 0 until floatSamples) samples[i] = buffer.getFloat()
                samples
            }

            else -> { // 默认按16-bit处理
                val shortSamples = size / 2
                val samples = FloatArray(shortSamples)
                for (i in 0 until shortSamples) samples[i] = buffer.getShort().toFloat() / 32768f
                samples
            }
        }
    }

    private fun getAudioFormat(extractor: MediaExtractor): MediaFormat? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                return format
            }
        }
        return null
    }

    private fun configureDecoder(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        return codec
    }

    private fun createErrorResult() = AudioLoudnessInfo(lufs = -70f)
}