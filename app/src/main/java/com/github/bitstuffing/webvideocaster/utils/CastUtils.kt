package com.github.bitstuffing.webvideocaster.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.io.BufferedOutputStream
import java.io.StringReader
import java.net.*
import javax.net.ssl.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.SecureRandom
import org.json.JSONObject

data class CastDevice(
    val ip: String,
    val friendlyName: String,
    val modelName: String? = null,
    val udn: String? = null
)

data class CastSession(
    val device: CastDevice,
    val sourceId: String,
    val transportId: String,
    val sessionId: String,
    val mediaSessionId: Int,
    val socket: SSLSocket,
    var requestId: Int
)

object CastUtils {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    private const val PLAYER_ID = "CC1AD845"

    // =========================
    // MULTICAST
    // =========================
    fun acquireMulticastLock(context: Context): WifiManager.MulticastLock {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifi.createMulticastLock("cast_discovery").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    // =========================
    // DISCOVERY
    // =========================
    suspend fun searchDevices(timeoutMs: Long = 3000L): List<CastDevice> {
        return withContext(Dispatchers.IO) {
            val devices = linkedMapOf<String, CastDevice>()

            val found = ssdpDiscover(timeoutMs)
            for (device in found) {
                devices[device.ip] = device
            }

            devices.values.sortedBy { it.friendlyName.lowercase() }
        }
    }

    private fun ssdpDiscover(timeoutMs: Long): List<CastDevice> {
        val found = mutableListOf<CastDevice>()

        val socket = DatagramSocket().apply {
            reuseAddress = true
            soTimeout = timeoutMs.toInt()
        }

        try {
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 1\r\n")
                append("ST: urn:dial-multiscreen-org:service:dial:1\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)

            socket.send(
                DatagramPacket(
                    request,
                    request.size,
                    InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT)
                )
            )

            val buffer = ByteArray(8192)
            val start = System.currentTimeMillis()

            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val raw = String(packet.data, packet.offset, packet.length)

                    val headers = parseHttpHeaders(raw)
                    val location = headers["location"] ?: continue
                    val ip = runCatching { URL(location).host }.getOrNull() ?: continue

                    fetchDeviceDesc(ip)?.let { found.add(it) }

                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            socket.close()
        }

        return found
    }

    private fun fetchDeviceDesc(ip: String): CastDevice? {
        val ports = listOf(8008, 8009)

        for (port in ports) {
            try {
                val body = httpGet("http://$ip:$port/ssdp/device-desc.xml")

                if (body.contains("Chromecast", true) || body.contains("Google", true)) {
                    return CastDevice(
                        ip = ip,
                        friendlyName = extractXmlTag(body, "friendlyName") ?: "Chromecast",
                        modelName = extractXmlTag(body, "modelName"),
                        udn = extractXmlTag(body, "UDN")
                    )
                }
            } catch (_: Exception) {}

            try {
                val body = httpGet("http://$ip:$port/setup/eureka_info")
                if (body.contains("\"name\"")) {
                    val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: "Chromecast"

                    return CastDevice(ip, name)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1200
            readTimeout = 1200
            requestMethod = "GET"
            setRequestProperty("User-Agent", "curl/7.54.0")
        }

        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parseHttpHeaders(raw: String): Map<String, String> {
        return raw.lineSequence()
            .drop(1)
            .mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null
                else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }
            .toMap()
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            doc.getElementsByTagName(tag)
                .item(0)
                ?.textContent
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun extractJson(raw: String): JSONObject? {

        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')

        if (start < 0 || end <= start)
            return null

        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    fun formatMessage(
        sourceId: String,
        destinationId: String?,
        namespace: String,
        data: String?
    ): ByteArray {

        fun formatFieldId(fieldNo: Int, fieldType: Int): Int {
            return (fieldNo shl 3) or fieldType
        }

        fun formatVarint(value: Int): ByteArray {
            var v = value
            val result = mutableListOf<Byte>()

            while (v > 127) {
                result.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }

            result.add((v and 0x7F).toByte())

            return result.toByteArray()
        }

        fun formatIntField(fieldNumber: Int, value: Int): ByteArray {
            return byteArrayOf(
                formatFieldId(fieldNumber, 0).toByte(),
                value.toByte()
            )
        }

        fun formatStringField(fieldNumber: Int, value: String): ByteArray {

            val bytes = value.toByteArray(Charsets.UTF_8)

            return byteArrayOf(
                formatFieldId(fieldNumber, 2).toByte()
            ) +
                    formatVarint(bytes.size) +
                    bytes
        }

        fun prependLengthHeader(msg: ByteArray): ByteArray {

            val len = msg.size

            return byteArrayOf(
                ((len ushr 24) and 0xFF).toByte(),
                ((len ushr 16) and 0xFF).toByte(),
                ((len ushr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            ) + msg
        }

        var msg = byteArrayOf()

        // protocol version
        msg += formatIntField(1, 0)

        // source id
        msg += formatStringField(2, sourceId)

        // destination id
        if (destinationId != null) {
            msg += formatStringField(3, destinationId)
        }

        // namespace
        msg += formatStringField(4, namespace)

        // payload type = STRING (0)
        msg += formatIntField(5, 0)

        // payload utf8
        if (!data.isNullOrEmpty()) {
            msg += formatStringField(6, data)
        }

        return prependLengthHeader(msg)
    }

    fun castGetStatus(session: CastSession): JSONObject? {

        return try {

            val out = session.socket.outputStream
            val input = session.socket.inputStream

            val requestId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

            val payload = JSONObject()
                .put("type", "GET_STATUS")
                .put("requestId", session.requestId++)

            val frame = formatMessage(
                session.sourceId,
                session.transportId, // receiver/app channel activo
                "urn:x-cast:com.google.cast.receiver",
                payload.toString()
            )

            out.write(frame)
            out.flush()

            // leer respuesta
            val buf = ByteArray(8192)
            val len = input.read(buf)

            if (len <= 0) return null

            val raw = String(buf, 0, len)
            extractJson(raw)

        } catch (e: Exception) {
            Log.e("CAST_DEBUG", "GET_STATUS ERROR", e)
            null
        }
    }

    fun castPause(session: CastSession) {
        val payload = JSONObject()
            .put("type", "PAUSE")
            .put("mediaSessionId", session.mediaSessionId)
            .put("requestId", session.requestId++)
        val frame = formatMessage(
            session.sourceId,
            session.transportId,
            "urn:x-cast:com.google.cast.media",
            payload.toString()
        )
        session.socket.outputStream.write(frame)
        session.socket.outputStream.flush()
    }

    fun castPlay(session: CastSession) {
        val payload = JSONObject()
            .put("type", "PLAY")
            .put("mediaSessionId", session.mediaSessionId)
            .put("requestId", session.requestId++)
        val frame = formatMessage(
            session.sourceId,
            session.transportId,
            "urn:x-cast:com.google.cast.media",
            payload.toString()
        )
        session.socket.outputStream.write(frame)
        session.socket.outputStream.flush()
    }

    fun castStop(session: CastSession) {

        val out = session.socket.outputStream

        val payload = JSONObject()
            .put("type", "STOP")
            .put("mediaSessionId", session.mediaSessionId)
            .put("requestId", session.requestId++)

        val frame = formatMessage(
            session.sourceId,
            session.transportId,
            "urn:x-cast:com.google.cast.media",
            payload.toString()
        )

        out.write(frame)
        out.flush()
    }

    fun castSeek(session: CastSession, seconds: Double) {

        val payload = JSONObject()
            .put("type", "SEEK")
            .put("mediaSessionId", session.mediaSessionId)
            .put("currentTime", seconds)
            .put("requestId", System.currentTimeMillis().toInt())

        val frame = formatMessage(
            session.sourceId,
            session.transportId,
            "urn:x-cast:com.google.cast.media",
            payload.toString()
        )

        session.socket.outputStream.write(frame)
        session.socket.outputStream.flush()
    }

    fun castForward(session: CastSession, seconds: Int) {

        val status = castGetStatus(session) ?: return

        val currentTime =
            status.optJSONArray("status")
                ?.optJSONObject(0)
                ?.optDouble("currentTime")
                ?: return

        val newTime = currentTime + seconds

        castSeek(session, newTime)
    }

    fun castRewind(session: CastSession, seconds: Int) {

        val status = castGetStatus(session) ?: return

        val currentTime =
            status.optJSONArray("status")
                ?.optJSONObject(0)
                ?.optDouble("currentTime")
                ?: return

        val newTime = (currentTime - seconds).coerceAtLeast(0.0)

        castSeek(session, newTime)
    }

    fun castUrl(
        device: CastDevice,
        url: String,
        onResult: (CastSession?) -> Unit
    ) {

        Thread {

            var socket: SSLSocket? = null
            var resultSession: CastSession? = null

            try {
                socket = createSecureSocket(device.ip)

                val out = socket.outputStream
                val input = socket.inputStream

                val sourceId = "sender-0"
                var destinationId = "receiver-0"
                var requestId = 1

                fun send(frame: ByteArray, tag: String) {
                    out.write(frame)
                    out.flush()
                }

                fun recv(tag: String): String {
                    val buf = ByteArray(8192)
                    val len = input.read(buf)
                    if (len <= 0) return ""
                    return String(buf, 0, len)
                }

                fun recvJson(tag: String): JSONObject? {
                    repeat(10) {
                        val json = extractJson(recv(tag))
                        if (json != null) return json
                    }
                    return null
                }

                // CONNECT
                send(
                    formatMessage(
                        sourceId,
                        destinationId,
                        "urn:x-cast:com.google.cast.tp.connection",
                        """{"type":"CONNECT"}"""
                    ),
                    "CONNECT"
                )
                recv("CONNECT")

                // GET STATUS
                send(
                    formatMessage(
                        sourceId,
                        destinationId,
                        "urn:x-cast:com.google.cast.receiver",
                        """{"type":"GET_STATUS","requestId":${requestId++}}"""
                    ),
                    "GET_STATUS"
                )
                recv("GET_STATUS")

                var sessionId: String? = null
                var transportId: String? = null
                var mediaSessionId: Int? = null

                repeat(5) { attempt ->

                    send(
                        formatMessage(
                            sourceId,
                            destinationId,
                            "urn:x-cast:com.google.cast.receiver",
                            """{"type":"LAUNCH","appId":"${PLAYER_ID}","requestId":${requestId++}}"""
                        ),
                        "LAUNCH"
                    )

                    val json = recvJson("LAUNCH") ?: return@repeat

                    val apps = json.optJSONObject("status")
                        ?.optJSONArray("applications")

                    if (apps != null && apps.length() > 0) {
                        val app = apps.getJSONObject(0)

                        sessionId = app.optString("sessionId")
                        transportId = app.optString("transportId")
                    }
                }

                if (sessionId == null || transportId == null) return@Thread

                destinationId = transportId

                // APP CONNECT
                send(
                    formatMessage(
                        sourceId,
                        destinationId,
                        "urn:x-cast:com.google.cast.tp.connection",
                        """{"type":"CONNECT"}"""
                    ),
                    "APP_CONNECT"
                )
                recv("APP_CONNECT")

                // LOAD
                val loadPayload = JSONObject()
                    .put("type", "LOAD")
                    .put("sessionId", sessionId)
                    .put(
                        "media",
                        JSONObject()
                            .put("contentId", url)
                            .put("streamType", "BUFFERED")
                            .put("contentType", guessType(url))
                    )
                    .put("autoplay", true)
                    .put("requestId", requestId++)

                send(
                    formatMessage(
                        sourceId,
                        destinationId,
                        "urn:x-cast:com.google.cast.media",
                        loadPayload.toString()
                    ),
                    "LOAD"
                )

                val loadResp = recv("LOAD")
                val mediaJson = extractJson(loadResp)

                mediaSessionId = mediaJson
                    ?.optJSONArray("status")
                    ?.optJSONObject(0)
                    ?.optInt("mediaSessionId")

                if (sessionId != null && transportId != null && mediaSessionId != null) {
                    resultSession = CastSession(
                        device = device,
                        sourceId = sourceId,
                        transportId = transportId,
                        sessionId = sessionId,
                        mediaSessionId = mediaSessionId,
                        socket = socket,
                        requestId = requestId
                    )
                }

            } catch (e: Exception) {
                Log.e("CAST_DEBUG", "CAST ERROR", e)
            }

            onResult(resultSession)   // 👈 AQUÍ está la clave
        }.start()

    }

    // =========================================================
    // 🔐 TLS SOCKET (8009)
    // =========================================================

    private fun createSecureSocket(ip: String): SSLSocket {
        val context = SSLContext.getInstance("TLS")

        context.init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }), SecureRandom())

        return context.socketFactory.createSocket(ip, 8009) as SSLSocket
    }

    // =========================================================
    // HELPERS
    // =========================================================
    private fun guessType(url: String): String {
        return when {
            url.endsWith(".mp4") -> "video/mp4"
            url.endsWith(".m3u8") -> "application/x-mpegURL"
            url.endsWith(".mp3") -> "audio/mpeg"
            else -> "video/mp4"
        }
    }
}
