
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
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.github.bitstuffing.webvideocaster.R
import com.github.bitstuffing.webvideocaster.utils.CastConnectionManager
import com.github.bitstuffing.webvideocaster.utils.CastMediaInfo
import com.github.bitstuffing.webvideocaster.utils.CastSession
import kotlinx.coroutines.*
import com.github.bitstuffing.webvideocaster.utils.CastDevice
import com.github.bitstuffing.webvideocaster.utils.CastUtils

class CastService : Service() {

    private var discoveryJob: Job? = null

    companion object {
        private const val TAG = "CAST_SERVICE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "cast_service"

        const val ACTION_PLAY = "cast_play"
        const val ACTION_PAUSE = "cast_pause"
        const val ACTION_STOP = "cast_stop"
    }

    fun monitorDevice(device: CastDevice) {
        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            while (isActive) {
                val currentSession = session
                if (currentSession == null || !currentSession.socket.isConnected || currentSession.socket.isClosed) {
                    try {
                        val recovered = withContext(Dispatchers.IO) {
                            CastUtils.getSession(device)
                        }
                        if (recovered != null) {
                            withContext(Dispatchers.Main.immediate) {
                                attachSession(recovered, "")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Session recovery failed", e)
                    }
                }
                delay(2000)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CastService = this@CastService
    }

    private val binder = LocalBinder()

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var statusJob: Job? = null

    private var manager: CastConnectionManager? = null
    private var session: CastSession? = null

    @Volatile
    private var mediaInfo: CastMediaInfo? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var hasMedia = false

    @Volatile
    private var playerState = CastConnectionManager.CastPlayerState.UNKNOWN

    @Volatile
    private var receiverName: String? = null

    private var lastNotificationKey: String? = null

    private lateinit var mediaSession: MediaSessionCompat

    private var mediaSessionInitialized = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMediaSession()
        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    // --------------------------------------------------
    // ATTACH CAST SESSION
    // --------------------------------------------------
    fun attachSession(session: CastSession, url: String) {
        Log.d(TAG, "Attaching Cast session")
        statusJob?.cancel()
        manager?.close()
        this.session = session
        this.manager = CastConnectionManager(session)
        mediaInfo = null
        receiverName = null
        isPlaying = false
        hasMedia = false
        playerState = CastConnectionManager.CastPlayerState.UNKNOWN
        lastNotificationKey = null
        mediaSession.isActive = true
        discoveryJob?.cancel()
        discoveryJob = null
        startForeground(NOTIFICATION_ID,buildNotification())
        startStatusLoop()
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "WebVideoCaster")
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }
                override fun onPause() {
                    pause()
                }
                override fun onStop() {
                    stopCasting()
                }
            }
        )
        mediaSessionInitialized = true
        Log.d(TAG, "MediaSession created")
    }

    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            Log.d(TAG, "Status monitoring started")
            while (isActive) {
                try {
                    val currentManager = manager
                    if (currentManager == null) {
                        delay(1000)
                        continue
                    }
                    val info = currentManager.getMediaInfo()
                    if (info == null) {
                        delay(500)
                        continue
                    }
                    val newHasMedia = info.playerState != CastConnectionManager.CastPlayerState.IDLE && info.playerState != CastConnectionManager.CastPlayerState.UNKNOWN
                    val newIsPlaying = newHasMedia && info.playerState == CastConnectionManager.CastPlayerState.PLAYING
                    val newReceiverName = currentManager.getReceiverName()
                    mediaInfo = info
                    playerState = info.playerState
                    hasMedia = newHasMedia
                    isPlaying = newIsPlaying
                    receiverName = newReceiverName
                    val notificationKey = listOf(
                        info.playerState,
                        info.title,
                        info.subtitle,
                        info.artist,
                        info.imageUrl,
                        info.isLive,
                        info.resolution,
                        newReceiverName,
                        currentManager.getMediaSessionId()
                    ).joinToString("|")
                    withContext(Dispatchers.Main.immediate) {
                        updateMediaSession()
                        if (notificationKey != lastNotificationKey) {
                            lastNotificationKey = notificationKey
                            updateNotification()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Status monitoring error",e)
                }
                delay(1000)
            }
        }
    }

    private fun updateMediaSession() {
        val info = mediaInfo
        if (info == null) return
        val title = info.title?: receiverName?: getString(R.string.notification_title)
        val artist = info.artist?: info.subtitle?: "Chromecast"
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,artist)
        if (info.duration > 0 && !info.isLive) {
            metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (info.duration * 1000).toLong())
        }
        mediaSession.setMetadata(metadata.build())
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        val state = when (info.playerState) {
            CastConnectionManager.CastPlayerState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            CastConnectionManager.CastPlayerState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            CastConnectionManager.CastPlayerState.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            CastConnectionManager.CastPlayerState.IDLE -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions).setState(state,
                if (info.currentTime >= 0) {
                    (info.currentTime * 1000).toLong()
                } else {
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
                },
                if (state == PlaybackStateCompat.STATE_PLAYING) {
                    1f
                } else {
                    0f
                }
            ).build()
        )
    }

    // --------------------------------------------------
    // CONTROLS
    // --------------------------------------------------
    fun play() {
        if (!hasMedia) return
        Log.d(TAG, "Play requested")
        manager?.play()
    }

    fun pause() {
        if (!hasMedia) return
        Log.d(TAG, "Pause requested")
        manager?.pause()
    }

    fun stopCasting() {
        Log.d(TAG, "Stop requested")
        manager?.stop()
        isPlaying = false
    }

    // --------------------------------------------------
    // NOTIFICATION
    // --------------------------------------------------
    private fun buildNotification(): Notification {

        val info = mediaInfo
        val title = info?.title?: receiverName?: getString(R.string.notification_title)
        val statusText = when {
            !hasMedia -> getString(R.string.notification_no_media)
            info?.isLive == true -> "LIVE"
            isPlaying -> getString(R.string.notification_playing)
            playerState == CastConnectionManager.CastPlayerState.BUFFERING -> "Buffering..."
            else -> getString(R.string.notification_paused)
        }
        val details = buildList {
            add(statusText)
            info?.resolution?.let {
                add(it)
            }
            info?.subtitle?.let {
                add(it)
            }
        }.joinToString(" · ")

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, getString(R.string.pause), actionIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, getString(R.string.play), actionIntent(ACTION_PLAY))
        }
        val stopAction = NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_STOP))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(details)
            .setSubText(receiverName)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(hasMedia)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1)
        ).build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        if (hasMedia) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, CastService::class.java
        ).apply {
            this.action = action
        }

        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stopCasting()
        }
        return START_STICKY
    }

    // --------------------------------------------------
    // CHANNEL
    // --------------------------------------------------
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.cast_service_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = "Chromecast media controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------
    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        statusJob?.cancel()
        serviceScope.cancel()
        manager?.close()
        manager = null
        session = null
        if (mediaSessionInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        discoveryJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}