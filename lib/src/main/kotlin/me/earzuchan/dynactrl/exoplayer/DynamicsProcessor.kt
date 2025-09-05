package me.earzuchan.dynactrl.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class DynamicsProcessor : BaseAudioProcessor() {
    fun setCurrentTrackLoudness(loudnessInfo: AudioLoudnessInfo) {
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
    }

    override fun onFlush() {
    }

    override fun onReset() {
    }
}