package com.github.bitstuffing.webvideocaster.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.bitstuffing.webvideocaster.utils.CastConnectionManager
import com.github.bitstuffing.webvideocaster.utils.CastSession
import kotlinx.coroutines.*

class CastService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CastService = this@CastService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d("CAST_SERVICE", "onBind CALLED")
        return binder
    }

    // ----------------------------
    // CORE
    // ----------------------------
    private var manager: CastConnectionManager? = null
    private var session: CastSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusJob: Job? = null

    // ----------------------------
    // NOTIFICATION STATE
    // ----------------------------
    private var isPlaying = false
    private var hasMedia = false
    private var lastMediaSessionId: Int? = null

    private val channelId = "cast_service"

    companion object {
        private const val ACTION_PLAY = "cast_play"
        private const val ACTION_PAUSE = "cast_pause"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("CAST_SERVICE", "Service created")
        createNotificationChannel()
    }

    // --------------------------------------------------
    // ATTACH SESSION
    // --------------------------------------------------
    fun attachSession(session: CastSession, url: String) {

        Log.d("CAST_SERVICE", "attachSession()")

        this.session = session
        this.manager = CastConnectionManager(session)

        hasMedia = true
        isPlaying = true

        startStatusLoop()
        startForeground(1, buildNotification())
    }

    // --------------------------------------------------
    // LOOP STATUS (SOLO DEBUG + UPDATE UI STATE)
    // --------------------------------------------------
    private fun startStatusLoop() {

        statusJob?.cancel()

        statusJob = serviceScope.launch {

            Log.d("CAST_SERVICE", "status loop started")

            while (isActive) {

                val m = manager

                if (m == null) {
                    Log.d("CAST_SERVICE", "manager = null")
                } else {

                    val ready = m.isReady()
                    val mediaId = m.getMediaSessionId()

                    val newPlaying = ready && mediaId != null

                    if (mediaId != lastMediaSessionId) {
                        Log.d("CAST_SERVICE", "MEDIA CHANGE → $mediaId")
                        lastMediaSessionId = mediaId
                    }

                    if (newPlaying != isPlaying) {
                        isPlaying = newPlaying
                        Log.d("CAST_SERVICE", "PLAY STATE CHANGED → $isPlaying")
                    }

                    hasMedia = ready

                    Log.d(
                        "CAST_SERVICE",
                        "loop → ready=$ready mediaSessionId=$mediaId playing=$isPlaying"
                    )
                }

                delay(1000)
            }
        }
    }

    // --------------------------------------------------
    // CONTROLS
    // --------------------------------------------------
    fun play() {
        manager?.play()
        isPlaying = true
        updateNotification()
    }

    fun pause() {
        manager?.pause()
        isPlaying = false
        updateNotification()
    }

    // --------------------------------------------------
    // NOTIFICATION
    // --------------------------------------------------
    private fun buildNotification(): Notification {

        val statusText = when {
            !hasMedia -> "No media"
            isPlaying -> "Playing"
            else -> "Paused"
        }

        val playPauseAction =
            if (isPlaying) {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    actionIntent(ACTION_PAUSE)
                )
            } else {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "Play",
                    actionIntent(ACTION_PLAY)
                )
            }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cast active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOnlyAlertOnce(true)
            .addAction(playPauseAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        startForeground(1, buildNotification())
    }

    // --------------------------------------------------
    // ACTION HANDLING
    // --------------------------------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
        }

        return START_STICKY
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, CastService::class.java).apply {
            this.action = action
        }

        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // --------------------------------------------------
    // CHANNEL
    // --------------------------------------------------
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Cast Service",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()
        statusJob?.cancel()
        serviceScope.cancel()
        manager?.close()
    }
}