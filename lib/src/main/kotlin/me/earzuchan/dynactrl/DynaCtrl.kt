package me.earzuchan.dynactrl

import android.util.Log
import me.earzuchan.dynactrl.utilities.Logger
import java.lang.System.loadLibrary

fun initDynaCtrl() {
    val TAG = "DynaCtrl"

    loadLibrary("dynactrl")

    Log.i(TAG, "Init Start")

    val initState = nativeInit(Logger)

    when (initState) {
        1 -> Log.i(TAG, "Init OK")
        0 -> Log.i(TAG, "STH went WRONG")
        -1 -> Log.i(TAG, "WTF")
        else -> Log.i(TAG, "Init ret Kode $initState")
    }

    val testLoggerState = nativeTestLogger()
    when (testLoggerState) {
        1 -> Log.i(TAG, "LOGGER OK")
        0 -> Log.i(TAG, "STH no BABE")
        -1 -> Log.i(TAG, "NO Logger???")
        -2 -> Log.i(TAG, "WTF")
        else -> Log.i(TAG, "LOGGER ret Kode $testLoggerState")
    }
}

private external fun nativeInit(logger: Logger): Int

private external fun nativeTestLogger(): Int