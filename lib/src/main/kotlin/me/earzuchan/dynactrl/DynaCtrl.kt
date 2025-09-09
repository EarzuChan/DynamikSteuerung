package me.earzuchan.dynactrl

import android.util.Log

object DynaCtrl {
    private const val TAG = "DynaCtrl"

    @JvmStatic
    private external fun nativeAnalyzeFile(filePath: String): Float

    fun init() {
        Log.i(TAG, "init")

        System.loadLibrary("dynactrl")

        val result = nativeAnalyzeFile("114514")
        Log.i(TAG, "1919810？$result")
    }
}