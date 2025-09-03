package me.earzuchan.dynactrl.utilities

import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File
import java.io.IOException
import java.lang.System.loadLibrary

/**
 * Lightweight analyzer that pre-processes audio files for loudness normalization.
 * Uses the Rust EBU-R128 implementation to analyze files before playback.
 */
class LightweightLoudnessAnalyzer {

    /**
     * Analyze an audio file and return loudness information.
     * This should be called before playback starts to prepare normalization parameters.
     *
     * @param audioFile The audio file to analyze
     * @return AudioLoudnessInfo containing the analysis results
     * @throws IOException if the file cannot be read or analyzed
     * @throws UnsupportedOperationException if the file format is not supported
     */
    @Throws(IOException::class)
    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        if (!audioFile.exists()) throw IOException("File does not exist: ${audioFile.absolutePath}")

        if (!audioFile.canRead()) throw IOException("Cannot read file: ${audioFile.absolutePath}")

        // Call native analysis function
        val nativePtr = nativeAnalyzeFile(audioFile.absolutePath)

        // Would be read from the file
        // Would be read from the file
        // Would be calculated from file size and format
        when (nativePtr) {
            0L -> throw IOException("Failed to analyze file: ${audioFile.absolutePath}")
            
            -1L -> throw IOException("Internal error")

            // Get the measured values from native code
            // Clean up the native pointer immediately after getting values
            // Return the analysis results
            else -> {
                val measuredLufs = AudioLoudnessInfo.nativeGetLufs(nativePtr)
                val measuredScale = AudioLoudnessInfo.nativeGetTargetScale(nativePtr)

                // Clean up the native pointer immediately after getting values
                AudioLoudnessInfo.nativeDestroy(nativePtr)

                // Return the analysis results
                return AudioLoudnessInfo(
                    lufs = measuredLufs,
                    sampleRate = 44100, // Would be read from the file
                    channels = 2,       // Would be read from the file
                    durationSeconds = 0f // Would be calculated from file size and format
                )
            }
        }
    }

    companion object {
        init { loadLibrary("dynactrl") }

        // JNI function declaration
        @JvmStatic
        external fun nativeAnalyzeFile(filePath: String): Long

        /**
         * Test function for synthetic audio analysis
         */
        @JvmStatic
        external fun analyzeSyntheticAudio(sampleRate: Int, channels: Int): AudioLoudnessInfo
    }
}