package com.ycm.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val ready = state == BleManager.State.READY

    var script by remember { mutableStateOf(BuiltinPatterns.ramp) }
    var patternName by remember { mutableStateOf("爬升") }
    var loop by remember { mutableStateOf(false) }
    var repeatCount by remember { mutableFloatStateOf(1f) }
    var parseError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // ------------------------------------------------------------ 波形编辑
        SectionCard(
            title = "自定义波形",
            subtitle = "每行一步：力度 [持续毫秒]，力度 0 表示暂停，支持 # 注释",
            icon = Icons.Filled.Edit,
        ) {
            Text("内置示例", style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BuiltinPatterns.all.forEach { (name, text) ->
                    FilterChip(
                        selected = false,
                        onClick = { script = text; patternName = name; parseError = null },
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
                supportingText = {
                    parseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ------------------------------------------------------------ 播放
        SectionCard(
            title = "播放",
            icon = Icons.Filled.PlayArrow,
        ) {
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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (!ready) { onNeedConnection(); return@Button }
                        Pattern.parse(
                            text = script,
                            name = patternName,
                            loop = loop,
                            repeat = repeatCount.toInt(),
                        ).fold(
                            onSuccess = { p -> parseError = null; player.play(p) },
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
                Text(
                    "正在播放：${progress.patternName}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "第 ${progress.stepIndex}/${progress.stepCount} 步    " +
                        "力度 ${progress.strength}" +
                        if (loop) "    第 ${progress.loopRound} 轮" else "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------------------------ 原始帧
        RawFrameCard(ble = ble, ready = ready, onNeedConnection = onNeedConnection)

        // ------------------------------------------------------------ 命令探测
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
            "注意：这段遍历会真的驱动玩具，请只在安全环境下使用。" +
                "出现异常动作立刻按「中止」或去控制页急停。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
