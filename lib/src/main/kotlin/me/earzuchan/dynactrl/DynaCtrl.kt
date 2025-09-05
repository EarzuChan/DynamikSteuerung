package me.earzuchan.dynactrl

import android.util.Log
import java.lang.System.loadLibrary

object DynaCtrl {
    private const val TAG = "DynaCtrl"

    // private external fun getStringFromNative(): String
    // private external fun sayHello()
    private external fun nativeInit(): Boolean

    fun init() {
        Log.i(TAG, "init")

        loadLibrary("dynactrl")
        val result = nativeInit()

        Log.i(TAG, "OK吗？$result")

        nativeInit() // Once Again
    }
}