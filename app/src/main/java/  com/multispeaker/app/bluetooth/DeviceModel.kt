package com.multispeaker.app.bluetooth

import android.bluetooth.BluetoothDevice

enum class DeviceType {
    LE_AUDIO,
    A2DP_CLASSIC,
    UNKNOWN
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class DeviceModel(
    val name: String,
    val address: String,
    val type: DeviceType,
    val rssi: Int,
    val isBonded: Boolean,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val rawDevice: BluetoothDevice? = null
)
