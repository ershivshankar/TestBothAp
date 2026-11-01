package com.control.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface TouchCallback {
        fun onTap(xNorm: Float, yNorm: Float)
        fun onLongPress(xNorm: Float, yNorm: Float)
        fun onSwipe(x1Norm: Float, y1Norm: Float, x2Norm: Float, y2Norm: Float, durationMs: Long)
    }

    var callback: TouchCallback? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val xNorm = (e.x / width).coerceIn(0f, 1f)
            val yNorm = (e.y / height).coerceIn(0f, 1f)
            callback?.onTap(xNorm, yNorm)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val xNorm = (e.x / width).coerceIn(0f, 1f)
            val yNorm = (e.y / height).coerceIn(0f, 1f)
            callback?.onLongPress(xNorm, yNorm)
        }
    })

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        // Transparent — no drawing
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val upX = event.x
                val upY = event.y
                val dx = upX - downX
                val dy = upY - downY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val duration = event.eventTime - downTime

                if (distance > 30f && duration < 2000L) {
                    val x1Norm = (downX / width).coerceIn(0f, 1f)
                    val y1Norm = (downY / height).coerceIn(0f, 1f)
                    val x2Norm = (upX / width).coerceIn(0f, 1f)
                    val y2Norm = (upY / height).coerceIn(0f, 1f)
                    callback?.onSwipe(x1Norm, y1Norm, x2Norm, y2Norm, duration)
                }
            }
        }
        return true
    }
}
