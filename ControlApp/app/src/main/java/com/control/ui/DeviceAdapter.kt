package com.control.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.control.databinding.ItemDeviceOfflineBinding
import com.control.databinding.ItemDeviceOnlineBinding
import com.control.model.DeviceInfo
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

sealed class DeviceListItem {
    data class Header(val title: String, val count: Int) : DeviceListItem()
    data class Device(val info: DeviceInfo) : DeviceListItem()
}

class DeviceAdapter(
    private val onDeviceClick: (DeviceInfo) -> Unit
) : ListAdapter<DeviceListItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_ONLINE = 1
        const val VIEW_TYPE_OFFLINE = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is DeviceListItem.Header -> VIEW_TYPE_HEADER
            is DeviceListItem.Device -> if (item.info.online) VIEW_TYPE_ONLINE else VIEW_TYPE_OFFLINE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(parent)
            VIEW_TYPE_ONLINE -> OnlineDeviceViewHolder(
                ItemDeviceOnlineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> OfflineDeviceViewHolder(
                ItemDeviceOfflineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DeviceListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is DeviceListItem.Device -> {
                if (holder is OnlineDeviceViewHolder) holder.bind(item.info, onDeviceClick)
                else if (holder is OfflineDeviceViewHolder) holder.bind(item.info)
            }
        }
    }

    class HeaderViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
    ) {
        private val text = itemView as android.widget.TextView

        fun bind(header: DeviceListItem.Header) {
            text.text = "${header.title} (${header.count})"
            text.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            text.textSize = 12f
            text.setPadding(32, 24, 32, 8)
            text.typeface = android.graphics.Typeface.DEFAULT_BOLD
            text.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        }
    }

    class OnlineDeviceViewHolder(
        private val binding: ItemDeviceOnlineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DeviceInfo, onClick: (DeviceInfo) -> Unit) {
            binding.tvDeviceName.text = device.deviceName
            binding.tvDeviceModel.text = "${device.model} · Android ${device.androidVersion}"
            binding.tvConnectionDuration.text = formatDuration(device.connectedSince)

            val initial = device.deviceName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatar.text = initial

            binding.root.setOnClickListener { onClick(device) }
        }

        private fun formatDuration(connectedSince: Long): String {
            if (connectedSince == 0L) return "Just connected"
            val elapsed = System.currentTimeMillis() - connectedSince
            val h = TimeUnit.MILLISECONDS.toHours(elapsed)
            val m = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }
    }

    class OfflineDeviceViewHolder(
        private val binding: ItemDeviceOfflineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DeviceInfo) {
            binding.tvDeviceName.text = device.deviceName
            binding.tvDeviceModel.text = "${device.model} · Android ${device.androidVersion}"
            binding.tvLastSeen.text = "Last seen: ${formatTime(device.lastSeen)}"

            val initial = device.deviceName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatar.text = initial
        }

        private fun formatTime(timestamp: Long): String {
            if (timestamp == 0L) return "Never"
            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DeviceListItem>() {
        override fun areItemsTheSame(old: DeviceListItem, new: DeviceListItem): Boolean {
            return when {
                old is DeviceListItem.Header && new is DeviceListItem.Header -> old.title == new.title
                old is DeviceListItem.Device && new is DeviceListItem.Device -> old.info.deviceId == new.info.deviceId
                else -> false
            }
        }

        override fun areContentsTheSame(old: DeviceListItem, new: DeviceListItem): Boolean {
            return old == new
        }
    }
}
