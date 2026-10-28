package com.jfrmedia.switcher.core.util

import android.util.Log

/** Log breve y filtrable: adb logcat -s JFR. */
object L {
    private const val TAG = "JFR"
    var enabled: Boolean = true

    fun i(scope: String, msg: String) {
        if (enabled) Log.i(TAG, "[$scope] $msg")
    }

    fun w(scope: String, msg: String) {
        if (enabled) Log.w(TAG, "[$scope] $msg")
    }

    fun e(scope: String, msg: String, t: Throwable? = null) {
        if (enabled) {
            if (t != null) Log.e(TAG, "[$scope] $msg", t) else Log.e(TAG, "[$scope] $msg")
        }
    }
}
