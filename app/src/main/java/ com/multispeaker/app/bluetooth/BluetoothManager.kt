package com.multispeaker.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
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
import java.util.UUID

class BluetoothManager(private val context: Context) {

    companion object {
        const val MAX_DEVICES = 3
        private val LE_AUDIO_UUID: UUID =
            UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")
        private val PACS_UUID: UUID =
            UUID.fromString("00001850-0000-1000-8000-00805F9B34FB")
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
    private var classicReceiverRegistered = false

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
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun detectDeviceType(
        device: BluetoothDevice,
        scanRecord: ScanRecord?
    ): DeviceType {
        val serviceUuids = scanRecord?.serviceUuids
        if (serviceUuids != null) {
            val hasLeAudio = serviceUuids.any { parcelUuid ->
                parcelUuid.uuid == LE_AUDIO_UUID || parcelUuid.uuid == PACS_UUID
            }
            if (hasLeAudio) return DeviceType.LE_AUDIO
        }

        val btClass = try {
            device.bluetoothClass
        } catch (_: SecurityException) {
            null
        }
        if (btClass != null) {
            val major = btClass.majorDeviceClass
            if (major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) {
                return DeviceType.A2DP_CLASSIC
            }
        }

        val name = try {
            device.name ?: ""
        } catch (_: SecurityException) {
            ""
        }
        val lower = name.lowercase()
        return when {
            lower.contains("le audio") || lower.contains("lc3") -> DeviceType.LE_AUDIO
            lower.contains("buds") ||
                lower.contains("speaker") ||
                lower.contains("headphone") ||
                lower.contains("sound") ||
                lower.contains("audio") -> DeviceType.A2DP_CLASSIC
            else -> DeviceType.UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val localAdapter = adapter
        if (localAdapter == null) {
            _statusMessage.value = "Bluetooth not available on this device"
            return
        }
        if (!localAdapter.isEnabled) {
            _statusMessage.value = "Please turn ON Bluetooth"
            return
        }
        if (!hasPermissions()) {
            _statusMessage.value = "Bluetooth permissions required"
            return
        }
        if (_isScanning.value) return

        val bonded: Set<BluetoothDevice> = try {
            localAdapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
        _devices.value = bonded.map { it.toDeviceModel(true) }

        _isScanning.value = true
        _statusMessage.value = "Scanning for devices..."

        val scanner = try {
            localAdapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        }

        if (scanner != null) {
            try {
                scanner.startScan(leScanCallback)
            } catch (_: SecurityException) {
                _statusMessage.value = "Scan permission missing"
            }
        } else {
            try {
                if (!classicReceiverRegistered) {
                    ContextCompat.registerReceiver(
                        context,
                        classicReceiver,
                        IntentFilter(BluetoothDevice.ACTION_FOUND),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    classicReceiverRegistered = true
                }
                localAdapter.startDiscovery()
            } catch (_: SecurityException) { }
        }

        handler.postDelayed({ stopScan() }, 15_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val localAdapter = adapter ?: return
        if (!_isScanning.value) return

        try {
            localAdapter.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: SecurityException) { }

        try {
            localAdapter.cancelDiscovery()
        } catch (_: SecurityException) { }

        if (classicReceiverRegistered) {
            try {
                context.unregisterReceiver(classicReceiver)
            } catch (_: Exception) { }
            classicReceiverRegistered = false
        }

        _isScanning.value = false
        _statusMessage.value = "Scan complete"
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            addOrUpdate(device, result.rssi, result.scanRecord, false)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { r ->
                val device = r.device ?: return@forEach
                addOrUpdate(device, r.rssi, r.scanRecord, false)
            }
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val dev: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                @Suppress("DEPRECATION")
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-100).toShort()).toInt()
                if (dev != null) {
                    addOrUpdate(dev, rssi, null, false)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addOrUpdate(
        device: BluetoothDevice,
        rssi: Int,
        scanRecord: ScanRecord?,
        isBonded: Boolean
    ) {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        } ?: return

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
        val deviceName = try {
            this.name
        } catch (_: SecurityException) {
            null
        } ?: "Unknown Device"

        return DeviceModel(
            name = deviceName,
            address = this.address ?: "",
            type = detectDeviceType(this, null),
            rssi = 0,
            isBonded = bonded,
            connectionState = ConnectionState.DISCONNECTED,
            rawDevice = this
        )
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(model: DeviceModel): Boolean {
        val connectedCount = _devices.value.count {
            it.connectionState == ConnectionState.CONNECTED
        }
        if (connectedCount >= MAX_DEVICES) {
            _statusMessage.value =
                "Maximum $MAX_DEVICES devices allowed. Disconnect one first."
            return false
        }

        val device = model.rawDevice
        if (device == null) {
            _statusMessage.value = "Device reference not available"
            return false
        }

        updateDeviceState(model.address, ConnectionState.CONNECTING)
        _statusMessage.value = "Connecting to ${model.name}..."

        return try {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                val started = device.createBond()
                if (started) {
                    _statusMessage.value =
                        "Pairing with ${model.name}. Confirm on both devices."
                } else {
                    updateDeviceState(model.address, ConnectionState.CONNECTED)
                    _statusMessage.value = "${model.name} is ready"
                }
            } else {
                updateDeviceState(model.address, ConnectionState.CONNECTED)
                _statusMessage.value = "Connected to ${model.name}"
            }
            true
        } catch (e: SecurityException) {
            updateDeviceState(model.address, ConnectionState.FAILED)
            _statusMessage.value = "Permission denied: ${e.message}"
            false
        } catch (e: Exception) {
            updateDeviceState(model.address, ConnectionState.FAILED)
            _statusMessage.value = "Failed: ${e.message}"
            false
        }
    }

    fun disconnectDevice(model: DeviceModel) {
        updateDeviceState(model.address, ConnectionState.DISCONNECTED)
        _statusMessage.value = "Disconnected from ${model.name}"
    }

    private fun updateDeviceState(address: String, state: ConnectionState) {
        _devices.value = _devices.value.map { d ->
            if (d.address == address) d.copy(connectionState = state) else d
        }
    }

    fun cleanup() {
        stopScan()
    }
}
