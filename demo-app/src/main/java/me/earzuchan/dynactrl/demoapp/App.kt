package me.earzuchan.dynactrl.demoapp

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.earzuchan.dynactrl.exoplayer.DynamicsProcessor
import me.earzuchan.dynactrl.models.AudioLoudnessInfo
import me.earzuchan.dynactrl.LightweightLoudnessAnalyzer
import java.io.File

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

@UnstableApi
class MainActivity : ComponentActivity() {
    // 音频处理相关
    private val loudnessAnalyzer = LightweightLoudnessAnalyzer()
    private val dynamicsProcessor = DynamicsProcessor()
    private var exoPlayer: ExoPlayer? = null
    private var rawExoPlayer: ExoPlayer? = null // 未处理的播放器

    // UI状态
    private var selectedFile by mutableStateOf<File?>(null)
    private var loudnessInfo by mutableStateOf<AudioLoudnessInfo?>(null)
    private var isAnalyzing by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var isPlayingRaw by mutableStateOf(false)

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    @SuppressLint("DefaultLocale")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化播放器
        initPlayers()

        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LazyColumn(
                        Modifier.padding(16.dp),
                        contentPadding = innerPadding,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Button(
                                onClick = { filePickerLauncher.launch("audio/*") }
                            ) {
                                Text("加载文件")
                            }
                        }

                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
                                    Text("计算结果", style = MaterialTheme.typography.titleMedium)

                                    val bodySmall = MaterialTheme.typography.bodySmall

                                    when {
                                        isAnalyzing -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(Modifier.size(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("分析中...", style = bodySmall)
                                            }
                                        }

                                        loudnessInfo != null -> {
                                            val info = loudnessInfo!!
                                            Text("响度: ${"%.1f".format(info.lufs)} LUFS", style = bodySmall)
                                            // Text("峰值: ${"%.1f".format(info.truePeak)} dBFS", style = bodySmall)
                                            // Text("动态范围: ${"%.1f".format(info.lra)} dB", style = bodySmall)
                                        }

                                        selectedFile != null -> {
                                            Text("文件: ${selectedFile?.name}", style = bodySmall)
                                            Text("等待分析...", style = bodySmall)
                                        }

                                        else -> Text("请先选择音频文件", style = bodySmall)
                                    }
                                }
                            }
                        }

                        item {
                            OutlinedButton({ toggleRawPlayback() }, enabled = selectedFile != null) {
                                Text(if (isPlayingRaw) "停止原始" else "播放未处理的")
                            }
                        }

                        item {
                            Button(
                                { toggleProcessedPlayback() },
                                enabled = selectedFile != null && loudnessInfo != null
                            ) {
                                Text(if (isPlaying) "停止处理" else "播放处理后的")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initPlayers() {
        // 未处理的播放器
        rawExoPlayer = ExoPlayer.Builder(this).build()

        // 创建一个匿名的 RenderersFactory 子类来提供自定义的 AudioSink
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(this@MainActivity)
                .setAudioProcessors(arrayOf(dynamicsProcessor))
                .build()
        }

        exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()
    }

    private fun handleFileSelection(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "temp_audio_${System.currentTimeMillis()}.wav")

            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            selectedFile = tempFile
            loudnessInfo = null

            // 异步分析音频
            analyzeAudioFile(tempFile)

        } catch (e: Exception) {
            e.printStackTrace()
            // 这里可以显示错误提示
        }
    }

    private fun analyzeAudioFile(file: File) {
        isAnalyzing = true

        // 在后台线程分析
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val analysis = loudnessAnalyzer.analyzeFile(file)

                withContext(Dispatchers.Main) {
                    loudnessInfo = analysis
                    isAnalyzing = false
                    dynamicsProcessor.setTrackLoudness(analysis)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    e.printStackTrace()
                }
            }
        }
    }

    private fun toggleRawPlayback() = if (isPlayingRaw) {
        rawExoPlayer?.pause()
        isPlayingRaw = false
    } else {
        selectedFile?.let { file ->
            // 停止处理后的播放
            if (isPlaying) {
                exoPlayer?.pause()
                isPlaying = false
            }

            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            rawExoPlayer?.setMediaItem(mediaItem)
            rawExoPlayer?.prepare()
            rawExoPlayer?.play()
            isPlayingRaw = true

            // 监听播放完成
            rawExoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) isPlayingRaw = false
                }
            })
        }
    }

    private fun toggleProcessedPlayback() = if (isPlaying) {
        exoPlayer?.pause()
        isPlaying = false
    } else {
        selectedFile?.let { file ->
            // 停止原始播放
            if (isPlayingRaw) {
                rawExoPlayer?.pause()
                isPlayingRaw = false
            }

            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
            isPlaying = true

            // 监听播放完成
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) isPlaying = false
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        rawExoPlayer?.release()

        // 清理临时文件
        selectedFile?.delete()
    }
}

/*
data class AudioLoudnessInfo(
    val lufs: Float, // 类LUFS响度
    val peak: Float, // 真峰值 (dBFS)
    val dynamicRange: Float // 动态范围
)

class LightweightLoudnessAnalyzer {
    companion object {
        private const val SAMPLE_INTERVAL = 5.0 // 每5秒采样一次
        private const val SAMPLE_DURATION = 1.0 // 每次采样1秒的数据
        private const val ABSOLUTE_THRESHOLD = -70.0 // dB
    }

    fun analyzeFile(audioFile: File): AudioLoudnessInfo {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(audioFile.absolutePath)
            val audioTrackIndex = findAudioTrack(extractor)

            if (audioTrackIndex == -1) throw IllegalArgumentException("未找到音频轨道")

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationSec = durationUs / 1_000_000.0

            // 轻量级采样分析
            return performSampledAnalysis(extractor, sampleRate, durationSec)
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) return i
        }

        return -1
    }

    private fun performSampledAnalysis(
        extractor: MediaExtractor,
        sampleRate: Int,
        durationSec: Double
    ): AudioLoudnessInfo {
        val samplePoints = mutableListOf<AudioSample>()
        val sampleSize = (sampleRate * SAMPLE_DURATION).toInt() * 2 // 16位=2字节
        val buffer = ByteBuffer.allocate(sampleSize * 2) // 双倍缓冲保险

        // 计算采样点
        var currentTime = 0.0
        while (currentTime < durationSec) {
            val seekTimeUs = (currentTime * 1_000_000).toLong()
            extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            buffer.clear()
            val bytesRead = extractor.readSampleData(buffer, 0)

            if (bytesRead > 0) {
                val audioSample = extractSample(buffer, min(bytesRead, sampleSize))
                samplePoints.add(audioSample)
            }

            currentTime += SAMPLE_INTERVAL
        }

        if (samplePoints.isEmpty()) return AudioLoudnessInfo(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f)

        // 分析采样点
        return analyzeSamples(samplePoints)
    }

    private fun extractSample(buffer: ByteBuffer, size: Int): AudioSample {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.rewind()

        var sumSquares = 0.0
        var maxSample = 0.0f
        val sampleCount = size / 2 // 16位采样

        for (i in 0 until sampleCount) {
            if (buffer.remaining() >= 2) {
                val sample = buffer.short.toFloat() / 32768.0f // 标准化到[-1,1]
                sumSquares += (sample * sample).toDouble()
                maxSample = max(maxSample, abs(sample))
            }
        }

        val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
        return AudioSample(rms, maxSample.toDouble())
    }

    private fun analyzeSamples(samples: List<AudioSample>): AudioLoudnessInfo {
        // 1. 计算峰值
        val peak = samples.maxOfOrNull { it.peak }?.toFloat() ?: 0f
        val peakDb = if (peak > 0) 20.0f * log10(peak) else Float.NEGATIVE_INFINITY

        // 2. 计算类LUFS响度
        val validSamples = samples.filter { it.rms > 0 }
        val lufs = if (validSamples.isNotEmpty()) {
            // 简化的响度计算：RMS -> dB -> 类LUFS
            val meanRms = validSamples.map { it.rms }.average()
            val dbValue = 20.0 * log10(meanRms)

            // 魔法拟合
            (dbValue + 0.7).toFloat() * 0.7f
        } else Float.NEGATIVE_INFINITY

        // 3. 计算动态范围
        val dynamicRange = if (validSamples.size > 1) {
            val dbValues = validSamples.map { 20.0 * log10(it.rms) }
                .filter { it > ABSOLUTE_THRESHOLD }
                .sorted()

            if (dbValues.size >= 2) {
                val p10 = dbValues[(dbValues.size * 0.1).toInt()]
                val p90 = dbValues[(dbValues.size * 0.9).toInt()]
                (p90 - p10).toFloat()
            } else 0f
        } else 0f

        return AudioLoudnessInfo(
            lufs = lufs,
            peak = peakDb,
            dynamicRange = dynamicRange
        )
    }

    private data class AudioSample(
        val rms: Double,    // 均方根值
        val peak: Double    // 峰值
    )
}

@UnstableApi
class DynamicsProcessor : BaseAudioProcessor() {
    companion object {
        private const val TAG = "DynamicsProcessor"

        // 目标响度 (LUFS)
        private const val TARGET_LUFS = -14f // Spotify标准
    }

    // 当前歌曲的预计算信息
    private var currentLoudnessInfo: AudioLoudnessInfo? = null

    // 动态计算的参数
    private var adaptiveThreshold = -12f
    private var adaptiveMakeupGain = 0f
    private var adaptiveRatio = 3f
    private var limiterThreshold = -0.3f

    // 处理状态
    private var sampleRate = 44100
    private var channelCount = 2

    // 包络跟随器
    private var compressorEnvelope = 1f
    private var limiterEnvelope = 1f

    // 设置当前歌曲的响度信息
    fun setCurrentTrackLoudness(loudnessInfo: AudioLoudnessInfo) {
        currentLoudnessInfo = loudnessInfo
        calculateAdaptiveParameters(loudnessInfo)
    }

    private fun calculateAdaptiveParameters(info: AudioLoudnessInfo) {
        // 1. 根据响度差异设置压缩参数
        val loudnessDifference = info.lufs - TARGET_LUFS

        when {
            // 音量过大，需要较强压缩
            loudnessDifference > 6f -> {
                adaptiveThreshold = -8f
                adaptiveRatio = 5f
                adaptiveMakeupGain = -loudnessDifference * 0.8f
            }

            // 音量偏大，中等压缩
            loudnessDifference > 0f -> {
                adaptiveThreshold = -12f + loudnessDifference * 0.5f
                adaptiveRatio = 2.5f + loudnessDifference * 0.3f
                adaptiveMakeupGain = -loudnessDifference * 0.6f
            }

            // 音量正常，轻微处理
            loudnessDifference > -6f -> {
                adaptiveThreshold = -15f
                adaptiveRatio = 2f
                adaptiveMakeupGain = -loudnessDifference * 0.7f
            }

            // 音量过小，主要提升
            else -> {
                adaptiveThreshold = -20f
                adaptiveRatio = 1.5f
                adaptiveMakeupGain = min(-loudnessDifference * 0.8f, 12f) // 最大提升12dB
            }
        }

        // 2. 根据峰值设置限制器阈值
        limiterThreshold = when {
            info.peak > -1f -> -0.1f  // 接近满刻度，严格限制
            info.peak > -3f -> -0.3f  // 峰值较高，中等限制
            else -> -0.5f             // 峰值正常，宽松限制
        }

        // 3. 根据动态范围调整处理强度
        val dynamicFactor = when {
            info.dynamicRange < 5f -> 0.5f   // 低动态范围，轻处理
            info.dynamicRange > 20f -> 1.5f  // 高动态范围，强处理
            else -> 1f                       // 正常动态范围
        }

        adaptiveRatio *= dynamicFactor
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        // 获取输出缓冲区（与输入大小相同）
        val outputBuffer = replaceOutputBuffer(inputSize)

        // 计算样本数量（每个样本2字节，考虑声道数）
        val bytesPerSample = 2 // 16位音频
        val samplesCount = inputSize / (bytesPerSample * channelCount)

        // 时间常数（用于包络跟随器）
        val attackTime = 0.003f // 3ms
        val releaseTime = 0.1f   // 100ms
        val limiterAttack = 0.001f // 1ms
        val limiterRelease = 0.05f // 50ms

        // 计算包络系数
        val compAttackCoeff = exp(-1f / (attackTime * sampleRate))
        val compReleaseCoeff = exp(-1f / (releaseTime * sampleRate))
        val limAttackCoeff = exp(-1f / (limiterAttack * sampleRate))
        val limReleaseCoeff = exp(-1f / (limiterRelease * sampleRate))

        // 处理每个样本
        for (i in 0 until samplesCount) {
            // 读取左右声道数据（16位有符号整数）
            val left = inputBuffer.short.toFloat() / 32768f
            val right = if (channelCount == 2) inputBuffer.short.toFloat() / 32768f else left

            // 计算立体声RMS电平
            val rms = sqrt((left * left + right * right) / channelCount)
            val rmsDb = if (rms > 0.000001f) 20f * log10(rms) else -120f

            // === 压缩器处理 ===
            val compressorGainReduction = if (rmsDb > adaptiveThreshold) {
                val overThreshold = rmsDb - adaptiveThreshold
                val gainReduction = overThreshold * (1f - 1f / adaptiveRatio)
                -gainReduction
            } else 0f

            // 更新压缩器包络
            val targetCompGain = dbToLinear(compressorGainReduction)
            compressorEnvelope =
                if (targetCompGain < compressorEnvelope) targetCompGain + (compressorEnvelope - targetCompGain) * compAttackCoeff
                else targetCompGain + (compressorEnvelope - targetCompGain) * compReleaseCoeff

            // 应用压缩和补偿增益
            var processedLeft = left * compressorEnvelope * dbToLinear(adaptiveMakeupGain)
            var processedRight = right * compressorEnvelope * dbToLinear(adaptiveMakeupGain)

            // === 限制器处理 ===
            val peak = max(abs(processedLeft), abs(processedRight))
            val peakDb = if (peak > 0.000001f) 20f * log10(peak) else -120f

            val limiterGainReduction = if (peakDb > limiterThreshold) {
                val overThreshold = peakDb - limiterThreshold
                -overThreshold
            } else 0f

            // 更新限制器包络
            val targetLimGain = dbToLinear(limiterGainReduction)
            limiterEnvelope =
                if (targetLimGain < limiterEnvelope) targetLimGain + (limiterEnvelope - targetLimGain) * limAttackCoeff
                else targetLimGain + (limiterEnvelope - targetLimGain) * limReleaseCoeff

            // 应用限制器
            processedLeft *= limiterEnvelope
            processedRight *= limiterEnvelope

            // 最终软限幅（防止数字削波）
            processedLeft = softClip(processedLeft)
            processedRight = softClip(processedRight)

            // 转换回16位整数并写入输出缓冲区
            val outputLeft = (processedLeft * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            val outputRight = (processedRight * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()

            outputBuffer.putShort(outputLeft)
            if (channelCount == 2) outputBuffer.putShort(outputRight)
        }

        // 确保缓冲区position正确
        outputBuffer.flip()
    }

    // 辅助函数：dB到线性转换
    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    // 辅助函数：软限幅
    private fun softClip(input: Float): Float = when {
        input > 0.9f -> 0.9f + 0.1f * tanh((input - 0.9f) * 10f)
        input < -0.9f -> -0.9f + 0.1f * tanh((input + 0.9f) * 10f)
        else -> input
    }

    override fun isActive(): Boolean {
        super.isActive()
        return true
    }

    private fun clear() {
        compressorEnvelope = 1f
        limiterEnvelope = 1f
    }

    override fun onFlush() {
        super.onFlush()
        clear()
    }

    override fun onReset() {
        super.onReset()
        clear()
    }
}*/