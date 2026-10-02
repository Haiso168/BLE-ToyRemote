package com.ycm.remote.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
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
    var shakeThreshold by remember { mutableIntStateOf(15) }
    var shakeLiveValue by remember { mutableIntStateOf(0) }
    var lastFrame by remember { mutableStateOf<String?>(null) }

    fun send(frame: ByteArray, note: String) {
        if (!ready) { onNeedConnection(); return }
        lastFrame = "${Protocol.hex(frame)}   # $note"
        scope.launch { ble.writeWithRetry(frame) }
    }

    // ---------------------------------------------------------------- 摇晃控制
    // 用「线性加速度」而不是原始读数：
    //   原始加速度静止时恒为 ~9.8（重力），直接映射会一开机就是最大值。
    //   这里先用低通滤波估出重力分量，再取扣除后的模长作为"晃动强度"。
    // 计算出的力度必须 >= shakeThreshold 才会发送，避免轻微晃动就抖动输出。
    val context = LocalContext.current
    DisposableEffect(shakeEnabled, ready, sensitivity, shakeThreshold) {
        if (!shakeEnabled || !ready) {
            shakeLiveValue = 0
            return@DisposableEffect onDispose { }
        }

        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE)
            as android.hardware.SensorManager
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)

        var gx = 0f
        var gy = 0f
        var gz = 9.8f          // 重力估计，初值取静止时的典型值
        val alpha = 0.8f       // 低通系数：越大重力估计越稳

        var lastSentAt = 0L
        var lastSentValue = -1
        var deviceStopped = true

        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                gx = alpha * gx + (1 - alpha) * x
                gy = alpha * gy + (1 - alpha) * y
                gz = alpha * gz + (1 - alpha) * z

                val lx = x - gx
                val ly = y - gy
                val lz = z - gz
                val linear = sqrt(lx * lx + ly * ly + lz * lz)

                val dead = 0.3f
                val effective = if (linear <= dead) 0f else linear - dead

                val gain = sensitivity.coerceIn(0.5f, 12f)
                val computed = if (effective <= 0f) {
                    0
                } else {
                    ((effective / gain) * 100f).toInt().coerceIn(0, Protocol.STRENGTH_MAX)
                }

                shakeLiveValue = computed

                // 阈值：低于阈值一律当作停止
                val target = if (computed >= shakeThreshold) computed else 0

                val now = System.currentTimeMillis()
                if (now - lastSentAt < 60) return
                if (target == lastSentValue) return

                lastSentAt = now
                lastSentValue = target

                if (target == 0) {
                    if (!deviceStopped) {
                        deviceStopped = true
                        scope.launch { ble.write(Protocol.framePause()) }
                    }
                } else {
                    deviceStopped = false
                    scope.launch { ble.write(Protocol.frameStrength(target)) }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(
            listener,
            accel,
            android.hardware.SensorManager.SENSOR_DELAY_GAME,
        )
        onDispose { sm.unregisterListener(listener) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        if (!ready) {
            SectionCard(
                title = "尚未连接",
                subtitle = "请先到「连接」页连上 YCM-BL001",
                icon = Icons.Filled.Info,
                accent = MaterialTheme.colorScheme.error,
            ) {
                OutlinedButton(onClick = onNeedConnection) { Text("去连接") }
            }
        }

        // ---------------------------------------------------------------- 急停
        SectionCard(
            title = "急停",
            subtitle = "任何时刻都能立刻停下",
            icon = Icons.Filled.Bolt,
            accent = MaterialTheme.colorScheme.error,
        ) {
            Button(
                onClick = { player.emergencyStop(); lastFrame = "AA 01 00 AB   # 急停" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("立即暂停", style = MaterialTheme.typography.titleMedium)
            }
        }

        // ---------------------------------------------------------------- 启停
        SectionCard(
            title = "启停",
            icon = Icons.Filled.PlayCircle,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { send(Protocol.frameStart(), "开始") },
                    modifier = Modifier.weight(1f),
                ) { Text("开始") }
                OutlinedButton(
                    onClick = { send(Protocol.framePause(), "暂停") },
                    modifier = Modifier.weight(1f),
                ) { Text("暂停") }
            }
        }

        // ---------------------------------------------------------------- 模式
        SectionCard(
            title = "模式",
            subtitle = "点一下立即切换",
            icon = Icons.Filled.Vibration,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
        }

        // ---------------------------------------------------------------- 力度
        SectionCard(
            title = "力度",
            subtitle = "范围 ${Protocol.STRENGTH_MIN}-${Protocol.STRENGTH_MAX}",
            icon = Icons.Filled.Speed,
        ) {
            Slider(
                value = strength.toFloat(),
                onValueChange = { strength = it.toInt() },
                valueRange = Protocol.STRENGTH_MIN.toFloat()..Protocol.STRENGTH_MAX.toFloat(),
                steps = Protocol.STRENGTH_MAX - Protocol.STRENGTH_MIN - 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledValue(
                    label = "当前",
                    value = strength.toString(),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { strength = 0 }) { Text("归零") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { send(Protocol.frameStrength(strength), "力度 $strength") }) {
                    Text("发送")
                }
            }
        }

        // ---------------------------------------------------------------- 摇晃
        SectionCard(
            title = "摇晃控制",
            subtitle = "晃动手机来实时控制力度（已扣除重力，静止为 0）",
            icon = Icons.Filled.Vibration,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", modifier = Modifier.weight(1f))
                Switch(checked = shakeEnabled, onCheckedChange = { shakeEnabled = it })
            }

            if (shakeEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledValue(
                        label = "实时力度",
                        value = shakeLiveValue.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { player.emergencyStop() }) { Text("停") }
                }

                Text("最小阈值：$shakeThreshold", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = shakeThreshold.toFloat(),
                    onValueChange = { shakeThreshold = it.toInt() },
                    valueRange = 0f..Protocol.STRENGTH_MAX.toFloat(),
                    steps = Protocol.STRENGTH_MAX - 1,
                )
                Text(
                    "算出的力度达到该值才会发送，低于它按停止处理。默认 15。",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("灵敏度：${"%.1f".format(sensitivity)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 0.5f..12f,
                )
                Text(
                    "数值越小越灵敏（0.5 最灵敏，12 需要大幅晃动）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---------------------------------------------------------------- 调试
        RawFrameCard(ble = ble, ready = ready, onNeedConnection = onNeedConnection)

        SectionCard(
            title = "回包记录",
            subtitle = "AE3C 通知格式未解析，此处原样记录",
            icon = Icons.Filled.Info,
        ) {
            lastFrame?.let {
                Text("最近发送", style = MaterialTheme.typography.bodySmall)
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (notifications.isEmpty()) {
                Text("(暂无回包)", style = MaterialTheme.typography.bodySmall)
            } else {
                notifications.takeLast(8).reversed().forEach {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ProbeCard(ble = ble, ready = ready, onNeedConnection = onNeedConnection)
    }
}

@Composable
private fun RawFrameCard(
    ble: BleManager,
    ready: Boolean,
    onNeedConnection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("AA 01 02 AD") }
    var parsed by remember { mutableStateOf<ByteArray?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "发送原始帧",
        subtitle = "直接下发任意 HEX，便于手工试验",
        icon = Icons.Filled.Send,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                parsed = Protocol.parseHex(it)
                note = null
            },
            label = { Text("HEX，例如 AA 01 02 AD") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = parsed == null && input.isNotBlank(),
            supportingText = {
                parsed?.let {
                    Text("${it.size} 字节   校验和${if (Protocol.isValid(it)) "正确" else "不正确"}")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val frame = parsed ?: return@Button
                if (!ready) { onNeedConnection(); return@Button }
                scope.launch {
                    val ok = ble.writeWithRetry(frame)
                    note = if (ok) "已发送 ${Protocol.hex(frame)}" else "发送失败，见日志"
                }
            },
            enabled = parsed != null,
        ) { Text("发送") }
    }
}

@Composable
private fun ProbeCard(
    ble: BleManager,
    ready: Boolean,
    onNeedConnection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var group by remember { mutableFloatStateOf(1f) }
    var fromCmd by remember { mutableFloatStateOf(0f) }
    var toCmd by remember { mutableFloatStateOf(32f) }
    var intervalMs by remember { mutableFloatStateOf(600f) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "命令空间探测",
        subtitle = "遍历未知命令字，可能挖出小程序没做的隐藏功能",
        icon = Icons.Filled.Warning,
        accent = MaterialTheme.colorScheme.error,
    ) {
        Text(
            "校验和只是求和，固件无法用它做白名单过滤，所以遍历未知命令是安全的。" +
                "已知组 1 的 0/1/2-9/16/17 有定义。",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("命令组：${group.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(value = group, onValueChange = { group = it }, valueRange = 0f..8f, steps = 7)

        Text(
            "命令字范围：${fromCmd.toInt()} → ${toCmd.toInt()}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(
                value = fromCmd,
                onValueChange = { fromCmd = it },
                valueRange = 0f..255f,
                modifier = Modifier.weight(1f),
            )
            Slider(
                value = toCmd,
                onValueChange = { toCmd = it },
                valueRange = 0f..255f,
                modifier = Modifier.weight(1f),
            )
        }

        Text("每步间隔：${intervalMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = intervalMs,
            onValueChange = { intervalMs = it },
            valueRange = 200f..3000f,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (!ready) { onNeedConnection(); return@Button }
                    val g = group.toInt()
                    val a = minOf(fromCmd.toInt(), toCmd.toInt())
                    val b = maxOf(fromCmd.toInt(), toCmd.toInt())
                    running = true
                    status = "正在遍历 组$g / $a..$b（请观察玩具）"
                    scope.launch {
                        for (cmd in a..b) {
                            status = "已发 组$g 命令字 $cmd"
                            ble.write(Protocol.frame(g, cmd))
                            delay(intervalMs.toLong())
                        }
                        ble.write(Protocol.framePause())
                        status = "遍历结束，已发暂停"
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { Text(if (running) "遍历中..." else "开始遍历") }

            OutlinedButton(
                onClick = { ble.disconnect(); running = false },
                modifier = Modifier.weight(1f),
            ) { Text("中止") }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Text(
            "注意：这段遍历会真的驱动玩具，请只在安全环境下使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
