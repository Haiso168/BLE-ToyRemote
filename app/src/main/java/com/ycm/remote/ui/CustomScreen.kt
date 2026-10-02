package com.ycm.remote.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ycm.remote.ble.BleManager
import com.ycm.remote.ble.PatternPlayer
import com.ycm.remote.data.AlarmScheduler
import com.ycm.remote.data.PatternStore
import com.ycm.remote.data.RingtoneKind
import com.ycm.remote.data.RingtoneSpec
import com.ycm.remote.protocol.BuiltinPatterns
import com.ycm.remote.protocol.Pattern
import com.ycm.remote.protocol.PatternCodec
import com.ycm.remote.protocol.Protocol
import com.ycm.remote.util.FileTransfer
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomScreen(
    ble: BleManager,
    player: PatternPlayer,
    onNeedConnection: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PatternStore(context) }
    val pickText = FileTransfer.rememberTextPicker()

    val state by ble.state.collectAsStateWithLifecycle()
    val progress by player.progress.collectAsStateWithLifecycle()
    val ready = state == BleManager.State.READY

    var script by remember { mutableStateOf(BuiltinPatterns.ramp) }
    var patternName by remember { mutableStateOf("爬升") }
    var loop by remember { mutableStateOf(false) }
    var repeatCount by remember { mutableFloatStateOf(1f) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(store.loadAll()) }

    fun refresh() { saved = store.loadAll() }

    fun currentPattern(): Pattern? = Pattern.parse(
        text = script,
        name = patternName.ifBlank { "未命名" },
        loop = loop,
        repeat = repeatCount.toInt(),
    ).getOrElse { parseError = it.message; null }

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
                        onClick = { script = text; patternName = name; parseError = null; notice = null },
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

            // 已保存列表，点一下载入编辑器
            if (saved.isNotEmpty()) {
                Text("已保存", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    saved.forEach { p ->
                        FilterChip(
                            selected = p.name == patternName,
                            onClick = {
                                script = p.toScriptText()
                                patternName = p.name
                                loop = p.loop
                                repeatCount = p.repeat.toFloat()
                                parseError = null
                                notice = "已载入「${p.name}」"
                            },
                            label = { Text(p.name) },
                        )
                    }
                }
            }
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
                        if (!ready) {
                            onNeedConnection()
                        } else {
                            Pattern.parse(
                                script,
                                patternName.ifBlank { "未命名" },
                                loop,
                                repeatCount.toInt(),
                            ).fold(
                                onSuccess = { p -> parseError = null; notice = null; player.play(p) },
                                onFailure = { parseError = it.message },
                            )
                        }
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
                    "第 ${progress.stepIndex}/${progress.stepCount} 步    力度 ${progress.strength}" +
                        if (loop) "    第 ${progress.loopRound} 轮" else "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------------------------ 保存 / 导入 / 导出
        SectionCard(
            title = "保存 / 导入 / 导出",
            subtitle = "导出为文本文件，可分享或备份；导入支持导出文件或纯脚本",
            icon = Icons.Filled.Save,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val p = currentPattern()
                        if (p != null) {
                            val finalName =
                                if (store.exists(p.name)) store.uniqueName(p.name) else p.name
                            if (store.save(p.copy(name = finalName))) {
                                patternName = finalName
                                refresh()
                                notice = "已保存「$finalName」"
                            } else {
                                notice = "保存失败"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        // 注意：Button 的 onClick 不是 inline lambda，
                        // 不能用 return@Button 提前返回，只能用 if/else 组织。
                        val p = currentPattern()
                        if (p != null) {
                            store.delete(p.name)
                            refresh()
                            notice = "已删除「${p.name}」"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = store.exists(patternName),
                ) { Text("删除") }

                OutlinedButton(
                    onClick = {
                        val p = currentPattern()
                        if (p != null) {
                            FileTransfer.shareText(
                                context = context,
                                fileName = PatternCodec.safeFileName(p.name),
                                content = store.export(p),
                            )
                            notice = "已导出「${p.name}」"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("导出") }
            }

            OutlinedButton(
                onClick = {
                    pickText { text ->
                        if (text == null) {
                            notice = "未选择文件或读取失败"
                        } else {
                            store.import(text).fold(
                                onSuccess = { p ->
                                    refresh()
                                    script = p.toScriptText()
                                    patternName = p.name
                                    loop = p.loop
                                    repeatCount = p.repeat.toFloat()
                                    notice = "已导入「${p.name}」，共 ${p.steps.size} 步"
                                },
                                onFailure = { notice = "导入失败：${it.message}" },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("从文件导入")
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "存储位置：应用私有目录（卸载会清空）。" +
                    "想长期保留请用「导出」存到网盘或聊天记录里。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ------------------------------------------------------------ 闹钟
        AlarmCard(
            ble = ble,
            player = player,
            store = store,
            ready = ready,
            onNeedConnection = onNeedConnection,
        )
    }
}

/** 把 Pattern 转回可编辑脚本文本 */
private fun Pattern.toScriptText(): String =
    steps.joinToString("\n") { "${it.strength} ${it.durationMs}" }

// ---------------------------------------------------------------- 闹钟

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmCard(
    ble: BleManager,
    player: PatternPlayer,
    store: PatternStore,
    ready: Boolean,
    onNeedConnection: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(7) }
    var minute by remember { mutableStateOf(30) }
    var ringtoneKind by remember { mutableStateOf(RingtoneKind.MODE) }
    var modeCmd by remember { mutableStateOf(Protocol.MODES.first().cmd) }
    var patternName by remember { mutableStateOf(store.loadAll().firstOrNull()?.name) }
    var alarmStrength by remember { mutableFloatStateOf(80f) }
    var status by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "闹钟",
        subtitle = "到点后自动播放选定的「铃声」——可以是预设模式，也可以是自定义波形",
        icon = Icons.Filled.Alarm,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d:%02d".format(hour, minute),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, h, m -> hour = h; minute = m },
                        hour,
                        minute,
                        true,
                    ).show()
                },
            ) { Text("选时间") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Text("铃声类型", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ringtoneKind == RingtoneKind.MODE,
                onClick = { ringtoneKind = RingtoneKind.MODE },
                label = { Text("预设模式") },
            )
            FilterChip(
                selected = ringtoneKind == RingtoneKind.PATTERN,
                onClick = { ringtoneKind = RingtoneKind.PATTERN },
                label = { Text("自定义波形") },
            )
        }

        when (ringtoneKind) {
            RingtoneKind.MODE -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Protocol.MODES.forEach { m ->
                        FilterChip(
                            selected = modeCmd == m.cmd,
                            onClick = { modeCmd = m.cmd },
                            label = { Text(m.name) },
                        )
                    }
                }
                Text("力度：${alarmStrength.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = alarmStrength,
                    onValueChange = { alarmStrength = it },
                    valueRange = 0f..Protocol.STRENGTH_MAX.toFloat(),
                    steps = Protocol.STRENGTH_MAX - 1,
                )
            }

            RingtoneKind.PATTERN -> {
                val list = store.loadAll()
                if (list.isEmpty()) {
                    Text(
                        "还没有保存的自定义波形。请先在上面写一个并点「保存」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        list.forEach { p ->
                            FilterChip(
                                selected = patternName == p.name,
                                onClick = { patternName = p.name },
                                label = { Text(p.name) },
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (!ready) {
                        onNeedConnection()
                    } else {
                        AlarmScheduler.schedule(
                            context = context,
                            settings = AlarmScheduler.Settings(
                                enabled = enabled,
                                hour = hour,
                                minute = minute,
                                spec = RingtoneSpec(
                                    kind = ringtoneKind,
                                    modeCmd = modeCmd,
                                    patternName = patternName,
                                    strength = alarmStrength.toInt(),
                                ),
                            ),
                        )
                        status = if (enabled) {
                            "已设定 %02d:%02d 的闹钟".format(hour, minute)
                        } else {
                            "闹钟未启用（已保存设置）"
                        }
                    }
                },
            ) { Text("应用设置") }

            OutlinedButton(
                onClick = {
                    // 立即试听：不等到点，直接跑一遍当前铃声
                    scope.launch {
                        if (!ready) { onNeedConnection(); return@launch }
                        when (ringtoneKind) {
                            RingtoneKind.MODE -> {
                                ble.write(Protocol.frameMode(modeCmd))
                                ble.write(Protocol.frameStrength(alarmStrength.toInt()))
                            }
                            RingtoneKind.PATTERN -> {
                                store.load(patternName ?: "")?.let { player.play(it) }
                                    ?: run { status = "没有找到该波形"; return@launch }
                            }
                        }
                        status = "试听中..."
                    }
                },
                enabled = ringtoneKind == RingtoneKind.MODE || patternName != null,
            ) { Text("立即试听") }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "注意：闹钟需要通知权限，且建议保持 App 在后台不被系统清理。" +
                "若到点时连接已断开，会自动尝试重连，失败则发通知提示。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
