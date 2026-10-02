package com.ycm.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // ------------------------------------------------------------ 权限 / 蓝牙
        if (!ble.hasPermissions()) {
            SectionCard(
                title = "缺少蓝牙权限",
                subtitle = "Android 12 及以上需要「附近的设备」；Android 11 及以下需要定位权限",
                icon = Icons.Filled.Info,
                accent = MaterialTheme.colorScheme.error,
            ) {
                Button(onClick = onRequestPermissions) { Text("申请权限") }
            }
        }

        if (!ble.isBluetoothEnabled()) {
            SectionCard(
                title = "蓝牙未开启",
                icon = Icons.Filled.Info,
                accent = MaterialTheme.colorScheme.error,
            ) {
                Button(onClick = onRequestEnableBluetooth) { Text("打开蓝牙") }
            }
        }

        // ------------------------------------------------------------ 连接状态
        val statusText = when (state) {
            BleManager.State.IDLE -> "未连接"
            BleManager.State.SCANNING -> "扫描中"
            BleManager.State.CONNECTING -> "连接中"
            BleManager.State.READY -> "已就绪"
            BleManager.State.DISCONNECTED -> "已断开"
            BleManager.State.ERROR -> "出错"
        }
        SectionCard(
            title = "连接状态",
            subtitle = statusText,
            icon = Icons.Filled.CheckCircle,
            accent = if (state == BleManager.State.READY) MaterialTheme.colorScheme.primary else null,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (state == BleManager.State.SCANNING) ble.stopScan() else ble.startScan()
                    },
                    enabled = ble.hasPermissions() && ble.isBluetoothEnabled(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state == BleManager.State.SCANNING) "停止扫描" else "扫描设备")
                }
                if (state == BleManager.State.READY || state == BleManager.State.CONNECTING) {
                    OutlinedButton(
                        onClick = { ble.disconnect() },
                        modifier = Modifier.weight(1f),
                    ) { Text("断开") }
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------------------------ 设备列表
        SectionCard(
            title = "附近的设备",
            subtitle = if (found.isEmpty()) "点「扫描设备」开始查找" else "共 ${found.size} 个",
            icon = Icons.Filled.BluetoothSearching,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(found, key = { it.address }) { d ->
                    val isTarget = d.name == BleManager.DEVICE_NAME
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isTarget)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = d.name ?: "(无名称)",
                                    fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = d.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                text = "${d.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { ble.connect(d.address) }) { Text("连接") }
                        }
                    }
                }
                if (found.isEmpty()) {
                    item {
                        Text(
                            "还没有设备。请确认玩具已开机，然后点「扫描设备」。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ 日志
        SectionCard(
            title = "日志",
            subtitle = "连接、服务发现、写入结果都会记录在这里",
            icon = Icons.Filled.Terminal,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 260.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(10.dp),
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    if (log.isEmpty()) {
                        Text("(空)", style = MaterialTheme.typography.bodySmall)
                    } else {
                        log.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}
