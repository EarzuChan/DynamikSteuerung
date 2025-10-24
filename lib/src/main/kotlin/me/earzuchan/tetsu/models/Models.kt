package me.earzuchan.tetsu.models

data class AudioLoudnessInfo(val lufs: Float)

internal class AudioData(
    val samples: FloatArray,
    val sampleRate: Int,
    val channelCount: Int
)