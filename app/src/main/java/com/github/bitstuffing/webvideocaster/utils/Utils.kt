package com.github.bitstuffing.webvideocaster.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.github.bitstuffing.webvideocaster.R

const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; Pixel 10 Pro Build/CP1A.260505.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/148.0.7778.225 Mobile Safari/537.36 (Mobile; afma-sdk-a-v261833035.261833035.0)"

fun openWithVlc(context: Context, url: String) {

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        setPackage("org.videolan.vlc")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {

        Toast.makeText(
            context,
            context.getString(R.string.vlc_not_installed),
            Toast.LENGTH_SHORT
        ).show()
    }
}

fun isMediaUrl(url: String): Boolean {
    return url.contains(".m3u8") ||
            url.contains(".mp4") ||
            url.contains(".mpd") ||
            url.contains(".webm") ||
            url.contains("videoplayback") ||
            url.contains("manifest") ||
            url.contains("chunk")
}