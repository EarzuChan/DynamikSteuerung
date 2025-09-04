package me.earzuchan.dynactrl.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class DynamicsProcessor : BaseAudioProcessor() {
    companion object {
        private const val TAG = "DynamicsProcessor"

        private const val TARGET_LUFS = -14f // Spotify
    }

    // 设置当前音轨的响度信息，以计算处理（增益和限制）参数
    fun setCurrentTrackLoudness(loudnessInfo: AudioLoudnessInfo) // 读取AudioLoudnessInfo的lufs字段

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat

    // 进行增益、限制
    override fun queueInput(inputBuffer: ByteBuffer) {
        // 你需要通过replaceOutputBuffer(size)获得给你写出用的缓冲区
    }
}