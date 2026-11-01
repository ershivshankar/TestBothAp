package com.control.model

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val model: String,
    val androidVersion: String,
    val manufacturer: String = "",
    val battery: Int = 0,
    val isCharging: Boolean = false,
    val storage: String = "",
    val networkType: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0L,
    val connectedSince: Long = 0L,
    val fcmToken: String = ""
)
