package me.earzuchan.dynactrl.models

import kotlin.math.pow

/**
 * Data class holding loudness analysis information for an audio file.
 * This replaces real-time loudness calculation during playback.
 */
data class AudioLoudnessInfo(val lufs: Float, val sampleRate: Int, val channels: Int, val durationSeconds: Float) {
    /**
     * Calculate the scaling factor needed to normalize to -18 dB LUFS reference
     */
    val targetScale: Float
        get() = if (lufs.isFinite() && lufs > -70f) 10.0.pow((-18.0 - lufs) / 20.0).toFloat()
        else 1.0f // Default scale if measurement is invalid


    companion object {
        // JNI function declarations
        @JvmStatic
        external fun nativeGetLufs(nativePtr: Long): Float

        @JvmStatic
        external fun nativeGetTargetScale(nativePtr: Long): Float

        @JvmStatic
        external fun nativeDestroy(nativePtr: Long)
    }

    // Native pointer for JNI access
    private var nativePtr: Long = 0

    /**
     * Set the native pointer from JNI call
     */
    internal fun setNativePtr(ptr: Long) = if (nativePtr != 0L) nativeDestroy(nativePtr)
    else nativePtr = ptr

    /**
     * Get LUFS value (updates from native if available)
     */
    fun getMeasuredLufs(): Float = if (nativePtr != 0L) nativeGetLufs(nativePtr) else lufs

    /**
     * Get target scale (updates from native if available)
     */
    fun getCalculatedTargetScale(): Float = if (nativePtr != 0L) nativeGetTargetScale(nativePtr) else targetScale

    /**
     * Clean up native resources
     */
    fun destroy() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
        }
    }

    override fun toString(): String =
        "AudioLoudnessInfo(lufs=%.2f, targetScale=%.3f, duration=%.1fs, channels=$channels, sampleRate=$sampleRate)".format(
            getMeasuredLufs(), getCalculatedTargetScale(), durationSeconds
        )
}