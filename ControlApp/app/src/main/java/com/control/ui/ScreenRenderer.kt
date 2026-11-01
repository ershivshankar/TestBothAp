package com.control.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ScreenRenderer(imageView: ImageView) {

    private val TAG = "ScreenRenderer"
    private val imageViewRef = WeakReference(imageView)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val frameCount = AtomicInteger(0)
    private val lastFpsCalcTime = AtomicLong(System.currentTimeMillis())
    private val currentFps = java.util.concurrent.atomic.AtomicReference(0f)

    private var lastRenderJob: Job? = null

    fun render(jpeg: ByteArray) {
        lastRenderJob?.cancel()
        lastRenderJob = scope.launch {
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            }.getOrNull() ?: return@launch

            val count = frameCount.incrementAndGet()
            val now = System.currentTimeMillis()
            val elapsed = now - lastFpsCalcTime.get()
            if (elapsed >= 1000L) {
                val fps = count * 1000f / elapsed
                currentFps.set(fps)
                frameCount.set(0)
                lastFpsCalcTime.set(now)
            }

            withContext(Dispatchers.Main) {
                val iv = imageViewRef.get() ?: return@withContext
                iv.setImageBitmap(bitmap)
            }
        }
    }

    fun getFps(): Float = currentFps.get()

    fun clear() {
        mainHandler.post {
            imageViewRef.get()?.setImageBitmap(null)
        }
        currentFps.set(0f)
        frameCount.set(0)
    }

    fun release() {
        scope.cancel()
    }
}
