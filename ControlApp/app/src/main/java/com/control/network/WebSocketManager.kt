package com.control.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.control.BuildConfig
import com.control.model.DeviceInfo
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WebSocketManager {

    private const val TAG = "WebSocketManager"
    private const val TYPE_SCREEN_FRAME: Byte = 0x01
    private const val TYPE_MIC_PCM: Byte = 0x02
    private const val TYPE_DEVICE_AUDIO_PCM: Byte = 0x03
    private const val TYPE_CONTROL_EVENT: Byte = 0x04
    private const val TYPE_DEVICE_INFO: Byte = 0x05
    private const val TYPE_DEVICE_LIST: Byte = 0x06

    interface Callbacks {
        fun onDeviceList(devices: List<DeviceInfo>) {}
        fun onScreenFrame(jpeg: ByteArray) {}
        fun onMicAudio(pcm: ByteArray) {}
        fun onDeviceAudio(pcm: ByteArray) {}
        fun onDeviceInfo(device: DeviceInfo) {}
        fun onConnectionStateChanged(connected: Boolean) {}
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val callbacks = mutableListOf<Callbacks>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reconnectDelayMs = 1000L
    private var reconnecting = false
    private var destroyed = false

    fun addCallback(cb: Callbacks) {
        if (!callbacks.contains(cb)) callbacks.add(cb)
    }

    fun removeCallback(cb: Callbacks) {
        callbacks.remove(cb)
    }

    fun connect() {
        if (webSocket != null) return
        destroyed = false
        doConnect()
    }

    private fun doConnect() {
        val request = Request.Builder().url(BuildConfig.RELAY_URL).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        destroyed = true
        webSocket?.close(1000, "Controller disconnected")
        webSocket = null
        reconnecting = false
    }

    fun subscribe(deviceId: String) {
        val json = JSONObject().apply {
            put("action", "subscribe")
            put("deviceId", deviceId)
        }.toString()
        sendControlEvent(json)
    }

    fun unsubscribe(deviceId: String) {
        val json = JSONObject().apply {
            put("action", "unsubscribe")
            put("deviceId", deviceId)
        }.toString()
        sendControlEvent(json)
    }

    fun sendControlEvent(json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(1 + payload.size)
        frame[0] = TYPE_CONTROL_EVENT
        System.arraycopy(payload, 0, frame, 1, payload.size)
        val ws = webSocket ?: run {
            Log.w(TAG, "sendControlEvent: no active WebSocket")
            return
        }
        ws.send(okio.ByteString.of(*frame))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
            reconnectDelayMs = 1000L
            reconnecting = false
            val hello = JSONObject().apply { put("action", "controller_connect") }.toString()
            sendControlEvent(hello)
            mainHandler.post {
                callbacks.forEach { it.onConnectionStateChanged(true) }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            val raw = bytes.toByteArray()
            if (raw.isEmpty()) return
            val typeFlag = raw[0]
            val payload = raw.copyOfRange(1, raw.size)
            when (typeFlag) {
                TYPE_SCREEN_FRAME -> {
                    mainHandler.post { callbacks.forEach { it.onScreenFrame(payload) } }
                }
                TYPE_MIC_PCM -> {
                    mainHandler.post { callbacks.forEach { it.onMicAudio(payload) } }
                }
                TYPE_DEVICE_AUDIO_PCM -> {
                    mainHandler.post { callbacks.forEach { it.onDeviceAudio(payload) } }
                }
                TYPE_DEVICE_INFO -> {
                    runCatching {
                        val json = JSONObject(String(payload, Charsets.UTF_8))
                        val device = parseDeviceInfo(json)
                        mainHandler.post { callbacks.forEach { it.onDeviceInfo(device) } }
                    }.onFailure { Log.e(TAG, "parse device info", it) }
                }
                TYPE_DEVICE_LIST -> {
                    runCatching {
                        val arr = JSONArray(String(payload, Charsets.UTF_8))
                        val list = (0 until arr.length()).map { parseDeviceInfo(arr.getJSONObject(it)) }
                        mainHandler.post { callbacks.forEach { it.onDeviceList(list) } }
                    }.onFailure { Log.e(TAG, "parse device list", it) }
                }
                else -> Log.w(TAG, "Unknown frame type: $typeFlag")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "device_list" -> {
                        val arr = json.getJSONArray("devices")
                        val list = (0 until arr.length()).map { parseDeviceInfo(arr.getJSONObject(it)) }
                        mainHandler.post { callbacks.forEach { it.onDeviceList(list) } }
                    }
                    "device_info" -> {
                        val device = parseDeviceInfo(json.getJSONObject("device"))
                        mainHandler.post { callbacks.forEach { it.onDeviceInfo(device) } }
                    }
                    else -> Log.d(TAG, "Unhandled text type: ${json.optString("type")}")
                }
            }.onFailure { Log.e(TAG, "text message parse", it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            this@WebSocketManager.webSocket = null
            mainHandler.post {
                callbacks.forEach { it.onConnectionStateChanged(false) }
            }
            if (!destroyed) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            this@WebSocketManager.webSocket = null
            mainHandler.post {
                callbacks.forEach { it.onConnectionStateChanged(false) }
            }
            if (!destroyed) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnecting || destroyed) return
        reconnecting = true
        Log.d(TAG, "Reconnecting in ${reconnectDelayMs}ms")
        mainHandler.postDelayed({
            reconnecting = false
            if (!destroyed) {
                doConnect()
                reconnectDelayMs = minOf(reconnectDelayMs * 2, 30_000L)
            }
        }, reconnectDelayMs)
    }

    private fun parseDeviceInfo(json: JSONObject): DeviceInfo {
        return DeviceInfo(
            deviceId = json.optString("deviceId"),
            deviceName = json.optString("deviceName", "Unknown"),
            model = json.optString("model", ""),
            androidVersion = json.optString("androidVersion", ""),
            manufacturer = json.optString("manufacturer", ""),
            battery = json.optInt("battery", 0),
            isCharging = json.optBoolean("isCharging", false),
            storage = json.optString("storage", ""),
            networkType = json.optString("networkType", ""),
            online = json.optBoolean("online", false),
            lastSeen = json.optLong("lastSeen", 0L),
            connectedSince = json.optLong("connectedSince", 0L),
            fcmToken = json.optString("fcmToken", "")
        )
    }
}
