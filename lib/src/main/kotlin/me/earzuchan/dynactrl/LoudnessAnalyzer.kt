package me.earzuchan.dynactrl

import android.util.Log
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File

object LightweightLoudnessAnalyzer {
    private const val TAG = "LoudnessAnalyzer"

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        val result = DynaCtrl.nativeAnalyzeFile(audioFile.absolutePath)

        Log.i(TAG, "分析结果：$result")

        return AudioLoudnessInfo(result)
    }
}