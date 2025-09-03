package me.earzuchan.dynactrl.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.lang.System.loadLibrary
import java.nio.ByteBuffer

/**
 * Media3 ExoPlayer audio processor that applies loudness normalization using
 * pre-calculated loudness information.
 */
@OptIn(UnstableApi::class)
class DynamicsProcessor : BaseAudioProcessor() {
    private var loudnessInfo: AudioLoudnessInfo? = null
    private var currentScale: Float = 1.0f
    private var targetScale: Float = 1.0f
    private val scaleStep: Float = 0.001f // Scale change per sample for smooth ramping
    private val fadeTimeMs: Long = 50 // 50ms fade time for scale changes

    private var samplesPerFrame: Int = 1
    private var bytesPerSample: Int = 4 // Default to float
    private var sampleRate: Int = 44100

    /**
     * Set the loudness information for the current track.
     * Should be called before playback starts for each new track.
     */
    fun setCurrentTrackLoudness(loudnessInfo: AudioLoudnessInfo) {
        this.loudnessInfo = loudnessInfo
        targetScale = loudnessInfo.getCalculatedTargetScale().coerceIn(0.1f, 4.0f) // Reasonable bounds
        currentScale = targetScale // Start directly at target, or ramp if smooth transition desired

        // Calculate fade parameters if needed
        samplesPerFrame = if (sampleRate > 0) (sampleRate * fadeTimeMs / 1000L).toInt() else 1
    }

    /**
     * Called by ExoPlayer when audio format changes (including at track start).
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        val encoding = inputAudioFormat.encoding

        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            // Unsupported encoding
            else -> return inputAudioFormat
        }

        samplesPerFrame = if (sampleRate > 0) (sampleRate * fadeTimeMs / 1000L).toInt() else 1

        // Output format is the same as input
        return inputAudioFormat
    }

    /**
     * Process audio in real-time during playback.
     * This receives audio buffers from the decoder and should modify them.
     */
    override fun queueInput(inputBuffer: ByteBuffer) {
        // Input buffer contains audio data to be processed
        if (!inputBuffer.hasRemaining()) return

        // Ensure we have an output buffer to write to
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())

        val channelCount = inputAudioFormat.channelCount
        val sampleCount = inputBuffer.remaining() / (channelCount * bytesPerSample)

        // Process the audio with loudness normalization
        processAudioBuffer(inputBuffer, outputBuffer, sampleCount, channelCount)
    }

    /**
     * Core audio processing function that applies loudness normalization.
     */
    private fun processAudioBuffer(
        inputBuffer: ByteBuffer, outputBuffer: ByteBuffer,
        sampleCount: Int, channelCount: Int
    ) {
        // For simplicity, we'll work with float samples
        // In a real implementation with integer formats, conversion would be needed

        when (bytesPerSample) {
            2 -> processPcm16(inputBuffer, outputBuffer, sampleCount, channelCount)

            // Could be PCM32 or FLOAT
            4 -> if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
                processPcmFloat(inputBuffer, outputBuffer, sampleCount, channelCount)
            else processPcm32(inputBuffer, outputBuffer, sampleCount, channelCount)

            // Fallback: just copy the buffer
            else -> outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(
        inputBuffer: ByteBuffer, outputBuffer: ByteBuffer,
        sampleCount: Int, channelCount: Int
    ) {
        val inputSamples = FloatArray(sampleCount * channelCount)
        inputBuffer.asFloatBuffer().get(inputSamples)

        val outputSamples = FloatArray(sampleCount * channelCount)

        for (i in 0 until sampleCount * channelCount) outputSamples[i] = inputSamples[i] * targetScale

        outputBuffer.asFloatBuffer().put(outputSamples)
    }

    private fun processPcm16(
        inputBuffer: ByteBuffer, outputBuffer: ByteBuffer,
        sampleCount: Int, channelCount: Int
    ) {
        val inputSamples = ShortArray(sampleCount * channelCount)
        inputBuffer.asShortBuffer().get(inputSamples)

        val outputSamples = ShortArray(sampleCount * channelCount)

        for (i in 0 until sampleCount * channelCount) {
            val scaled = (inputSamples[i].toFloat() * targetScale).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            outputSamples[i] = scaled.toShort()
        }

        outputBuffer.asShortBuffer().put(outputSamples)
    }

    private fun processPcm32(
        inputBuffer: ByteBuffer, outputBuffer: ByteBuffer,
        sampleCount: Int, channelCount: Int
    ) {
        val inputSamples = IntArray(sampleCount * channelCount)
        inputBuffer.asIntBuffer().get(inputSamples)

        val outputSamples = IntArray(sampleCount * channelCount)

        for (i in 0 until sampleCount * channelCount) {
            val scaled = (inputSamples[i].toFloat() * targetScale).toInt()
                .coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)

            outputSamples[i] = scaled
        }

        outputBuffer.asIntBuffer().put(outputSamples)
    }

    /**
     * Called when the track ends or playback stops.
     * Clean up and reset state.
     */
    override fun onReset() {
        loudnessInfo = null
        currentScale = 1.0f
        targetScale = 1.0f
        super.onReset()
    }

    /**
     * Get debugging information about the processor state.
     */
    fun getDebugInfo(): String = "DynamicsProcessor(scale=%.3f, target=%.3f, sampleRate=%d)".format(
        currentScale, targetScale, sampleRate
    )

    companion object {
        init { loadLibrary("dynactrl") }

        // JNI function for native processing (optional, for complex DSP)
        @JvmStatic
        external fun nativeProcessAudio(
            loudnessInfoPtr: Long, inputBuffer: ByteBuffer,
            outputBuffer: ByteBuffer, sampleCount: Int
        ): Boolean
    }
}