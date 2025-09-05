package me.earzuchan.dynactrl

import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File

class LightweightLoudnessAnalyzer {
    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        return AudioLoudnessInfo(-70f)
    }
}