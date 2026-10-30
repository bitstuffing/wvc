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
import com.github.bitstuffing.webvideocaster.R
import com.github.bitstuffing.webvideocaster.utils.CastConnectionManager
import com.github.bitstuffing.webvideocaster.utils.CastSession
import kotlinx.coroutines.*
import com.github.bitstuffing.webvideocaster.utils.CastDevice
import com.github.bitstuffing.webvideocaster.utils.CastUtils

class CastService : Service() {

    private val binder = LocalBinder()
    private var discoveryJob: Job? = null

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
    @Volatile
    private var isPlaying = false

    @Volatile
    private var hasMedia = false

    @Volatile
    private var playerState = CastConnectionManager.CastPlayerState.UNKNOWN

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

    fun monitorDevice(device: CastDevice) {
        Log.d("CAST_SERVICE", "Starting monitor for ${device.friendlyName}")
        startMonitoring()
    }

    private fun startMonitoring() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            Log.d("CAST_SERVICE", "Monitor started")
            while (isActive) {
                try {
                    val currentSession = session
                    if (currentSession == null) {
                        Log.d("CAST_SERVICE", "No session available")
                        delay(1000)
                        continue
                    }
                    val playerState = withContext(Dispatchers.IO) {
                        CastUtils.getMediaPlayerState(currentSession)
                    }
                    Log.d("CAST_SERVICE", "Player state received: $playerState")
                    var changed = false
                    when (playerState) {
                        "PLAYING" -> {
                            if (!isPlaying || !hasMedia) {
                                isPlaying = true
                                hasMedia = true
                                changed = true
                            }
                        }
                        "PAUSED" -> {
                            if (isPlaying || !hasMedia) {
                                isPlaying = false
                                hasMedia = true
                                changed = true
                            }
                        }
                        "IDLE" -> {
                            if (isPlaying || hasMedia) {
                                isPlaying = false
                                hasMedia = false
                                changed = true
                            }
                        }
                        null -> {
                            Log.d(
                                "CAST_SERVICE",
                                "Unable to retrieve player state; keeping previous state"
                            )
                        }
                        else -> {
                            Log.d(
                                "CAST_SERVICE",
                                "Unknown player state: $playerState"
                            )
                        }
                    }

                    if (changed) {
                        Log.d("CAST_SERVICE", "Updating notification: playing=$isPlaying, hasMedia=$hasMedia")
                        updateNotification()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("CAST_SERVICE", "Error monitoring Cast playback", e)
                }
                delay(2000)
            }
        }
    }

    // --------------------------------------------------
    // ATTACH SESSION
    // --------------------------------------------------
    fun attachSession(session: CastSession, url: String) {
        Log.d("CAST_SERVICE", "attachSession()")
        discoveryJob?.cancel()
        discoveryJob = null
        statusJob?.cancel()
        manager?.close()
        this.session = session
        this.manager = CastConnectionManager(session)
        lastMediaSessionId = null
        playerState = CastConnectionManager.CastPlayerState.UNKNOWN
        hasMedia = false
        isPlaying = false
        startForeground(1, buildNotification())
        startStatusLoop()
    }

    // --------------------------------------------------
    // LOOP STATUS (SOLO DEBUG + UPDATE UI STATE)
    // --------------------------------------------------
    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            while (isActive) {
                val m = manager
                if (m != null) {
                    val mediaId = m.getMediaSessionId()
                    val state = m.getPlayerState()
                    val newHasMedia = mediaId != null && state != CastConnectionManager.CastPlayerState.IDLE
                    val newIsPlaying = newHasMedia && state == CastConnectionManager.CastPlayerState.PLAYING
                    val changed = mediaId != lastMediaSessionId || state != playerState || newHasMedia != hasMedia || newIsPlaying != isPlaying
                    lastMediaSessionId = mediaId
                    playerState = state
                    hasMedia = newHasMedia
                    isPlaying = newIsPlaying

                    if (changed) {
                        Log.d("CAST_SERVICE", "State changed: state=$state, hasMedia=$hasMedia, playing=$isPlaying")
                        withContext(Dispatchers.Main.immediate) {
                            updateNotification()
                        }
                    }
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
            !hasMedia -> getString(R.string.notification_no_media)
            isPlaying -> getString(R.string.notification_playing)
            else -> getString(R.string.notification_paused)
        }

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, getString(R.string.pause), actionIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, getString(R.string.play), actionIntent(ACTION_PLAY))
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOnlyAlertOnce(true)
            .addAction(playPauseAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        getSystemService(NotificationManager::class.java).notify(1, notification)
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
        return PendingIntent.getService(this,action.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // --------------------------------------------------
    // CHANNEL
    // --------------------------------------------------
    private fun createNotificationChannel() {
        val channel = NotificationChannel(channelId,getString(R.string.cast_service_channel_name),NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------
    override fun onDestroy() {
        discoveryJob?.cancel()
        statusJob?.cancel()
        serviceScope.cancel()
        manager?.close()
        super.onDestroy()
    }
}