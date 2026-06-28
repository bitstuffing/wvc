package com.github.bitstuffing.webvideocaster.utils

import android.util.Log
import com.github.bitstuffing.webvideocaster.utils.CastUtils.formatMessage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketException
import java.util.*

class CastConnectionManager(
    private val session: CastSession
) {

    private val TAG = "CAST_CONNECTION_MANAGER"
    private val socket = session.socket
    private val out = socket.outputStream
    private val input = socket.inputStream

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var requestId = session.requestId

    private val sourceId = "client-${(Math.random() * 100000).toInt()}"

    @Volatile private var running = true
    @Volatile private var receiverReady = false
    @Volatile private var mediaReady = false

    // estado interno (igual que Python globals)
    @Volatile private var player = false
    @Volatile private var mediaSessionId: Int? = session.mediaSessionId
    @Volatile private var transportId: String? = session.transportId
    @Volatile private var url: String? = null
    @Volatile private var currentTime: Double = 0.0
    private var mediaUpdateJob: Job? = null

    private val connectedTransports = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        startLoop()
        sendConnect()
        sendGetStatus()
        transportId?.let {
            log(TAG, "Re-connecting to existing transport: $it")
            sendAppConnect(it)
            sendGetMediaStatus()
        }
    }

    // -----------------------------
    // MAIN LOOP
    // -----------------------------
    private fun startLoop() {
        scope.launch {
            val buf = ByteArray(8192)

            while (running && socket.isConnected) {
                try {
                    val len = input.read(buf)
                    if (len <= 0) continue

                    val raw = String(buf, 0, len)

                    log("⬅️ RECV", raw)

                    handleReceivedData(raw)

                } catch (e: Exception) {
                    log("ERROR", e.toString())
                    break
                }
            }
        }
    }

    // -----------------------------
    // CORE DISPATCH
    // -----------------------------
    private fun handleReceivedData(raw: String) {

        val namespaceRegex = Regex("""(urn:[^"0-9(){}\s]+)""")
        val namespace = namespaceRegex.find(raw)?.value

        val json = CastUtils.extractJson(raw)
        val type = json?.optString("type") ?: ""
        val reqId = json?.optInt("requestId") ?: 0

        if (json == null) {
            when (namespace) {

                "urn:x-cast:com.google.cast.tp.heartbeat" -> {
                    log(TAG, "heartbeat...")
                    sendHeartbeatPong()
                }

                "urn:x-cast:com.google.cast.tp.deviceauth" -> {
                    log(TAG, "device auth...")
                    sendConnect()
                }

                else -> log(TAG, "unknown raw=$raw")
            }
            return
        }

        log(TAG, "📩 RECEIVED type=$type json=$json")

        when (type) {

            // ---------------- CONNECT ----------------
            "CONNECT" -> {
                log(TAG, "connect ack")
            }

            "CONNECTED" -> {
                log(TAG, "connected ok")
            }

            // ---------------- PING ----------------
            "PING" -> {
                sendHeartbeatPong()
            }

            // ---------------- RECEIVER_STATUS ----------------
            "RECEIVER_STATUS" -> {
                val app = json.optJSONObject("status")
                    ?.optJSONArray("applications")
                    ?.optJSONObject(0)

                val newTransportId = app?.optString("transportId")

                if (!newTransportId.isNullOrBlank()) {
                    transportId = newTransportId
                    session.transportId = newTransportId

                    receiverReady = true
                    log(TAG, "🎯 receiverReady=true transportId=$transportId")

                    sendGetMediaStatus()
                }
            }

            // ---------------- MEDIA_STATUS ----------------
            "MEDIA_STATUS" -> {
                val status = json.optJSONArray("status")
                    ?.optJSONObject(0)

                val mid = status?.optInt("mediaSessionId")

                if (mid != null && mid != -1) {
                    mediaSessionId = mid
                    session.mediaSessionId = mid

                    mediaReady = true
                    log(TAG, "🎬 mediaReady=true mediaSessionId=$mediaSessionId")
                }
            }

            // ---------------- SEEK ----------------
            "SEEK" -> {
                val ct = json.optDouble("currentTime", 0.0)
                currentTime = ct
            }

            // ---------------- CLOSE ----------------
            "CLOSE" -> {
                log(TAG, "close $namespace")
                if (namespace == "urn:x-cast:com.google.cast.tp.connection") {
                    transportId = null
                }
                player = false
            }

            else -> {
                log(TAG, "unknown type=$type raw=$raw")
            }
        }
    }

    private fun launchMediaReceiver() {

        val payload = JSONObject()
            .put("type", "LAUNCH")
            .put("appId", "CC1AD845")
            .put("requestId", requestId++)

        send(
            payload.toString(),
            "urn:x-cast:com.google.cast.receiver",
            "receiver-0"
        )
    }

    // -----------------------------
    // SEND HELPERS
    // -----------------------------
    private fun sendConnect() {
        send("""{"type":"CONNECT"}""", "urn:x-cast:com.google.cast.tp.connection", "receiver-0")
    }

    private fun sendGetStatus() {
        send(
            """{"type":"GET_STATUS","requestId":${requestId++}}""",
            "urn:x-cast:com.google.cast.receiver",
            "receiver-0"
        )
    }

    private fun sendGetMediaStatus() {
        val tid = transportId ?: return
        if (tid.isBlank() || tid == "receiver-0") return

        // IMPORTANTE: Conectar al transportId antes de pedir status
        sendAppConnect(tid)

        val payload = JSONObject()
            .put("type", "GET_STATUS")
            .put("requestId", requestId++)

        send(payload.toString(), "urn:x-cast:com.google.cast.media", tid)
    }

    private fun sendAppConnect(tid: String) {
        if (connectedTransports.contains(tid)) return
        log(TAG, "🔌 Sending CONNECT to app transport: $tid")
        send("""{"type":"CONNECT"}""", "urn:x-cast:com.google.cast.tp.connection", tid)
        connectedTransports.add(tid)
    }

    private fun sendHeartbeatPong() {
        // Un PONG siempre responde a receiver-0 si viene del dispositivo
        send(
            """{"type":"PONG"}""",
            "urn:x-cast:com.google.cast.tp.heartbeat",
            "receiver-0"
        )
    }

    private fun send(payload: String, namespace: String, destinationId: String) {
        scope.launch {
            try {
                val frame = formatMessage(sourceId, destinationId, namespace, payload)
                out.write(frame)
                out.flush()

                log("➡️ SEND [$namespace] to $destinationId", payload)

            } catch (e: Exception) {
                log("Error sending", e.toString())
            }
        }
    }

    private fun sendResponse(payload: String, namespace: String, destinationId: String = "receiver-0") {
        // sendResponse se usa para actuar como receiver (según Python)
        // Pero como somos sender, redirigimos a send con los IDs correctos para el rol.
        send(payload, namespace, destinationId)
    }

    private fun startMediaUpdateLoop() {
        mediaUpdateJob?.cancel()
        mediaUpdateJob = scope.launch {
            while (player && running) {
                delay(5000)
                currentTime += 5.0
                val status = generateMediaStatus(0, url, "PLAYING", currentTime)
                sendResponse(status.toString(), "urn:x-cast:com.google.cast.media", transportId ?: "receiver-0")
            }
        }
    }

    private fun generateReceiverStatus(sessionId: String, transportId: String, requestId: Int): JSONObject {
        return JSONObject().apply {
            put("requestId", requestId)
            put("type", "RECEIVER_STATUS")
            put("status", JSONObject().apply {
                put("applications", JSONArray().apply {
                    put(JSONObject().apply {
                        put("appId", "CC1AD845")
                        put("appType", "WEB")
                        put("displayName", "Default Media Receiver")
                        put("iconUrl", "")
                        put("isIdleScreen", false)
                        put("launchedFromCloud", false)
                        put("namespaces", JSONArray().apply {
                            put(JSONObject().put("name", "urn:x-cast:com.google.cast.cac"))
                            put(JSONObject().put("name", "urn:x-cast:com.google.cast.debugoverlay"))
                            put(JSONObject().put("name", "urn:x-cast:com.google.cast.broadcast"))
                            put(JSONObject().put("name", "urn:x-cast:com.google.cast.media"))
                        })
                        put("sessionId", sessionId)
                        put("statusText", "Default Media Receiver")
                        put("transportId", transportId)
                        put("universalAppId", "CC1AD845")
                    })
                })
                put("userEq", JSONObject())
                put("volume", JSONObject().apply {
                    put("controlType", "attenuation")
                    put("level", 1.0)
                    put("muted", false)
                    put("stepInterval", 0.05000000074505806)
                })
            })
        }
    }

    private fun generateMediaStatus(requestId: Int, contentId: String?, playerState: String, currentTime: Double): JSONObject {
        val encodedContentId = contentId ?: "https://example.com/default.m3u8"

        return JSONObject().apply {
            put("requestId", requestId)
            put("type", "MEDIA_STATUS")
            put("status", JSONArray().apply {
                put(JSONObject().apply {
                    put("mediaSessionId", 1)
                    put("playbackRate", 1)
                    put("playerState", playerState)
                    put("currentTime", currentTime)
                    put("supportedMediaCommands", 12303)
                    put("volume", JSONObject().apply {
                        put("level", 1)
                        put("muted", false)
                    })
                    put("media", JSONObject().apply {
                        put("contentId", encodedContentId)
                        put("streamType", "BUFFERED")
                        put("contentType", "application/x-mpegURL")
                        put("metadata", JSONObject())
                        put("customData", JSONObject().apply {
                            put("headers", JSONObject().apply {
                                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1788.0")
                            })
                        })
                    })
                    put("currentItemId", 1)
                    put("extendedStatus", JSONObject().apply {
                        put("playerState", playerState)
                        put("media", JSONObject().apply {
                            put("contentId", encodedContentId)
                            put("streamType", "BUFFERED")
                            put("contentType", "application/x-mpegURL")
                            put("metadata", JSONObject())
                        })
                        put("mediaSessionId", 1)
                    })
                    put("repeatMode", "REPEAT_OFF")
                })
            })
        }
    }

    // -----------------------------
    // API pública (NO polling externo)
    // -----------------------------
    fun isReady(): Boolean {
        return receiverReady && !transportId.isNullOrBlank()
    }

    fun getMediaSessionId(): Int? = mediaSessionId

    fun play() {
        val tid = transportId ?: return
        val mid = mediaSessionId ?: return
        val payload = JSONObject()
            .put("type", "PLAY")
            .put("mediaSessionId", mid)
            .put("requestId", requestId++)
        send(payload.toString(), "urn:x-cast:com.google.cast.media", tid)
    }

    fun pause() {
        val tid = transportId ?: return
        val mid = mediaSessionId ?: return
        val payload = JSONObject()
            .put("type", "PAUSE")
            .put("mediaSessionId", mid)
            .put("requestId", requestId++)
        send(payload.toString(), "urn:x-cast:com.google.cast.media", tid)
    }

    fun stop() {
        val tid = transportId ?: return
        val mid = mediaSessionId ?: return
        val payload = JSONObject()
            .put("type", "STOP")
            .put("mediaSessionId", mid)
            .put("requestId", requestId++)
        send(payload.toString(), "urn:x-cast:com.google.cast.media", tid)
    }

    fun seek(seconds: Double) {
        val tid = transportId ?: return
        val mid = mediaSessionId ?: return
        val payload = JSONObject()
            .put("type", "SEEK")
            .put("mediaSessionId", mid)
            .put("currentTime", seconds)
            .put("requestId", requestId++)
        send(payload.toString(), "urn:x-cast:com.google.cast.media", tid)
    }

    fun close() {
        running = false
        socket.close()
        scope.cancel()
    }

    private fun log(tag: String, msg: String) {
        Log.d("CAST_MANAGER", "$tag $msg")
    }
}