package com.ycm.remote.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ycm.remote.ble.BleManager
import com.ycm.remote.ble.PatternPlayer
import com.ycm.remote.protocol.Protocol
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(
    ble: BleManager,
    player: PatternPlayer,
    onNeedConnection: () -> Unit,
) {
    val state by ble.state.collectAsStateWithLifecycle()
    val notifications by ble.notifications.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val ready = state == BleManager.State.READY

    var strength by remember { mutableIntStateOf(60) }
    var currentMode by remember { mutableStateOf<Int?>(null) }
    var shakeEnabled by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(3.0f) }
    var shakeLiveValue by remember { mutableIntStateOf(0) }
    var lastFrame by remember { mutableStateOf<String?>(null) }

    fun send(frame: ByteArray, note: String) {
        if (!ready) { onNeedConnection(); return }
        lastFrame = "${Protocol.hex(frame)}   # $note"
        scope.launch { ble.writeWithRetry(frame) }
    }

    // ---------------------------------------------------------------- 摇晃控制
    // 思路：用「线性加速度」而不是原始读数。
    //   原始加速度静止时恒为 ~9.8（重力），所以直接映射会一开机就是最大值。
    //   这里先用低通滤波估出重力分量，再取 |a| - |gravity| 作为"晃动强度"。
    //   sensitivity 越大越灵敏（需要的晃动越小就能到 100）。
    val context = LocalContext.current
    DisposableEffect(shakeEnabled, ready, sensitivity) {
        if (!shakeEnabled || !ready) {
            shakeLiveValue = 0
            return@DisposableEffect onDispose { }
        }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var gx = 0f
        var gy = 0f
        var gz = 9.8f          // 重力估计，初值取静止时的典型值
        val alpha = 0.8f       // 低通系数：越大越"迟钝"，重力估计越稳

        var lastSentAt = 0L
        var lastSentValue = -1
        var lastChangeAt = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // 低通滤波估计重力
                gx = alpha * gx + (1 - alpha) * x
                gy = alpha * gy + (1 - alpha) * y
                gz = alpha * gz + (1 - alpha) * z

                // 去掉重力后的线性加速度大小
                val lx = x - gx
                val ly = y - gy
                val lz = z - gz
                val linear = sqrt(lx * lx + ly * ly + lz * lz)

                // 死区：轻微抖动不发指令，避免刷屏
                val dead = 0.35f
                val effective = if (linear <= dead) 0f else linear - dead

                // sensitivity 越大越灵敏
                val gain = sensitivity.coerceIn(0.5f, 12f)
                val value = if (effective <= 0f) {
                    0
                } else {
                    ((effective / gain) * 100f).toInt().coerceIn(0, Protocol.STRENGTH_MAX)
                }

                shakeLiveValue = value

                val now = System.currentTimeMillis()
                if (abs(value - lastSentValue) >= 2) lastChangeAt = now

                // 节流：至少 100ms 间隔，且变化 >= 2 才发
                if (now - lastSentAt >= 100 && abs(value - lastSentValue) >= 2) {
                    lastSentAt = now
                    lastSentValue = value
                    scope.launch {
                        // 值为 0 时直接发暂停帧，语义更明确
                        if (value == 0) ble.write(Protocol.framePause())
                        else ble.write(Protocol.frameStrength(value))
                    }
                }

                // 静止超过 500ms 自动归零，避免残留一个较高力度
                if (value == 0 && lastSentValue != 0 && now - lastChangeAt >= 500) {
                    lastSentAt = now
                    lastSentValue = 0
                    scope.launch { ble.write(Protocol.framePause()) }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        if (!ready) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("尚未连接", fontWeight = FontWeight.Bold)
                    Text("请先到「连接」页连上 YCM-BL001", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------------------------------------------------------------- 急停
        Button(
            onClick = { player.emergencyStop(); lastFrame = "AA 01 00 AB   # 急停" },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text("急停（暂停）", style = MaterialTheme.typography.titleMedium)
        }

        // ---------------------------------------------------------------- 启停
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { send(Protocol.frameStart(), "开始") },
                modifier = Modifier.weight(1f),
            ) { Text("开始") }
            OutlinedButton(
                onClick = { send(Protocol.framePause(), "暂停") },
                modifier = Modifier.weight(1f),
            ) { Text("暂停") }
        }

        // ---------------------------------------------------------------- 模式
        Text("模式", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Protocol.MODES.forEach { mode ->
                FilterChip(
                    selected = currentMode == mode.cmd,
                    onClick = {
                        currentMode = mode.cmd
                        send(Protocol.frameMode(mode.cmd), "模式 ${mode.name}")
                    },
                    label = { Text(mode.name) },
                )
            }
        }

        // ---------------------------------------------------------------- 力度
        Text("力度（0-100）", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = strength.toFloat(),
            onValueChange = { strength = it.toInt() },
            valueRange = Protocol.STRENGTH_MIN.toFloat()..Protocol.STRENGTH_MAX.toFloat(),
            steps = Protocol.STRENGTH_MAX - Protocol.STRENGTH_MIN - 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "当前 $strength",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
            )
            OutlinedButton(onClick = { strength = 0 }) { Text("归零") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send(Protocol.frameStrength(strength), "力度 $strength") }) {
                Text("发送力度")
            }
        }

        // ---------------------------------------------------------------- 摇晃
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("摇晃控制", style = MaterialTheme.typography.titleSmall)
                Text(
                    "晃动手机来控制力度（已扣除重力，静止为 0）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = shakeEnabled, onCheckedChange = { shakeEnabled = it })
        }

        if (shakeEnabled) {
            Text("灵敏度：${"%.1f".format(sensitivity)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0.5f..12f,
            )
            Text(
                "数值越小越灵敏（0.5 最灵敏，12 需要大幅晃动）。" +
                    "如果一震就到顶，往左调；如果晃半天没反应，往右调。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "实时力度 $shakeLiveValue",
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedButton(onClick = { player.emergencyStop() }) { Text("停") }
            }
        }

        // ---------------------------------------------------------------- 帧对照
        lastFrame?.let {
            Text("最近发送", style = MaterialTheme.typography.titleSmall)
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        // ---------------------------------------------------------------- 回包
        Text("AE3C 回包（格式未解析，原样记录）", style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.padding(8.dp)) {
                if (notifications.isEmpty()) {
                    Text("(暂无)", style = MaterialTheme.typography.bodySmall)
                } else {
                    notifications.takeLast(10).reversed().forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
