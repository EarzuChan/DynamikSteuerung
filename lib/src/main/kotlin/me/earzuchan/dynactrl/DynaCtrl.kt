package me.earzuchan.dynactrl

import android.util.Log
import java.lang.System
import java.nio.ByteBuffer

object DynaCtrl {
    private const val TAG = "DynaCtrl"

    // 常规

    @JvmStatic
    external fun nativeAnalyzeFile(filePath: String): Float

    @JvmStatic
    external fun nativeCalculateLoudness(audioArr: FloatArray, sampleRate: Int, channelCount: Int): Float

    // 初始化

    fun init() {
        Log.i(TAG, "init")

        System.loadLibrary("dynactrl")
    }
}