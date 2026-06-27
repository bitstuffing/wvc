package com.github.bitstuffing.webvideocaster.runtime

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.bitstuffing.webvideocaster.utils.CastDevice
import com.github.bitstuffing.webvideocaster.utils.CastUtils
import kotlinx.coroutines.*

class CastService : Service() {

    companion object {
        private const val TAG = "CAST_SERVICE"
        private const val CHANNEL_ID = "cast_remote"
        private const val NOTIFICATION_ID = 1001
        private const val TEST_URL = "https://directo.tuwebtv.es/canal1.m3u8"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var manager: CastConnectionManager? = null
    private var connecting = false
    private var device: CastDevice? = CastDevice(ip = "192.168.1.255", friendlyName = "Chromecast")

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SERVICE CREATED")
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Buscando Chromecast..."))

        CoroutineScope(Dispatchers.IO).launch {
            device = CastUtils.searchDevices(applicationContext).firstOrNull()
            startLoop()
        }
    }


    private fun startLoop() {
        scope.launch {
            while(isActive) {
                val session = CastSessionHolder.get()

                if(session == null) {
                    Log.d(TAG,"No session -> connecting")
                    createSession()
                } else {
                    if(manager == null) {
                        Log.d(TAG,"Creating manager")
                        manager = CastConnectionManager(session)
                    }

                    updateNotification(session.device.friendlyName)
                }

                delay(5000)
            }
        }
    }

    private suspend fun createSession() {

        if(connecting) return

        connecting = true

        try {

            if(device == null) {
                Log.d(TAG,"No Chromecast found")
                return
            }

            Log.d(TAG,"Found ${device!!.ip}")

            CastUtils.castUrl(device!!,TEST_URL) { result ->

                if(result != null) {
                    Log.d(TAG,"SESSION CREATED")
                    CastSessionHolder.set(result)
                } else {
                    Log.d(TAG,"SESSION FAILED")
                }
            }

        } finally {
            connecting = false
        }
    }

    private fun buildNotification(text:String):Notification {
        return NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Web Video Caster")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text:String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID,buildNotification(text))
    }

    private fun createChannel() {

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Chromecast Remote",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onDestroy() {
        Log.d(TAG,"DESTROY")
        manager?.close()
        CastSessionHolder.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent:Intent?):IBinder? = null
}