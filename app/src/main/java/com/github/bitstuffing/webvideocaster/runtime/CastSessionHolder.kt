package com.github.bitstuffing.webvideocaster.runtime

import android.util.Log
import com.github.bitstuffing.webvideocaster.utils.CastSession

object CastSessionHolder {

    private const val TAG = "CAST_HOLDER"

    @Volatile
    private var session: CastSession? = null

    fun set(value: CastSession) {
        session = value
        Log.d(TAG, "SESSION SET ${value.device.ip}")
    }

    fun get(): CastSession? {
        Log.d(TAG, "GET session=${session != null}")
        return session
    }

    fun clear() {
        session = null
        Log.d(TAG, "CLEAR")
    }
}
