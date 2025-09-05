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
import androidx.core.net.toUri
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
    private var processedPlayer: ExoPlayer? = null
    private var rawPlayer: ExoPlayer? = null

    // UI状态
    private var selectedFile by mutableStateOf<File?>(null)
    private var loudnessInfo by mutableStateOf<AudioLoudnessInfo?>(null)
    private var isAnalyzing by mutableStateOf(false)
    private var isPlayingProcessed by mutableStateOf(false)
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
                            Button({ filePickerLauncher.launch("audio/*") }) {
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
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
                                Text(if (isPlayingRaw) "停止播放原始" else "播放原始音频")
                            }
                        }

                        item {
                            Button(
                                { toggleProcessedPlayback() },
                                enabled = selectedFile != null && loudnessInfo != null
                            ) {
                                Text(if (isPlayingProcessed) "停止播放处理后" else "播放处理后音频")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initPlayers() {
        // 未处理的播放器
        rawPlayer = ExoPlayer.Builder(this).build()

        // 创建一个匿名的 RenderersFactory 子类来提供自定义的 AudioSink
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(c: Context, b: Boolean, b1: Boolean): AudioSink = DefaultAudioSink.Builder(c)
                .setAudioProcessors(arrayOf(dynamicsProcessor))
                .build()
        }

        processedPlayer = ExoPlayer.Builder(this, renderersFactory).build()
    }

    private fun handleFileSelection(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "temp_audio_${System.currentTimeMillis()}.wav")

            inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }

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
                    dynamicsProcessor.setCurrentTrackLoudness(analysis)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    e.printStackTrace()
                }
            }
        }
    }

    private fun stopRaw(){
        rawPlayer?.pause()
        isPlayingRaw = false
    }

    private fun stopProcessed(){
        processedPlayer?.pause()
        isPlayingProcessed = false
    }

    fun ExoPlayer.setAndPlay(file: File) {
        setMediaItem(MediaItem.fromUri(file.toUri()))
        prepare()
        play()
    }

    private fun toggleRawPlayback() = if (isPlayingRaw) stopRaw() else selectedFile?.let { file ->
        // 停止处理后的播放
        if (isPlayingProcessed) stopProcessed()

        rawPlayer?.setAndPlay(file)
        isPlayingRaw = true

        // 监听播放完成
        rawPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) isPlayingRaw = false
            }
        })
    }

    private fun toggleProcessedPlayback() = if (isPlayingProcessed) stopProcessed() else selectedFile?.let { file ->
        // 停止原始播放
        if (isPlayingRaw) stopRaw()

        processedPlayer?.setAndPlay(file)
        isPlayingProcessed = true

        // 监听播放完成
        processedPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) isPlayingProcessed = false
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        processedPlayer?.release()
        rawPlayer?.release()

        // 清理临时文件
        selectedFile?.delete()
    }
}