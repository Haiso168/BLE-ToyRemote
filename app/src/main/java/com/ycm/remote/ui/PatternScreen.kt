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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ycm.remote.ble.BleManager
import com.ycm.remote.ble.PatternPlayer
import com.ycm.remote.protocol.BuiltinPatterns
import com.ycm.remote.protocol.Pattern
import com.ycm.remote.protocol.Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternScreen(
    ble: BleManager,
    player: PatternPlayer,
    onNeedConnection: () -> Unit,
) {
    val state by ble.state.collectAsStateWithLifecycle()
    val progress by player.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ready = state == BleManager.State.READY

    var script by remember { mutableStateOf(BuiltinPatterns.ramp) }
    var patternName by remember { mutableStateOf("爬升") }
    var loop by remember { mutableStateOf(false) }
    var repeatCount by remember { mutableStateOf(1f) }
    var parseError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        Text("自定义波形", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "每行一步：力度 [持续毫秒]。力度 0 表示暂停。支持 # 注释。\n" +
                "官方小程序只能发单点力度，这里可以编排任意时序。",
            style = MaterialTheme.typography.bodySmall,
        )

        // ------------------------------------------------ 内置示例
        Text("内置示例", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BuiltinPatterns.all.forEach { (name, text) ->
                FilterChip(
                    selected = false,
                    onClick = { script = text; patternName = name },
                    label = { Text(name) },
                )
            }
        }

        OutlinedTextField(
            value = patternName,
            onValueChange = { patternName = it },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = script,
            onValueChange = { script = it; parseError = null },
            label = { Text("脚本") },
            minLines = 8,
            maxLines = 16,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = parseError != null,
            supportingText = { parseError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
        )

        // ------------------------------------------------ 播放参数
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("无限循环", modifier = Modifier.weight(1f))
            Switch(checked = loop, onCheckedChange = { loop = it })
        }
        if (!loop) {
            Text("重复次数：${repeatCount.toInt()}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = repeatCount,
                onValueChange = { repeatCount = it },
                valueRange = 1f..20f,
                steps = 18,
            )
        }

        // ------------------------------------------------ 播放控制
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (!ready) { onNeedConnection(); return@Button }
                    val result = Pattern.parse(
                        text = script,
                        name = patternName,
                        loop = loop,
                        repeat = repeatCount.toInt(),
                    )
                    result.fold(
                        onSuccess = { p ->
                            parseError = null
                            player.play(p)
                        },
                        onFailure = { parseError = it.message },
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = !progress.playing,
            ) { Text("播放") }

            Button(
                onClick = { player.stop() },
                modifier = Modifier.weight(1f),
                enabled = progress.playing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("停止") }
        }

        if (progress.playing) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("正在播放：${progress.patternName}", fontWeight = FontWeight.Bold)
                    Text(
                        "第 ${progress.stepIndex}/${progress.stepCount} 步    " +
                            "力度 ${progress.strength}" +
                            if (loop) "    第 ${progress.loopRound} 轮" else "",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ------------------------------------------------ 原始帧
        RawFrameCard(ble = ble, ready = ready, onNeedConnection = onNeedConnection)

        // ------------------------------------------------ 命令探测
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

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发送原始帧", style = MaterialTheme.typography.titleSmall)
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProbeCard(
    ble: BleManager,
    ready: Boolean,
    onNeedConnection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(1f) }
    var fromCmd by remember { mutableStateOf(0f) }
    var toCmd by remember { mutableStateOf(32f) }
    var intervalMs by remember { mutableStateOf(600f) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("命令空间探测（找隐藏功能）", style = MaterialTheme.typography.titleSmall)
            Text(
                "校验和只是求和，固件无法用它做白名单过滤，所以可以安全地遍历未知命令字。\n" +
                    "已知：组 1 的 0/1/2-9/16/17 有定义；其余编号未知。",
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!ready) { onNeedConnection(); return@Button }
                        val g = group.toInt()
                        val a = minOf(fromCmd.toInt(), toCmd.toInt())
                        val b = maxOf(fromCmd.toInt(), toCmd.toInt())
                        running = true
                        status = "正在遍历 $g / $a..$b（请观察玩具）"
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
                ) { Text(if (running) "遍历中..." else "开始遍历") }

                OutlinedButton(
                    onClick = { ble.disconnect(); running = false },
                ) { Text("中止（断开）") }
            }

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(
                "提示：这段遍历会真的驱动玩具，请只在安全环境下使用。" +
                    "若出现异常动作，立刻按「中止」或控制页的急停。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
