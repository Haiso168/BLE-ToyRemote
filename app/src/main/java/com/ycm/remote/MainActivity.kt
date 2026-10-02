package com.ycm.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ycm.remote.ble.BleManager
import com.ycm.remote.ble.PatternPlayer
import com.ycm.remote.ui.ConnectionScreen
import com.ycm.remote.ui.ControlScreen
import com.ycm.remote.ui.PatternScreen
import com.ycm.remote.ui.theme.YcmRemoteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ble = BleManager(applicationContext)
        val player = PatternPlayer(ble)

        setContent {
            YcmRemoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(ble = ble, player = player)
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    CONNECT("连接"),
    CONTROL("控制"),
    PATTERN("波形"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(ble: BleManager, player: PatternPlayer) {

    // 用 remember 而非 rememberSaveable：enum 的自动 Saver 行为不保证，
    // 旋转屏幕后回到默认页是可接受的代价。
    var tab by remember { mutableStateOf(Tab.CONNECT) }
    val state by ble.state.collectAsStateWithLifecycle()
    val error by ble.error.collectAsStateWithLifecycle()

    // 权限申请
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            ble.setError(null)
        } else {
            ble.setError("蓝牙权限被拒绝，无法扫描/连接")
        }
    }

    // 进入时若缺权限则申请
    LaunchedEffect(Unit) {
        if (!ble.hasPermissions()) {
            permissionLauncher.launch(ble.requiredPermissions())
        }
    }

    // 蓝牙未开启时提示
    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* 结果由 state 反映 */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YCM-BL001 遥控器") },
                actions = {
                    Text(
                        text = when (state) {
                            BleManager.State.IDLE -> "未连接"
                            BleManager.State.SCANNING -> "扫描中"
                            BleManager.State.CONNECTING -> "连接中"
                            BleManager.State.READY -> "已就绪"
                            BleManager.State.DISCONNECTED -> "已断开"
                            BleManager.State.ERROR -> "出错"
                        },
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.CONNECT,
                    onClick = { tab = Tab.CONNECT },
                    icon = { Text("①") },
                    label = { Text(Tab.CONNECT.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.CONTROL,
                    onClick = { tab = Tab.CONTROL },
                    icon = { Text("②") },
                    label = { Text(Tab.CONTROL.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.PATTERN,
                    onClick = { tab = Tab.PATTERN },
                    icon = { Text("③") },
                    label = { Text(Tab.PATTERN.label) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Top,
        ) {
            // 常驻错误提示（不自动消失，便于看清原因）
            error?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { ble.setError(null) }) { Text("知道了") }
                }
            }

            when (tab) {
                Tab.CONNECT -> ConnectionScreen(
                    ble = ble,
                    onRequestPermissions = { permissionLauncher.launch(ble.requiredPermissions()) },
                    onRequestEnableBluetooth = {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                )

                Tab.CONTROL -> ControlScreen(
                    ble = ble,
                    player = player,
                    onNeedConnection = { tab = Tab.CONNECT; ble.setError("请先连接玩具") },
                )

                Tab.PATTERN -> PatternScreen(
                    ble = ble,
                    player = player,
                    onNeedConnection = { tab = Tab.CONNECT; ble.setError("请先连接玩具") },
                )
            }
        }
    }
}
