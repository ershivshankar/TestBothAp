package com.control

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.control.audio.AudioPlayer
import com.control.databinding.ActivityMonitoringBinding
import com.control.model.DeviceInfo
import com.control.network.WebSocketManager
import com.control.ui.ScreenRenderer
import com.control.ui.TouchOverlayView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MonitoringActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private lateinit var binding: ActivityMonitoringBinding
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var screenRenderer: ScreenRenderer

    private var deviceId: String = ""
    private var deviceName: String = ""
    private var currentDeviceInfo: DeviceInfo? = null

    private var screenOn = false
    private var micOn = false
    private var deviceAudioOn = false
    private var remoteControlOn = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fpsUpdateRunnable = object : Runnable {
        override fun run() {
            binding.tvFps.text = "FPS: ${"%.1f".format(screenRenderer.getFps())}"
            mainHandler.postDelayed(this, 1000)
        }
    }
    private val durationUpdateRunnable = object : Runnable {
        override fun run() {
            currentDeviceInfo?.let { updateConnectionDuration(it.connectedSince) }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val wsCallbacks = object : WebSocketManager.Callbacks {
        override fun onScreenFrame(jpeg: ByteArray) {
            if (screenOn) screenRenderer.render(jpeg)
        }

        override fun onMicAudio(pcm: ByteArray) {
            if (micOn) audioPlayer.writeMicData(pcm)
        }

        override fun onDeviceAudio(pcm: ByteArray) {
            if (deviceAudioOn) audioPlayer.writeDeviceAudioData(pcm)
        }

        override fun onDeviceInfo(device: DeviceInfo) {
            if (device.deviceId == deviceId) {
                currentDeviceInfo = device
                updateDeviceInfoUI(device)
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                binding.toolbar.subtitle = if (connected) deviceName else "Reconnecting…"
                binding.tvConnectionStatus.text = if (connected) "Connected" else "Reconnecting…"
                binding.tvConnectionStatus.setTextColor(
                    if (connected) getColor(android.R.color.holo_green_light)
                    else getColor(android.R.color.holo_orange_light)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Device"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = deviceName
            setDisplayHomeAsUpEnabled(true)
        }

        audioPlayer = AudioPlayer()
        screenRenderer = ScreenRenderer(binding.screenView)

        setupTouchOverlay()
        setupToggleControls()
        setupNavButtons()
        setupKeyboardButton()
        setupDeviceInfoButton()

        mainHandler.post(fpsUpdateRunnable)
        mainHandler.post(durationUpdateRunnable)
    }

    override fun onResume() {
        super.onResume()
        WebSocketManager.addCallback(wsCallbacks)
        WebSocketManager.connect()
        WebSocketManager.subscribe(deviceId)
    }

    override fun onPause() {
        super.onPause()
        WebSocketManager.removeCallback(wsCallbacks)
        WebSocketManager.unsubscribe(deviceId)
        if (screenOn) sendControlAction("STOP_SCREEN")
        if (micOn) sendControlAction("STOP_MIC")
        if (deviceAudioOn) sendControlAction("STOP_AUDIO")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(fpsUpdateRunnable)
        mainHandler.removeCallbacks(durationUpdateRunnable)
        audioPlayer.release()
        screenRenderer.release()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupTouchOverlay() {
        binding.touchOverlay.callback = object : TouchOverlayView.TouchCallback {
            override fun onTap(xNorm: Float, yNorm: Float) {
                if (!remoteControlOn) return
                val json = JSONObject().apply {
                    put("action", "TAP")
                    put("x", xNorm.toDouble())
                    put("y", yNorm.toDouble())
                }.toString()
                WebSocketManager.sendControlEvent(json)
            }

            override fun onLongPress(xNorm: Float, yNorm: Float) {
                if (!remoteControlOn) return
                val json = JSONObject().apply {
                    put("action", "LONG_PRESS")
                    put("x", xNorm.toDouble())
                    put("y", yNorm.toDouble())
                }.toString()
                WebSocketManager.sendControlEvent(json)
            }

            override fun onSwipe(x1Norm: Float, y1Norm: Float, x2Norm: Float, y2Norm: Float, durationMs: Long) {
                if (!remoteControlOn) return
                val json = JSONObject().apply {
                    put("action", "SWIPE")
                    put("x1", x1Norm.toDouble())
                    put("y1", y1Norm.toDouble())
                    put("x2", x2Norm.toDouble())
                    put("y2", y2Norm.toDouble())
                    put("duration", durationMs)
                }.toString()
                WebSocketManager.sendControlEvent(json)
            }
        }
    }

    private fun setupToggleControls() {
        binding.switchScreen.setOnCheckedChangeListener { _, isChecked ->
            screenOn = isChecked
            if (isChecked) {
                sendControlAction("START_SCREEN")
            } else {
                sendControlAction("STOP_SCREEN")
                screenRenderer.clear()
            }
            updateStatusBar()
        }

        binding.switchMic.setOnCheckedChangeListener { _, isChecked ->
            micOn = isChecked
            if (isChecked) {
                sendControlAction("START_MIC")
                audioPlayer.startMic()
            } else {
                sendControlAction("STOP_MIC")
                audioPlayer.stopMic()
            }
            updateStatusBar()
        }

        binding.switchDeviceAudio.setOnCheckedChangeListener { _, isChecked ->
            deviceAudioOn = isChecked
            if (isChecked) {
                sendControlAction("START_AUDIO")
                audioPlayer.startDeviceAudio()
            } else {
                sendControlAction("STOP_AUDIO")
                audioPlayer.stopDeviceAudio()
            }
            updateStatusBar()
        }

        binding.switchRemoteControl.setOnCheckedChangeListener { _, isChecked ->
            remoteControlOn = isChecked
            binding.touchOverlay.isEnabled = isChecked
            updateStatusBar()
        }
    }

    private fun setupNavButtons() {
        binding.btnBack.setOnClickListener {
            sendNavAction("BACK")
        }
        binding.btnHome.setOnClickListener {
            sendNavAction("HOME")
        }
        binding.btnRecents.setOnClickListener {
            sendNavAction("RECENTS")
        }
    }

    private fun setupKeyboardButton() {
        binding.btnKeyboard.setOnClickListener {
            val editText = EditText(this).apply {
                hint = "Type text to send"
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this, R.style.Theme_Control_Dialog)
                .setTitle("Send Text")
                .setView(editText)
                .setPositiveButton("Send") { _, _ ->
                    val text = editText.text.toString()
                    if (text.isNotEmpty()) {
                        val json = JSONObject().apply {
                            put("action", "TYPE_TEXT")
                            put("text", text)
                        }.toString()
                        WebSocketManager.sendControlEvent(json)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupDeviceInfoButton() {
        binding.btnDeviceInfo.setOnClickListener {
            showDeviceInfoBottomSheet()
        }
    }

    private fun showDeviceInfoBottomSheet() {
        val device = currentDeviceInfo ?: return
        val dialog = BottomSheetDialog(this, R.style.Theme_Control_BottomSheet)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_device_info, null)

        view.findViewById<TextView>(R.id.tvBsDeviceName).text = device.deviceName
        view.findViewById<TextView>(R.id.tvBsModel).text = "${device.manufacturer} ${device.model}"
        view.findViewById<TextView>(R.id.tvBsAndroid).text = "Android ${device.androidVersion}"
        view.findViewById<TextView>(R.id.tvBsBattery).text = "${device.battery}% ${if (device.isCharging) "⚡ Charging" else ""}"
        view.findViewById<TextView>(R.id.tvBsStorage).text = device.storage.ifEmpty { "N/A" }
        view.findViewById<TextView>(R.id.tvBsNetwork).text = device.networkType.ifEmpty { "N/A" }
        view.findViewById<TextView>(R.id.tvBsConnected).text = formatDuration(device.connectedSince)
        view.findViewById<TextView>(R.id.tvBsDeviceId).text = device.deviceId

        dialog.setContentView(view)
        dialog.show()
    }

    private fun sendControlAction(action: String) {
        val json = JSONObject().apply {
            put("action", action)
            put("deviceId", deviceId)
        }.toString()
        WebSocketManager.sendControlEvent(json)
    }

    private fun sendNavAction(action: String) {
        if (!remoteControlOn) return
        val json = JSONObject().apply {
            put("action", action)
        }.toString()
        WebSocketManager.sendControlEvent(json)
    }

    private fun updateStatusBar() {
        val parts = mutableListOf<String>()
        if (screenOn) parts.add("● Screen")
        if (micOn) parts.add("● Mic")
        if (deviceAudioOn) parts.add("● Audio")
        if (remoteControlOn) parts.add("● Remote")
        binding.tvStreamStatus.text = if (parts.isEmpty()) "No streams active" else parts.joinToString("  ")
    }

    private fun updateDeviceInfoUI(device: DeviceInfo) {
        runOnUiThread {
            binding.toolbar.title = device.deviceName
            binding.tvBattery.text = "${device.battery}%${if (device.isCharging) "⚡" else ""}"
        }
    }

    private fun updateConnectionDuration(connectedSince: Long) {
        if (connectedSince == 0L) {
            binding.tvDuration.text = ""
            return
        }
        binding.tvDuration.text = formatDuration(connectedSince)
    }

    private fun formatDuration(since: Long): String {
        if (since == 0L) return "N/A"
        val elapsed = System.currentTimeMillis() - since
        val h = TimeUnit.MILLISECONDS.toHours(elapsed)
        val m = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
