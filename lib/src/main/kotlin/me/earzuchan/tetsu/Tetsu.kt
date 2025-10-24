package me.earzuchan.tetsu

import android.util.Log
import java.lang.System
import java.nio.ByteBuffer

internal object Tetsu {
    private const val TAG = "Tetsu"

    // 常规

    @JvmStatic
    external fun nativeAnalyzeFile(filePath: String): Float

    @JvmStatic
    external fun nativeCalculateLoudness(audioArr: FloatArray, sampleRate: Int, channelCount: Int): Float

    // 初始化

    init {
        Log.i(TAG, "初始化")

        System.loadLibrary("tetsu")
    }
}