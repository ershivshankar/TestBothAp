package com.email.receiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import com.email.service.RemoteMonitorService

/**
 * FCMService stub — Firebase is disabled until real google-services.json is placed.
 * Replace this class with the full FirebaseMessagingService implementation after
 * enabling Firebase in build.gradle.
 *
 * Full implementation (uncomment after Firebase is configured):
 *
 * import com.google.firebase.messaging.FirebaseMessagingService
 * import com.google.firebase.messaging.RemoteMessage
 * class FCMService : FirebaseMessagingService() {
 *     override fun onMessageReceived(remoteMessage: RemoteMessage) { ... }
 *     override fun onNewToken(token: String) { ... }
 * }
 */
class FCMService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMonitorServiceRunning()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun ensureMonitorServiceRunning() {
        try {
            val i = Intent(applicationContext, RemoteMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (_: Exception) {}
    }
}
