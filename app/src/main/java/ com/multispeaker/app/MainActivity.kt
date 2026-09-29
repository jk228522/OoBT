package com.multispeaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multispeaker.app.bluetooth.BluetoothManager
import com.multispeaker.app.bluetooth.ConnectionState
import com.multispeaker.app.bluetooth.DeviceModel
import com.multispeaker.app.bluetooth.DeviceType

class MainActivity : ComponentActivity() {

    private lateinit var btManager: BluetoothManager
    private var pendingScan = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted && pendingScan) {
            btManager.startScan()
        }
        pendingScan = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btManager = BluetoothManager(applicationContext)
        setContent {
            MultiSpeakerScreen(
                btManager = btManager,
                onRequestPermissions = { pending ->
                    pendingScan = pending
                    permissionLauncher.launch(btManager.requiredPermissions())
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.cleanup()
    }
}

@Composable
fun MultiSpeakerScreen(
    btManager: BluetoothManager,
    onRequestPermissions: (Boolean) -> Unit
) {
    val devices by btManager.devices.collectAsState()
    val isScanning by btManager.isScanning.collectAsState()
    val status by btManager.statusMessage.collectAsState()

    val connectedCount = devices.count { it.connectionState == ConnectionState.CONNECTED }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF05070F), Color(0xFF0A0E1A), Color(0xFF131A2B))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MultiSpeaker",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF22D3EE)
                    )
                    Text(
                        text = "Synchronized Bluetooth playback",
                        fontSize = 12.sp,
                        color = Color(0xFF8B94A8)
                    )
                }
                Box(
                    modifier = Modifier
                        .background(
                            color = if (connectedCount > 0) Color(0x2210B981) else Color(0x228B94A8),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (connectedCount > 0) Color(0x6610B981) else Color(0x338B94A8),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "$connectedCount / ${BluetoothManager.MAX_DEVICES}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (connectedCount > 0) Color(0xFF10B981) else Color(0xFF8B94A8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (btManager.hasPermissions()) {
                        if (isScanning) btManager.stopScan() else btManager.startScan()
                    } else {
                        onRequestPermissions(true)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) Color(0xFFEF4444) else Color(0xFF22D3EE)
                )
            ) {
                Text(
                    text = if (isScanning) "Stop Scan" else "Scan for Devices",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF05070F)
                )
            }

            if (status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = Color(0xFF8B94A8),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isScanning) "Scanning..." else "No devices found",
                            fontSize = 16.sp,
                            color = Color(0xFF8B94A8)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Turn on Bluetooth and tap Scan",
                            fontSize = 12.sp,
                            color = Color(0xFF556070)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            onConnect = { btManager.connectDevice(device) },
                            onDisconnect = { btManager.disconnectDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = device.connectionState == ConnectionState.CONNECTED
    val isConnecting = device.connectionState == ConnectionState.CONNECTING

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0x14FFFFFF),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = 1.dp,
                color = when {
                    isConnected -> Color(0x6610B981)
                    isConnecting -> Color(0x66F59E0B)
                    else -> Color(0x1AFFFFFF)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EEFC),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = device.address,
                        fontSize = 11.sp,
                        color = Color(0xFF8B94A8)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tagText: String
                    val tagColor: Color
                    when (device.type) {
                        DeviceType.LE_AUDIO -> {
                            tagText = "LE Audio"
                            tagColor = Color(0xFF10B981)
                        }
                        DeviceType.A2DP_CLASSIC -> {
                            tagText = "A2DP"
                            tagColor = Color(0xFF22D3EE)
                        }
                        DeviceType.UNKNOWN -> {
                            tagText = "Unknown"
                            tagColor = Color(0xFF8B94A8)
                        }
                    }
                    Tag(text = tagText, color = tagColor)

                    if (device.isBonded) {
                        Tag(text = "Paired", color = Color(0xFFA855F7))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0x33EF4444)
                        )
                    ) {
                        Text("Disconnect", color = Color(0xFFEF4444), fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = !isConnecting,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF22D3EE)
                        )
                    ) {
                        Text(
                            text = if (isConnecting) "Connecting..." else "Connect",
                            color = Color(0xFF05070F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
            .border(width = 1.dp, color = color.copy(alpha = 0.4f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}
