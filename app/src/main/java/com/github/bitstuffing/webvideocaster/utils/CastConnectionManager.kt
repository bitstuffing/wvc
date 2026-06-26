package com.github.bitstuffing.webvideocaster.runtime

import com.github.bitstuffing.webvideocaster.utils.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

class CastConnectionManager(
    private val session: CastSession
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastTxTime = AtomicLong(System.currentTimeMillis())
    private var running = true

    init {
        startHeartbeat()
        startReaderLoop()
    }

    // =========================
    // HEARTBEAT (PING)
    // =========================
    private fun startHeartbeat() {
        scope.launch {
            while (running) {
                delay(1000)

                val now = System.currentTimeMillis()
                val delta = now - lastTxTime.get()

                if (delta > 5000) {
                    sendRawPing()
                    lastTxTime.set(System.currentTimeMillis())
                }
            }
        }
    }

    private fun sendRawPing() {
        try {
            val ping = JSONObject()
                .put("type", "PING")
                .put("requestId", session.requestId++)

            val frame = CastUtils.formatMessage(
                session.sourceId,
                session.transportId,
                "urn:x-cast:com.google.cast.tp.heartbeat",
                ping.toString()
            )

            session.socket.outputStream.write(frame)
            session.socket.outputStream.flush()

        } catch (_: Exception) {}
    }

    // =========================
    // READER LOOP (evita bloqueo socket)
    // =========================
    private fun startReaderLoop() {
        scope.launch {
            val input = session.socket.inputStream
            val buffer = ByteArray(8192)

            while (running) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        val raw = String(buffer, 0, len)

                        if (raw.contains("PONG", true)) {
                            lastTxTime.set(System.currentTimeMillis())
                        }
                    }
                } catch (_: Exception) {
                    delay(200)
                }
            }
        }
    }

    // =========================
    // COMMAND WRAPPER
    // =========================
    private fun send(namespace: String, payload: JSONObject) {
        try {
            lastTxTime.set(System.currentTimeMillis())

            val frame = CastUtils.formatMessage(
                session.sourceId,
                session.transportId,
                namespace,
                payload.toString()
            )

            session.socket.outputStream.write(frame)
            session.socket.outputStream.flush()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================
    // API PUBLICA
    // =========================

    fun play() {
        send(
            "urn:x-cast:com.google.cast.media",
            JSONObject()
                .put("type", "PLAY")
                .put("mediaSessionId", session.mediaSessionId)
                .put("requestId", session.requestId++)
        )
    }

    fun pause() {
        send(
            "urn:x-cast:com.google.cast.media",
            JSONObject()
                .put("type", "PAUSE")
                .put("mediaSessionId", session.mediaSessionId)
                .put("requestId", session.requestId++)
        )
    }

    fun seek(seconds: Double) {
        send(
            "urn:x-cast:com.google.cast.media",
            JSONObject()
                .put("type", "SEEK")
                .put("mediaSessionId", session.mediaSessionId)
                .put("currentTime", seconds)
                .put("requestId", session.requestId++)
        )
    }

    fun stop() {
        send(
            "urn:x-cast:com.google.cast.media",
            JSONObject()
                .put("type", "STOP")
                .put("mediaSessionId", session.mediaSessionId)
                .put("requestId", session.requestId++)
        )
    }

    fun close() {
        running = false
        scope.cancel()

        try {
            session.socket.close()
        } catch (_: Exception) {}
    }
}