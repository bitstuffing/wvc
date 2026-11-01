package com.github.bitstuffing.webvideocaster.chromecast.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.*
import javax.net.ssl.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.SecureRandom
import org.json.JSONObject
import java.io.DataInputStream
import java.net.SocketTimeoutException
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.security.cert.X509Certificate
import java.util.Collections

data class CastDevice(
    val ip: String,
    val friendlyName: String,
    val modelName: String? = null,
    val udn: String? = null
)

data class CastSession(
    val device: CastDevice,
    var sourceId: String,
    var transportId: String,
    val sessionId: String?,
    var mediaSessionId: Int?,
    val socket: SSLSocket,
    var requestId: Int
)

object CastUtils {

    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val CAST_SERVICE = "_googlecast._tcp.local."
    private const val PLAYER_ID = "CC1AD845"

    private data class MdnsSrv(
        val target: String,
        val port: Int
    )

    private fun recvCastJson(input: DataInputStream): JSONObject? {
        val length = input.readInt()
        if (length <= 0 || length > 1024 * 1024) {
            throw IOException("Invalid Cast frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return extractJson(String(payload, Charsets.UTF_8))
    }

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
    @SuppressLint("MissingPermission")
    private suspend fun ssdpDiscover(context: Context, timeoutMs: Long): List<CastDevice> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifi.createMulticastLock("wvc_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        val instances = linkedSetOf<String>()
        val srvRecords = mutableMapOf<String, MdnsSrv>()
        val addresses = mutableMapOf<String, String>()
        var socket: MulticastSocket? = null
        try {
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork
            val linkProperties: LinkProperties? = connectivity.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName
            var networkInterface = if (interfaceName != null) {
                NetworkInterface.getByName(interfaceName)
            } else {
                Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull {
                    it.isUp && !it.isLoopback && it.supportsMulticast()
                }
            } ?: throw IllegalStateException("No multicast network interface")
            Log.d("CAST_TEST", "mDNS interface: ${networkInterface.name}")
            val group = InetAddress.getByName(MDNS_GROUP)
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                soTimeout = 500
            }
            socket.setNetworkInterface(networkInterface)
            socket.joinGroup(InetSocketAddress(group, MDNS_PORT), networkInterface)
            Log.d("CAST_TEST", "mDNS socket bound to UDP $MDNS_PORT")
            Log.d("CAST_TEST", "MulticastLock acquired: ${multicastLock.isHeld}")
            val startTime = System.currentTimeMillis()
            var lastQuery = 0L
            val buffer = ByteArray(9000)
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val now = System.currentTimeMillis()
                if (now - lastQuery >= 2000L) {
                    val questions = linkedSetOf<Pair<String, Int>>()
                    questions.add(CAST_SERVICE to 12) // PTR
                    instances.forEach {
                        questions.add(it to 33) // SRV
                    }
                    srvRecords.values.forEach {
                        questions.add(it.target to 1) // A
                    }
                    val query = buildMdnsQuery(questions)
                    socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                    Log.d("CAST_TEST", "mDNS query sent: ${questions.size} questions")
                    lastQuery = now
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    Log.d("CAST_TEST", "mDNS packet received: ${packet.length} bytes from ${packet.address.hostAddress}")
                    parseMdnsPacket(packet.data, packet.length, instances, srvRecords, addresses)
                } catch (_: SocketTimeoutException) { }
            }
            Log.d("CAST_TEST", "mDNS discovery finished")
            val results = linkedMapOf<String, CastDevice>()
            for (instance in instances) {
                val srv = srvRecords[instance]
                val ip = srv?.let {
                    addresses[it.target]
                }
                val friendlyName = instance.substringBefore("._googlecast._tcp").removeSuffix(".").ifBlank { "Chromecast" }
                val device = if (!ip.isNullOrBlank()) {
                    fetchDeviceDesc(ip)?.copy(ip = ip) ?: CastDevice(friendlyName = friendlyName, ip = ip)
                } else {
                    CastDevice(friendlyName = friendlyName, ip = "")
                }
                results[instance] = device
                Log.d("CAST_TEST", "CHROMECAST FOUND: ${device.friendlyName} IP=${device.ip} PORT=${srv?.port ?: 0}")
            }
            results.values.sortedBy { it.friendlyName.lowercase() }
        } catch (e: Exception) {
            Log.e("CAST_TEST", "mDNS discovery error", e)
            emptyList()
        } finally {
            runCatching {
                socket?.close()
            }
            runCatching {
                if (multicastLock.isHeld) {
                    multicastLock.release()
                }
            }
            Log.d("CAST_TEST", "mDNS socket closed")
            Log.d("CAST_TEST", "MulticastLock released")
        }
    }

    private fun buildMdnsQuery(questions: Set<Pair<String, Int>>): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.writeShort(0) // Transaction ID
        data.writeShort(0) // Flags: standard query
        data.writeShort(questions.size)
        data.writeShort(0) // Answer count
        data.writeShort(0) // Authority count
        data.writeShort(0) // Additional count
        for ((name, type) in questions) {
            val cleanName = name.trimEnd('.')
            for (label in cleanName.split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 63)
                data.writeByte(bytes.size)
                data.write(bytes)
            }
            data.writeByte(0) // End of name
            data.writeShort(type)
            data.writeShort(1) // IN
        }
        return output.toByteArray()
    }

    private fun normalizeDnsName(name: String): String = name.trimEnd('.').lowercase() + "."

    private fun readDnsU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun readDnsName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var offset = start
        var nextOffset = -1
        var jumps = 0
        while (offset < data.size && jumps < 128) {
            val length = data[offset].toInt() and 0xff
            if (length == 0) {
                if (nextOffset == -1) {
                    nextOffset = offset + 1
                }
                break
            }
            // Compression pointer.
            if ((length and 0xC0) == 0xC0) {
                if (offset + 1 >= data.size) break
                val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xff)
                if (nextOffset == -1) {
                    nextOffset = offset + 2
                }
                if (pointer >= data.size) break
                offset = pointer
                jumps++
                continue
            }
            if ((length and 0xC0) != 0 || offset + 1 + length > data.size) {
                break
            }
            labels.add(String(data, offset + 1, length, Charsets.UTF_8))
            offset += length + 1
        }
        return Pair(labels.joinToString(".") + ".", if (nextOffset >= 0) nextOffset else offset)
    }

    private fun parseMdnsPacket(data: ByteArray, length: Int, instances: MutableSet<String>, srvRecords: MutableMap<String, MdnsSrv>, addresses: MutableMap<String, String>) {
        if (length < 12) return
        val flags = readDnsU16(data, 2)
        // Ignore queries; process responses only.
        if ((flags and 0x8000) == 0) return
        val questionCount = readDnsU16(data, 4)
        val answerCount = readDnsU16(data, 6)
        val authorityCount = readDnsU16(data, 8)
        val additionalCount = readDnsU16(data, 10)
        val totalRecords = answerCount + authorityCount + additionalCount
        var offset = 12
        // Skip questions.
        repeat(questionCount) {
            if (offset >= length) return
            val result = readDnsName(data, offset)
            offset = result.second
            if (offset + 4 > length) return
            offset += 4
        }
        repeat(totalRecords) {
            if (offset >= length) return
            val nameResult = readDnsName(data, offset)
            val owner = normalizeDnsName(nameResult.first)
            offset = nameResult.second
            if (offset + 10 > length) return
            val type = readDnsU16(data, offset)
            val recordClass = readDnsU16(data, offset + 2)
            val dataLength = readDnsU16(data, offset + 8)
            val rdataOffset = offset + 10
            val nextRecord = rdataOffset + dataLength
            if (nextRecord > length) return
            // Ignore non-IN records.
            if ((recordClass and 0x7fff) == 1) {
                when (type) {
                    // PTR: service -> instance
                    12 -> {
                        val target = readDnsName(data, rdataOffset).first
                        val normalizedTarget = normalizeDnsName(target)
                        if (owner == normalizeDnsName(CAST_SERVICE)) {
                            val added = instances.add(normalizedTarget)
                            if (added) {
                                Log.d("CAST_TEST", "PTR FOUND: $normalizedTarget")
                            }
                        }
                    }
                    // SRV: instance -> hostname + port
                    33 -> {
                        if (dataLength >= 7) {
                            val port = readDnsU16(data, rdataOffset + 4)
                            val target = readDnsName(data, rdataOffset + 6).first
                            val normalizedOwner = normalizeDnsName(owner)
                            val normalizedTarget = normalizeDnsName(target)
                            srvRecords[normalizedOwner] = MdnsSrv(target = normalizedTarget, port = port)
                            Log.d("CAST_TEST", "SRV FOUND: $normalizedOwner -> $normalizedTarget:$port")
                        }
                    }
                    // A: hostname -> IPv4
                    1 -> {
                        if (dataLength == 4) {
                            val ip = InetAddress.getByAddress(data.copyOfRange(rdataOffset,rdataOffset + 4)).hostAddress
                            val normalizedOwner = normalizeDnsName(owner)
                            addresses[normalizedOwner] = ip
                            Log.d("CAST_TEST", "A RECORD: $normalizedOwner -> $ip")
                        }
                    }
                }
            }
            offset = nextRecord
        }
    }

    suspend fun searchDevices(context: Context, timeoutMs: Long = 8000L): List<CastDevice> {
        return ssdpDiscover(context, timeoutMs)
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
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
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

    fun getSession(device: CastDevice): CastSession? {
        var socket: SSLSocket? = null
        try {
            Log.d("CAST_DEBUG", "Opening socket -> ${device.ip}")
            socket = createSecureSocket(device.ip).apply {
                soTimeout = 5000
            }
            val input = DataInputStream(socket.inputStream)
            val out = socket.outputStream
            val sourceId = "sender-0"
            val receiverId = "receiver-0"
            var requestId = 1
            fun send(destination: String, namespace: String, payload: String) {
                out.write(formatMessage(sourceId, destination, namespace, payload))
                out.flush()
            }
            send(receiverId,"urn:x-cast:com.google.cast.tp.connection","""{"type":"CONNECT"}""")
            send(receiverId,"urn:x-cast:com.google.cast.receiver", JSONObject().put("type", "GET_STATUS").put("requestId", requestId++).toString())

            var transportId: String? = null
            var sessionId: String? = null

            val deadline = System.currentTimeMillis() + 5000L

            while (System.currentTimeMillis() < deadline) {
                val json = try {
                    recvCastJson(input)
                } catch (e: SocketTimeoutException) {
                    Log.d("CAST_DEBUG", "Timeout waiting for receiver status")
                    break
                } ?: continue

                when (json.optString("type")) {
                    "RECEIVER_STATUS" -> {
                        val apps = json.optJSONObject("status")?.optJSONArray("applications")?: continue
                        for (i in 0 until apps.length()) {
                            val app = apps.optJSONObject(i) ?: continue
                            val transport = app.optString("transportId")
                            val appSession = app.optString("sessionId")
                            if (transport.isNotBlank() && transport != "null" && appSession.isNotBlank() && appSession != "null") {
                                transportId = transport
                                sessionId = appSession
                                break
                            }
                        }
                        if (transportId != null) break
                    }
                    "CLOSE" -> {
                        Log.d("CAST_DEBUG", "Receiver closed the connection")
                        break
                    }
                }
            }

            if (transportId == null || sessionId == null) {
                Log.d("CAST_SERVICE", "No active Cast application found")
                socket.close()
                return null
            }

            send(transportId!!, "urn:x-cast:com.google.cast.tp.connection", """{"type":"CONNECT"}""")
            send(transportId!!, "urn:x-cast:com.google.cast.media", JSONObject().put("type", "GET_STATUS").put("requestId", requestId++).toString())
            var mediaSessionId: Int? = null
            val mediaDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < mediaDeadline) {
                val json = try {
                    recvCastJson(input)
                } catch (e: SocketTimeoutException) {
                    break
                } ?: continue
                if (json.optString("type") != "MEDIA_STATUS") continue
                val statuses = json.optJSONArray("status") ?: continue
                if (statuses.length() == 0) break
                val status = statuses.optJSONObject(0) ?: continue
                val id = status.optInt("mediaSessionId", -1)
                if (id > 0) {
                    mediaSessionId = id
                    break
                }
            }
            Log.d("CAST_SERVICE", "Receiver app found: transport=$transportId media=$mediaSessionId")
            return CastSession(
                device = device,
                sourceId = sourceId,
                transportId = transportId!!,
                sessionId = sessionId,
                mediaSessionId = mediaSessionId,
                socket = socket,
                requestId = requestId
            ).also {
                socket = null
            }

        } catch (e: Exception) {
            Log.e("CAST_DEBUG", "Could not inspect Cast session", e)
            return null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) { }
        }
    }

    fun getMediaPlayerInfo(session: CastSession): CastMediaInfo? {
        val status = castGetStatus(session) ?: return null

        return try {
            val mediaStatus = status.optJSONArray("status")?.optJSONObject(0)?: return null
            val media = mediaStatus.optJSONObject("media")
            val videoInfo = mediaStatus.optJSONObject("videoInfo")
            val state = when (mediaStatus.optString("playerState")) {
                "PLAYING" -> CastConnectionManager.CastPlayerState.PLAYING
                "PAUSED" -> CastConnectionManager.CastPlayerState.PAUSED
                "BUFFERING" -> CastConnectionManager.CastPlayerState.BUFFERING
                "IDLE" -> CastConnectionManager.CastPlayerState.IDLE
                else -> CastConnectionManager.CastPlayerState.UNKNOWN
            }
            val tracks = media?.optJSONArray("tracks")
            val audioTracks = mutableListOf<String>()
            val subtitleTracks = mutableListOf<String>()
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    when (track.optString("type")) {
                        "AUDIO" -> audioTracks.add(
                            track.optString("name")
                        )
                        "TEXT" -> subtitleTracks.add(
                            track.optString("name")
                        )
                    }
                }
            }
            val activeTracks = mutableListOf<Int>()
            val activeTrackArray = mediaStatus.optJSONArray("activeTrackIds")
            if (activeTrackArray != null) {
                for (i in 0 until activeTrackArray.length()) {
                    activeTracks.add(activeTrackArray.optInt(i))
                }
            }
            CastMediaInfo(
                playerState = state,
                currentTime = mediaStatus.optDouble("currentTime", 0.0),
                duration = media?.optDouble("duration", -1.0) ?: -1.0,
                streamType = media?.optString("streamType"),
                contentType = media?.optString("contentType"),
                width = videoInfo?.optInt("width") ?: 0,
                height = videoInfo?.optInt("height") ?: 0,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                activeTrackIds = activeTracks
            )
        } catch (e: Exception) {
            Log.e("CAST_DEBUG", "Error parsing media information", e)
            null
        }
    }

    fun getMediaPlayerState(session: CastSession): String? {
        val status = castGetStatus(session) ?: return null
        Log.d("CAST_DEBUG", "status: $status")
        return status.optJSONArray("status")?.optJSONObject(0)?.optString("playerState")
    }

    fun castUrl(device: CastDevice, url: String, onResult: (CastSession?) -> Unit) {
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
                send(formatMessage(sourceId, destinationId, "urn:x-cast:com.google.cast.tp.connection", """{"type":"CONNECT"}"""), "CONNECT")
                recv("CONNECT")

                // GET STATUS
                send(formatMessage(sourceId, destinationId, "urn:x-cast:com.google.cast.receiver", """{"type":"GET_STATUS","requestId":${requestId++}}"""), "GET_STATUS")
                recv("GET_STATUS")

                var sessionId: String? = null
                var transportId: String? = null
                var mediaSessionId: Int? = null

                repeat(5) { attempt ->

                    send(formatMessage(sourceId, destinationId, "urn:x-cast:com.google.cast.receiver", """{"type":"LAUNCH","appId":"${PLAYER_ID}","requestId":${requestId++}}"""), "LAUNCH")

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
                send(formatMessage(sourceId, destinationId, "urn:x-cast:com.google.cast.tp.connection", """{"type":"CONNECT"}"""), "APP_CONNECT")
                recv("APP_CONNECT")

                // LOAD
                val loadPayload = JSONObject()
                    .put("type", "LOAD")
                    .put("sessionId", sessionId)
                    .put("media", JSONObject().put("contentId", url).put("streamType", "BUFFERED").put("contentType", guessType(url)))
                    .put("autoplay", true)
                    .put("requestId", requestId++)

                send(formatMessage(sourceId, destinationId, "urn:x-cast:com.google.cast.media", loadPayload.toString()), "LOAD")

                val loadResp = recv("LOAD")
                val mediaJson = extractJson(loadResp)

                mediaSessionId = mediaJson?.optJSONArray("status")?.optJSONObject(0)?.optInt("mediaSessionId")

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
    fun createSecureSocket(ip: String): SSLSocket {
        val context = SSLContext.getInstance("TLS")

        context.init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }),
            SecureRandom()
        )

        return (context.socketFactory.createSocket(ip, 8009) as SSLSocket).apply {
            soTimeout = 2000
        }
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
