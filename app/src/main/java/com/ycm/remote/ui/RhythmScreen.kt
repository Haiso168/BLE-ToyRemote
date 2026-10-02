package com.ycm.remote.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ycm.remote.audio.AudioAnalyzer
import com.ycm.remote.audio.MicSource
import com.ycm.remote.audio.PlaybackCaptureSource
import com.ycm.remote.audio.RhythmEngine
import com.ycm.remote.ble.BleManager
import com.ycm.remote.ble.PatternPlayer
import com.ycm.remote.data.PatternStore
import com.ycm.remote.protocol.Protocol
import com.ycm.remote.util.FileTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 律动的三条技术路线 */
private enum class RhythmRoute(val label: String) {
    MIC("麦克风实时"),
    CAPTURE("系统内录"),
    OFFLINE("导入音频分析"),
}

@Composable
fun RhythmScreen(
    ble: BleManager,
    player: PatternPlayer,
    onNeedConnection: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { RhythmEngine(context) }
    val store = remember { PatternStore(context) }

    val state by ble.state.collectAsStateWithLifecycle()
    val ready = state == BleManager.State.READY

    val level by engine.level.collectAsStateWithLifecycle()
    val strength by engine.strength.collectAsStateWithLifecycle()
    val beatCount by engine.beatCount.collectAsStateWithLifecycle()
    val running by engine.running.collectAsStateWithLifecycle()
    val cfg by engine.config.collectAsStateWithLifecycle()

    var route by remember { mutableStateOf(RhythmRoute.MIC) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var captureProjection by remember { mutableStateOf<MediaProjection?>(null) }

    // 离线分析相关
    val pickAudio = FileTransfer.rememberAudioPicker()
    var analyzing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<AudioAnalyzer.Result?>(null) }
    var anaMin by remember { mutableIntStateOf(30) }
    var anaMax by remember { mutableIntStateOf(95) }
    var anaDecay by remember { mutableIntStateOf(120) }

    fun say(text: String, error: Boolean = false) {
        message = text
        isError = error
    }

    // 把引擎输出接给 BLE
    DisposableEffect(ble) {
        engine.onStrength = { value ->
            scope.launch {
                if (value <= 0) ble.write(Protocol.framePause())
                else ble.write(Protocol.frameStrength(value))
            }
        }
        onDispose {
            engine.onStrength = null
            engine.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.dispose() }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureProjection = mpm.getMediaProjection(result.resultCode, result.data!!)
            say("已授权内录，可以点开始")
        } else {
            say("未授权系统内录", error = true)
        }
    }

    fun startLive() {
        if (!ready) { onNeedConnection(); return }
        when (route) {
            RhythmRoute.MIC -> {
                engine.resetBeatCount()
                engine.start(MicSource())
                say("麦克风分析中")
            }
            RhythmRoute.CAPTURE -> {
                val proj = captureProjection
                if (proj == null) { say("请先点「授权内录」", error = true); return }
                engine.resetBeatCount()
                engine.start(PlaybackCaptureSource(context, proj))
                say("系统内录分析中")
            }
            RhythmRoute.OFFLINE -> Unit
        }
    }

    fun runOfflineAnalysis(uri: android.net.Uri, displayName: String) {
        analyzing = true
        say("正在解码并分析「$displayName」，请稍候…")
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    // MediaExtractor 只能读文件路径，先把 content URI 拷到缓存目录
                    val tmp = java.io.File(context.cacheDir, "analyze_input")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw IllegalStateException("无法读取所选文件")

                    val r = AudioAnalyzer.analyze(
                        context = context,
                        filePath = tmp.absolutePath,
                        name = displayName,
                        options = AudioAnalyzer.Options(
                            minStrength = anaMin,
                            maxStrength = anaMax,
                            decayMs = anaDecay,
                        ),
                    )
                    tmp.delete()
                    r
                }
            }
            analyzing = false
            result.fold(
                onSuccess = { r ->
                    lastResult = r
                    val savedName = if (store.exists(r.pattern.name)) {
                        store.uniqueName(r.pattern.name)
                    } else {
                        r.pattern.name
                    }
                    val finalPattern = r.pattern.copy(name = savedName)
                    if (store.save(finalPattern)) {
                        say(
                            "分析完成：BPM ≈ ${"%.0f".format(r.bpm)}，" +
                                "检出 ${r.beatCount} 个节拍，" +
                                "已保存为「$savedName」。可以点播放了。"
                        )
                    } else {
                        say("分析完成但保存失败", error = true)
                    }
                },
                onFailure = { say("分析失败：${it.message}", error = true) },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // ------------------------------------------------------------ 路线选择
        SectionCard(
            title = "选择技术路线",
            subtitle = "三条路线各有取舍，可随时切换",
            icon = Icons.Filled.MusicNote,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RhythmRoute.values().forEach { r ->
                    FilterChip(
                        selected = route == r,
                        onClick = { route = r; message = null },
                        label = { Text(r.label) },
                    )
                }
            }

            Text(
                when (route) {
                    RhythmRoute.MIC ->
                        "实时听环境声音。任何音源都能用（音箱、蓝牙音箱、现场），" +
                            "但受环境噪声影响，体感延迟约 100-200ms。"
                    RhythmRoute.CAPTURE ->
                        "系统内录（Android 10+）。信号最干净，但有两个硬限制：" +
                            "① 音源 App 必须允许被录制（主流音乐 App 多数据绝）；" +
                            "② 捕获不到发往蓝牙设备的音频，必须走本机扬声器或有线耳机。"
                    RhythmRoute.OFFLINE ->
                        "导入音频文件，手机本地分析出节拍并生成波形，然后按精确时序回放。" +
                            "**零延迟、节拍最准，效果最好**；缺点是只对导入过的音频有效。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ------------------------------------------------------------ 实时控制
        if (route != RhythmRoute.OFFLINE) {
            SectionCard(
                title = "律动控制",
                subtitle = if (running) "运行中" else "已停止",
                icon = Icons.Filled.PlayArrow,
                accent = if (running) MaterialTheme.colorScheme.primary else null,
            ) {
                if (route == RhythmRoute.CAPTURE && captureProjection == null) {
                    OutlinedButton(
                        onClick = {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                as MediaProjectionManager
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("授权内录（会弹出录屏确认框）") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { startLive() },
                        modifier = Modifier.weight(1f),
                        enabled = !running,
                    ) { Text("开始") }

                    Button(
                        onClick = { engine.stop(); say("已停止") },
                        modifier = Modifier.weight(1f),
                        enabled = running,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("停止") }
                }

                Text("输入响度", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { (level * 12f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledValue(
                        label = "输出力度",
                        value = strength.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    LabeledValue(label = "节拍数", value = beatCount.toString())
                }

                if (running && level < 0.001f && route == RhythmRoute.CAPTURE) {
                    Text(
                        "⚠ 一直读到静音——多半是音源 App 禁止了被录制。换麦克风路线试试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ------------------------------------------------------------ 离线分析
        if (route == RhythmRoute.OFFLINE) {
            SectionCard(
                title = "导入音频并分析",
                subtitle = "在本机完成，不需要电脑",
                icon = Icons.Filled.Save,
            ) {
                Text(
                    "流程：选一个音频文件 → 手机解码并检测节拍 → 自动存成波形 → 点「播放」。" +
                        "生成结果也会出现在「自定义」页，可以再编辑、导出或设为闹钟铃声。",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("节拍力度范围：$anaMin - $anaMax", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Slider(
                        value = anaMin.toFloat(),
                        onValueChange = { anaMin = it.toInt().coerceAtMost(anaMax - 1) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    Slider(
                        value = anaMax.toFloat(),
                        onValueChange = { anaMax = it.toInt().coerceAtLeast(anaMin + 1) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text("每次冲击持续：${anaDecay} ms", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = anaDecay.toFloat(),
                    onValueChange = { anaDecay = it.toInt() },
                    valueRange = 40f..400f,
                )
                Text(
                    "越大越拖，越小越干脆。建议先 120 试。",
                    style = MaterialTheme.typography.bodySmall,
                )

                Button(
                    onClick = {
                        pickAudio { picked ->
                            if (picked == null) {
                                say("未选择文件", error = true)
                            } else {
                                runOfflineAnalysis(picked.uri, picked.displayName)
                            }
                        }
                    },
                    enabled = !analyzing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("选择音频文件并分析") }

                if (analyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Text("  分析中，长音频可能需要十几秒", style = MaterialTheme.typography.bodySmall)
                    }
                }

                lastResult?.let { r ->
                    Text(
                        "上次结果",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "BPM ≈ ${"%.0f".format(r.bpm)}    " +
                            "节拍 ${r.beatCount} 个    " +
                            "原曲 ${r.durationMs / 1000} 秒    " +
                            "波形步数 ${r.pattern.steps.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (!ready) { onNeedConnection(); return@Button }
                                store.load(r.pattern.name)?.let { player.play(it) }
                                    ?: player.play(r.pattern)
                                say("开始播放「${r.pattern.name}」")
                            },
                            enabled = ready && !player.isPlaying,
                            modifier = Modifier.weight(1f),
                        ) { Text("播放") }
                        OutlinedButton(
                            onClick = { player.stop(); say("已停止") },
                            enabled = player.isPlaying,
                            modifier = Modifier.weight(1f),
                        ) { Text("停止") }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 参数
        if (route != RhythmRoute.OFFLINE) {
            SectionCard(
                title = "映射参数",
                subtitle = "决定声音如何变成力度",
                icon = Icons.Filled.Info,
            ) {
                Text("映射模式", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RhythmEngine.Mode.values().forEach { m ->
                        FilterChip(
                            selected = cfg.mode == m,
                            onClick = { engine.updateConfig { it.mode = m } },
                            label = { Text(m.label) },
                        )
                    }
                }
                Text(
                    when (cfg.mode) {
                        RhythmEngine.Mode.DIRECT ->
                            "直接跟随响度：反应快、连贯，但音乐持续发声时力度会一直偏高，体感偏糊。"
                        RhythmEngine.Mode.BEAT ->
                            "节拍增强：只在明显超过近期平均时给一次冲击，然后回落。更像跟着鼓点打，推荐。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("增益：${"%.1f".format(cfg.gain)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = cfg.gain,
                    onValueChange = { v -> engine.updateConfig { it.gain = v } },
                    valueRange = 1f..20f,
                )

                Text("噪声底：${"%.3f".format(cfg.floor)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = cfg.floor,
                    onValueChange = { v -> engine.updateConfig { it.floor = v } },
                    valueRange = 0f..0.1f,
                )

                if (cfg.mode == RhythmEngine.Mode.BEAT) {
                    Text(
                        "节拍灵敏度：${"%.2f".format(cfg.beatSensitivity)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = cfg.beatSensitivity,
                        onValueChange = { v -> engine.updateConfig { it.beatSensitivity = v } },
                        valueRange = 1.05f..3f,
                    )
                    Text("越小越容易触发。", style = MaterialTheme.typography.bodySmall)

                    Text(
                        "衰减速度：${cfg.decayPerSecond.toInt()} /秒",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = cfg.decayPerSecond,
                        onValueChange = { v -> engine.updateConfig { it.decayPerSecond = v } },
                        valueRange = 50f..800f,
                    )
                }

                Text("最小力度：${cfg.minStrength}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = cfg.minStrength.toFloat(),
                    onValueChange = { v -> engine.updateConfig { it.minStrength = v.toInt() } },
                    valueRange = 0f..Protocol.STRENGTH_MAX.toFloat(),
                    steps = Protocol.STRENGTH_MAX - 1,
                )

                Text("力度上限：${cfg.maxStrength}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = cfg.maxStrength.toFloat(),
                    onValueChange = { v -> engine.updateConfig { it.maxStrength = v.toInt() } },
                    valueRange = 10f..100f,
                    steps = 89,
                )
                Text(
                    "上限用于安全保护，避免一直满功率。建议先设低一点试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        message?.let {
            SectionCard(
                title = if (isError) "出错了" else "状态",
                icon = Icons.Filled.Info,
                accent = if (isError) MaterialTheme.colorScheme.error else null,
            ) {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
