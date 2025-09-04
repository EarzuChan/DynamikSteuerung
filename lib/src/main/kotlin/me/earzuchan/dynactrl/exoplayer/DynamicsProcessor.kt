package me.earzuchan.dynactrl.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Media3 ExoPlayer audio processor that applies loudness normalization based on
 * EBU R128 standard using pre-calculated loudness information.
 *
 * Features:
 * - Dynamic gain adjustment for loudness normalization
 * - Smooth gain transitions to avoid audio artifacts
 * - Support for multiple channel configurations
 * - Memory efficient processing with minimal latency
 */
@OptIn(UnstableApi::class)
class DynamicsProcessor: BaseAudioProcessor() {
    // Current track loudness information
    private var currentTrackLoudness: AudioLoudnessInfo? = null

    // Target reference level in LUFS (typically -18 LUFS for broadcast)
    private var targetLoudness: Float = -18.0f

    // Processing state
    private var inputChannels = 1
    private var inputSampleRate = 48000
    private var currentGain = 1.0f
    private var targetGain = 1.0f

    // Gain ramping for smooth transitions
    private var gainRampSamples = 0
    private var gainRampSteps = 0
    private var gainRampStepSize = 0.0f

    // Buffer for PCM processing
    private val audioFloatSamples = mutableListOf<Float>()
    private val processedSamples = mutableListOf<Byte>()

    companion object {
        private const val REFERENCE_LOUDNESS = -14.0f  // Standard broadcast level
        private const val GAIN_RAMP_MS = 100  // Gain ramp duration in milliseconds
        private const val MAX_GAIN_DB = 24.0f  // Maximum allowed gain boost
        private const val MIN_GAIN_DB = -24.0f  // Maximum allowed gain cut
    }

    /**
     * Set the current track's loudness information before processing begins.
     * This should be called before each new track is loaded.
     *
     * @param loudnessInfo Pre-measured loudness of the current track
     * @param targetReference Target loudness level (default -18 LUFS)
     */
    fun setCurrentTrackLoudness(loudnessInfo: AudioLoudnessInfo, targetReference: Float = REFERENCE_LOUDNESS) {
        currentTrackLoudness = loudnessInfo
        targetLoudness = targetReference

        if (!loudnessInfo.lufs.isNaN() && !loudnessInfo.lufs.isInfinite()) {
            val requiredGainDb = targetReference - loudnessInfo.lufs
            val clampedGainDb = min(MAX_GAIN_DB, max(MIN_GAIN_DB, requiredGainDb))
            targetGain = 10.0f.pow(clampedGainDb / 20.0f)
        } else targetGain = 1.0f

        // Calculate gain ramp parameters
        gainRampSamples = (GAIN_RAMP_MS * inputSampleRate / 1000)
        gainRampSteps = gainRampSamples
        gainRampStepSize = (targetGain - currentGain) / gainRampSteps
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputChannels = inputAudioFormat.channelCount
        inputSampleRate = inputAudioFormat.sampleRate

        // Initialize gain ramping
        currentGain = 1.0f
        targetGain = 1.0f
        gainRampSamples = (GAIN_RAMP_MS * inputSampleRate / 1000)
        gainRampSteps = 0
        gainRampStepSize = 0.0f

        // Return same format (we don't change format, only amplitude)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Get input data size and validate
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        val samplesPerChannel = remainingBytes / (inputChannels * 2)  // 16-bit samples
        val totalSamples = samplesPerChannel * inputChannels

        // Convert PCM to float samples
        val floatSamples = FloatArray(totalSamples)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        var hasDataToProcess = false
        for (i in 0 until totalSamples) {
            val pcmSample = inputBuffer.getShort().toFloat()
            floatSamples[i] = pcmSample / 32768.0f

            // Check if we have non-zero data
            if (pcmSample.toDouble().absoluteValue > 1e-6) hasDataToProcess = true
        }

        // Process samples with current gain
        val processedFloats = if (hasDataToProcess) applyGainWithRamping(floatSamples, samplesPerChannel)
        else floatSamples

        // Allocate output buffer with exact same size
        val outputBuffer = replaceOutputBuffer(remainingBytes)

        // Convert back to 16-bit PCM
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in processedFloats) {
            val clamped = max(-1.0f, min(1.0f, sample))
            val pcm = (clamped * 32767.0f).toInt().toShort()
            outputBuffer.putShort(pcm)
        }

        outputBuffer.flip()
    }

    /**
     * Apply gain with smooth ramping to avoid audio artifacts
     */
    private fun applyGainWithRamping(samples: FloatArray, frames: Int): FloatArray {
        val processed = FloatArray(samples.size)
        val samplesPerFrame = samples.size / frames

        for (frame in 0 until frames) {
            // Update gain for ramping
            val frameGain = if (gainRampSteps > 0) {
                currentGain += gainRampStepSize
                gainRampSteps--
                currentGain
            } else {
                targetGain
            }

            // Apply gain to all channels in this frame
            val frameStart = frame * samplesPerFrame
            for (i in 0 until samplesPerFrame) {
                processed[frameStart + i] = samples[frameStart + i] * frameGain
            }
        }

        return processed
    }

    override fun onReset() {
        // Reset processor state
        currentGain = 1.0f
        targetGain = 1.0f
        gainRampSteps = 0
        currentTrackLoudness = null
        audioFloatSamples.clear()
        processedSamples.clear()
    }

    override fun onFlush() {
        // Flush any buffered audio data
        audioFloatSamples.clear()
        processedSamples.clear()
    }

    /**
     * Get current processing statistics
     */
    fun getProcessingStats(): Map<String, Any> = mapOf(
        "current_gain_db" to (20 * log10(max(1e-6f, currentGain).toDouble())),
        "target_gain_db" to (20 * log10(max(1e-6f, targetGain).toDouble())),
        "ramping_active" to (gainRampSteps > 0),
        "target_loudness" to targetLoudness,
        "input_channels" to inputChannels,
        "input_sample_rate" to inputSampleRate
    )

    /**
     * Get current track loudness information
     */
    fun getCurrentTrackLoudness(): AudioLoudnessInfo? = currentTrackLoudness

    /**
     * Check if loudness normalization is currently active
     */
    fun isNormalizationActive(): Boolean = currentTrackLoudness != null &&
            targetGain != 1.0f &&
            currentTrackLoudness?.lufs?.isFinite() == true
}