package me.earzuchan.dynactrl

import android.util.Log
import java.lang.System

object DynaCtrl {
    private const val TAG = "DynaCtrl"

    @JvmStatic
    external fun nativeAnalyzeFile(filePath: String): Float

    fun init() {
        Log.i(TAG, "init")

        System.loadLibrary("dynactrl")
    }
}