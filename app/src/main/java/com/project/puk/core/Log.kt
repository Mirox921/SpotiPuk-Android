package com.project.puk.core

import android.util.Log

/** Thin logging facade; everything lands under the SpotiPuk2 tag for adb logcat filtering. */
object Log {
    private const val TAG = "SpotiPuk2"

    fun d(where: String, msg: String) = Log.d(TAG, "[$where] $msg")
    fun i(where: String, msg: String) = Log.i(TAG, "[$where] $msg")
    fun w(where: String, msg: String) = Log.w(TAG, "[$where] $msg")
    fun e(where: String, msg: String, error: Throwable? = null) = Log.e(TAG, "[$where] $msg", error)
}
