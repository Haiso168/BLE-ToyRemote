package com.ycm.remote.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import kotlin.math.max
import kotlin.math.min

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

    var strength by remember { mutableStateOf(60f) }
    var experimentalRange by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf<Int?>(null) }
    var shakeEnabled by remember { mutableStateOf(false) }

    // 记录最近一次发送的帧，方便对照
    var lastFrame by remember { mutableStateOf<String?>(null) }

    fun send(frame: ByteArray, note: String) {
        if (!ready) { onNeedConnection(); return }
        lastFrame = "${Protocol.hex(frame)}   # $note"
        scope.launch { ble.writeWithRetry(frame) }
    }

    // 手机摇晃 → 力度（官方"摇晃控制"的增强版：阈值和范围都可调）
    val context = LocalContext.current
    DisposableEffect(shakeEnabled, ready) {
        if (!shakeEnabled || !ready) return@DisposableEffect onDispose { }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastSentAt = 0L
        var lastSentValue = -1
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val peak = max(event.values[0], max(event.values[1], event.values[2]))
                // 峰值 < 1 视为静止；映射到 0..100（与原小程序一致的思路，但阈值可自行调整）
                val value = if (peak < 1f) 0 else (peak / 4f * 60f + 40f).toInt()
                val clamped = min(100, max(0, value))

                // 节流：至少间隔 120ms，且变化达到 3 才发，避免刷爆 BLE
                val now = System.currentTimeMillis()
                val changed = kotlin.math.abs(clamped - lastSentValue) >= 3
                if (changed && now - lastSentAt >= 120) {
                    lastSentAt = now
                    lastSentValue = clamped
                    scope.launch {
                        if (clamped <= 34) ble.write(Protocol.framePause())
                        else ble.write(Protocol.frameStrength(clamped))
                    }
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

        // ---------------------------------------------------------- 急停
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

        // ---------------------------------------------------------- 启停
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

        // ---------------------------------------------------------- 模式
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

        // ---------------------------------------------------------- 力度
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("力度", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("实验范围 0-255", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(checked = experimentalRange, onCheckedChange = { experimentalRange = it })
        }

        val minV = if (experimentalRange) 0f else Protocol.STRENGTH_MIN_UI.toFloat()
        val maxV = if (experimentalRange) 255f else Protocol.STRENGTH_MAX_UI.toFloat()
        if (strength < minV || strength > maxV) {
            strength = strength.coerceIn(minV, maxV)
        }

        val stepsCount =
            if (experimentalRange) 254 else Protocol.STRENGTH_MAX_UI - Protocol.STRENGTH_MIN_UI - 1

        Slider(
            value = strength,
            onValueChange = { strength = it },
            valueRange = minV..maxV,
            steps = stepsCount,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "当前 ${strength.toInt()}",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
            )
            Button(onClick = { send(Protocol.frameStrength(strength.toInt()), "力度 ${strength.toInt()}") }) {
                Text("发送力度")
            }
        }
        if (!experimentalRange) {
            Text(
                "官方小程序只用 35~100。低于 35 的行为未验证，" +
                    "打开「实验范围」可自己试探（可能等同于停止，也可能有额外档位）。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "注意：0~34 与 101~255 的行为未经真机验证，请谨慎试探。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---------------------------------------------------------- 摇晃
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("摇晃控制", style = MaterialTheme.typography.titleSmall)
                Text(
                    "用手机加速度计实时映射力度（60Hz 采样，内部已节流）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = shakeEnabled, onCheckedChange = { shakeEnabled = it })
        }

        // ---------------------------------------------------------- 帧对照
        lastFrame?.let {
            Text("最近发送", style = MaterialTheme.typography.titleSmall)
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        // ---------------------------------------------------------- 回包
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
