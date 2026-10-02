package com.ycm.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ycm.remote.ble.BleManager
import com.ycm.remote.protocol.Protocol

@Composable
fun ConnectionScreen(
    ble: BleManager,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
) {
    val state by ble.state.collectAsStateWithLifecycle()
    val found by ble.found.collectAsStateWithLifecycle()
    val log by ble.log.collectAsStateWithLifecycle()
    val error by ble.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        if (!ble.hasPermissions()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("缺少蓝牙权限", fontWeight = FontWeight.Bold)
                    Text(
                        "Android 12 及以上需要「附近的设备」权限；" +
                            "Android 11 及以下需要定位权限。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPermissions) { Text("申请权限") }
                }
            }
        }

        if (!ble.isBluetoothEnabled()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("蓝牙未开启", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestEnableBluetooth) { Text("打开蓝牙") }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { if (state == BleManager.State.SCANNING) ble.stopScan() else ble.startScan() },
                enabled = ble.hasPermissions() && ble.isBluetoothEnabled(),
            ) {
                Text(if (state == BleManager.State.SCANNING) "停止扫描" else "扫描设备")
            }
            if (state == BleManager.State.READY || state == BleManager.State.CONNECTING) {
                OutlinedButton(onClick = { ble.disconnect() }) { Text("断开") }
            }
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("附近的设备", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(found, key = { it.address }) { d ->
                val isTarget = d.name == BleManager.DEVICE_NAME
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isTarget)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = d.name ?: "(无名称)",
                                fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(d.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { ble.connect(d.address) }) { Text("连接") }
                    }
                }
            }
            if (found.isEmpty()) {
                item {
                    Text(
                        "还没有设备。请确认玩具已开机，点「扫描设备」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (state == BleManager.State.READY) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("已连接并就绪", fontWeight = FontWeight.Bold)
                    Text(
                        "切到「控制」页开始操作。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("日志", style = MaterialTheme.typography.titleSmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                log.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (log.isEmpty()) {
                    Text("(空)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
