package com.control

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.control.databinding.ActivityMainBinding
import com.control.model.DeviceInfo
import com.control.network.WebSocketManager
import com.control.ui.DeviceAdapter
import com.control.ui.DeviceListItem
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceAdapter
    private var allDevices: List<DeviceInfo> = emptyList()
    private var searchQuery: String = ""
    private var searchBar: TextInputEditText? = null

    private val wsCallbacks = object : WebSocketManager.Callbacks {
        override fun onDeviceList(devices: List<DeviceInfo>) {
            allDevices = devices
            refreshList()
            binding.swipeRefresh.isRefreshing = false
        }

        override fun onDeviceInfo(device: DeviceInfo) {
            val updated = allDevices.toMutableList()
            val idx = updated.indexOfFirst { it.deviceId == device.deviceId }
            if (idx >= 0) updated[idx] = device else updated.add(device)
            allDevices = updated
            refreshList()
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                binding.toolbar.subtitle = if (connected) "Connected" else "Reconnecting…"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Control"

        adapter = DeviceAdapter { device ->
            if (device.online) {
                val intent = Intent(this, MonitoringActivity::class.java).apply {
                    putExtra(MonitoringActivity.EXTRA_DEVICE_ID, device.deviceId)
                    putExtra(MonitoringActivity.EXTRA_DEVICE_NAME, device.deviceName)
                }
                startActivity(intent)
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setColorSchemeColors(
            getColor(android.R.color.holo_green_light)
        )
        binding.swipeRefresh.setOnRefreshListener {
            val requestJson = org.json.JSONObject().apply {
                put("action", "get_device_list")
            }.toString()
            WebSocketManager.sendControlEvent(requestJson)
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        WebSocketManager.addCallback(wsCallbacks)
        WebSocketManager.connect()
        val requestJson = org.json.JSONObject().apply {
            put("action", "get_device_list")
        }.toString()
        WebSocketManager.sendControlEvent(requestJson)
    }

    override fun onPause() {
        super.onPause()
        WebSocketManager.removeCallback(wsCallbacks)
    }

    private fun refreshList() {
        val filtered = if (searchQuery.isEmpty()) {
            allDevices
        } else {
            allDevices.filter {
                it.deviceName.contains(searchQuery, ignoreCase = true) ||
                        it.model.contains(searchQuery, ignoreCase = true)
            }
        }

        val onlineDevices = filtered.filter { it.online }
        val offlineDevices = filtered.filter { !it.online }

        val items = mutableListOf<DeviceListItem>()

        items.add(DeviceListItem.Header("Online Devices", onlineDevices.size))
        if (onlineDevices.isEmpty()) {
            // Empty state handled by empty header count
        } else {
            onlineDevices.forEach { items.add(DeviceListItem.Device(it)) }
        }

        items.add(DeviceListItem.Header("Offline Devices", offlineDevices.size))
        if (offlineDevices.isNotEmpty()) {
            offlineDevices.forEach { items.add(DeviceListItem.Device(it)) }
        }

        adapter.submitList(items)

        binding.tvEmptyOnline.visibility = if (onlineDevices.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyOffline.visibility = if (offlineDevices.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                binding.swipeRefresh.isRefreshing = true
                val requestJson = org.json.JSONObject().apply {
                    put("action", "get_device_list")
                }.toString()
                WebSocketManager.sendControlEvent(requestJson)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
