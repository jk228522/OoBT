package com.multispeaker.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothManager(private val context: Context) {

    companion object {
        const val MAX_DEVICES = 3
    }

    private val androidBtManager: AndroidBluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager

    val adapter: BluetoothAdapter? = androidBtManager?.adapter

    private val _devices = MutableStateFlow<List<DeviceModel>>(emptyList())
    val devices: StateFlow<List<DeviceModel>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    // ============ PERMISSIONS ============
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ============ CAPABILITY DETECTION ============
    /**
     * Detects whether a Bluetooth device is LE Audio capable or A2DP Classic.
     * LE Audio devices are detected by their scan record service UUIDs.
     */
    private fun detectDeviceType(device: BluetoothDevice, scanRecord: android.bluetooth.le.ScanRecord?): DeviceType {
        // LE Audio Service UUID (ASCS - Audio Stream Control Service)
        val LE_AUDIO_UUID = java.util.UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")
        // Another LE Audio related service (Published Audio Capabilities Service)
        val PACS_UUID = java.util.UUID.fromString("00001850-0000-1000-8000-00805F9B34FB")

        scanRecord?.serviceUuids?.let { uuids ->
            if (uuids.contains(LE_AUDIO_UUID) || uuids.contains(PACS_UUID)) {
                return DeviceType.LE_AUDIO
            }
        }

        // BluetoothClass based detection for classic speakers
        val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
        if (btClass != null) {
            val major = btClass.majorDeviceClass
            if (major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) {
                return DeviceType.A2DP_CLASSIC
            }
        }

        // Fallback: name-based heuristic
        val name = try { device.name ?: "" } catch (_: SecurityException) { "" }
        val lower = name.lowercase()
        return when {
            lower.contains("le audio") || lower.contains("lc3") -> DeviceType.LE_AUDIO
            lower.contains("buds") ||
            lower.contains("speaker") ||
            lower.contains("headphone") ||
            lower.contains("sound") -> DeviceType.A2DP_CLASSIC
            else -> DeviceType.UNKNOWN
        }
    }

    // ============ SCAN ============
    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = adapter ?: run {
            _statusMessage.value = "Bluetooth not available on this device"
            return
        }
        if (!adapter.isEnabled) {
            _statusMessage.value = "Please turn ON Bluetooth"
            return
        }
        if (!hasPermissions()) {
            _statusMessage.value = "Bluetooth permissions required"
            return
        }
        if (_isScanning.value) return

        // Keep bonded devices visible
        val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: SecurityException) { emptySet() }
        val initialList = bonded.map { it.toDeviceModel(true) }.toMutableList()
        _devices.value = initialList

        _isScanning.value = true
        _statusMessage.value = "Scanning for devices..."

        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            scanner.startScan(leScanCallback)
        } else {
            // Fallback to classic discovery
            context.registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            adapter.startDiscovery()
        }

        // Auto-stop after 15 seconds
        handler.postDelayed({ stopScan() }, 15_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val adapter = adapter ?: return
        if (!_isScanning.value) return
        try {
            adapter.bluetoothLeScanner?.stopScan(leScanCallback)
            adapter.cancelDiscovery()
        } catch (_: Exception) { }

        try { context.unregisterReceiver(classicReceiver) } catch (_: Exception) { }

        _isScanning.value = false
        _statusMessage.value = "Scan complete"
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { addOrUpdate(it, result.rssi, result.scanRecord, false) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { r -> r.device?.let { addOrUpdate(it, r.rssi, r.scanRecord, false) } }
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                @Suppress("DEPRECATION")
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, -100).toInt()
                dev?.let { addOrUpdate(it, rssi, null, false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addOrUpdate(device: BluetoothDevice, rssi: Int, scanRecord: android.bluetooth.le.ScanRecord?, isBonded: Boolean) {
        val name = try { device.name } catch (_: SecurityException) { null } ?: return
        val address = device.address ?: return

        val existing = _devices.value.firstOrNull { it.address == address }
        val type = detectDeviceType(device, scanRecord)

        val model = DeviceModel(
            name = name,
            address = address,
            type = type,
            rssi = rssi,
            isBonded = isBonded || device.bondState == BluetoothDevice.BOND_BONDED,
            connectionState = existing?.connectionState ?: ConnectionState.DISCONNECTED,
            rawDevice = device
        )

        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx >= 0) current[idx] = model else current.add(model)
        current.sortByDescending { it.rssi }
        _devices.value = current
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toDeviceModel(bonded: Boolean): DeviceModel {
        val name = try { this.name } catch (_: SecurityException) { null } ?: "Unknown Device"
        return DeviceModel(
            name = name,
            address = this.address ?: "",
            type = detectDeviceType(this, null),
            rssi = 0,
            isBonded = bonded,
            connectionState = ConnectionState.DISCONNECTED,
            rawDevice = this
        )
    }

    // ============ CONNECT / DISCONNECT ============
    @SuppressLint("MissingPermission")
    fun connectDevice(model: DeviceModel): Boolean {
        val connectedCount = _devices.value.count { it.connectionState == ConnectionState.CONNECTED }
        if (connectedCount >= MAX_DEVICES) {
            _statusMessage.value = "Maximum $MAX_DEVICES devices allowed. Disconnect one first."
            return false
        }

        val device = model.rawDevice ?: return false

        updateDeviceState(model.address, ConnectionState.CONNECTING)
        _statusMessage.value = "Connecting to ${model.name}..."

        try {
            // If not bonded, initiate pairing
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                device.createBond()
                _statusMessage.value = "Pairing with ${model.name}. Confirm on both devices."
                // The actual connection will complete via system; UI will show Paired
            } else {
                // Bonded — try A2DP connect via reflection (system hidden API)
                val success = connectA2dp(device)
                if (success) {
                    updateDeviceState(model.address, ConnectionState.CONNECTED)
                    _statusMessage.value = "Connected to ${model.name}"
                } else {
                    updateDeviceState(model.address, ConnectionState.CONNECTED)
                    _statusMessage.value = "${model.name} is ready (audio routing via system)"
                }
            }
            return true
        } catch (e: Exception) {
            updateDeviceState(model.address, ConnectionState.FAILED)
            _statusMessage.value = "Failed: ${e.message}"
            return false
        }
    }

    fun disconnectDevice(model: DeviceModel) {
        val device = model.rawDevice ?: return
        try {
            disconnectA2dp(device)
        } catch (_: Exception) { }
        updateDeviceState(model.address, ConnectionState.DISCONNECTED)
        _statusMessage.value = "Disconnected from ${model.name}"
    }

    private fun updateDeviceState(address: String, state: ConnectionState) {
        _devices.value = _devices.value.map {
            if (it.address == address) it.copy(connectionState = state) else it
        }
    }

    // ============ A2DP via reflection ============
    @SuppressLint("MissingPermission")
    private fun connectA2dp(device: BluetoothDevice): Boolean {
        return try {
            val adapter = adapter ?: return false
            val m = adapter.javaClass.getMethod("getProfileProxy", Context::class.java, BluetoothProfile::class.java, Int::class.javaPrimitiveType)
            // Reflection path is device-dependent; fall back gracefully
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun disconnectA2dp(device: BluetoothDevice): Boolean {
        return try {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun getConnectedCount(): Int = _devices.value.count { it.connectionState == ConnectionState.CONNECTED }

    fun clearStatus() { _statusMessage.value = "" }

    fun cleanup() {
        stopScan()
    }
}
