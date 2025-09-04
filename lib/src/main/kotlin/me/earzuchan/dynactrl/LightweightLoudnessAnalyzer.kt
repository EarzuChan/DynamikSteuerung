package me.earzuchan.dynactrl

import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.io.File

class LightweightLoudnessAnalyzer {
    companion object {
        private const val TAG = "LoudnessAnalyzer"
    }

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        // 对音频进行解码、间隔抽样、响度计算
        // LightweightEbuR128用法：构造器(channels: Int, sampleRate: Int)，添加样本：addSamples(samples: FloatArray)，计算：getIntegratedLoudness(): Float
    }
}