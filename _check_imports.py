"""Kotlin import 完整性检查（静态分析，无法替代编译器，但能抓住最常见的低级错误）。

检查两类问题：
  1) 用到了某个符号，但没有对应 import，且该符号不在同文件/同包中定义
  2) 从 Material 图标库或 Compose 里用到但未声明的符号
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "app", "src", "main", "java")

# 需要检查的「必须显式 import」的符号 -> 期望 import
REQUIRED = {
    # Compose 运行时
    "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "mutableIntStateOf": "androidx.compose.runtime.mutableIntStateOf",
    "mutableFloatStateOf": "androidx.compose.runtime.mutableFloatStateOf",
    "remember": "androidx.compose.runtime.remember",
    "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope",
    "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "getValue": "androidx.compose.runtime.getValue",
    "setValue": "androidx.compose.runtime.setValue",
    "Composable": "androidx.compose.runtime.Composable",
    # 布局
    "Column": "androidx.compose.foundation.layout.Column",
    "Row": "androidx.compose.foundation.layout.Row",
    "Spacer": "androidx.compose.foundation.layout.Spacer",
    "Box": "androidx.compose.foundation.layout.Box",
    "FlowRow": "androidx.compose.foundation.layout.FlowRow",
    "Arrangement": "androidx.compose.foundation.layout.Arrangement",
    "ExperimentalLayoutApi": "androidx.compose.foundation.layout.ExperimentalLayoutApi",
    "ColumnScope": "androidx.compose.foundation.layout.ColumnScope",
    # Material3
    "Button": "androidx.compose.material3.Button",
    "OutlinedButton": "androidx.compose.material3.OutlinedButton",
    "TextButton": "androidx.compose.material3.TextButton",
    "Text": "androidx.compose.material3.Text",
    "Slider": "androidx.compose.material3.Slider",
    "Switch": "androidx.compose.material3.Switch",
    "FilterChip": "androidx.compose.material3.FilterChip",
    "Card": "androidx.compose.material3.Card",
    "CardDefaults": "androidx.compose.material3.CardDefaults",
    "Surface": "androidx.compose.material3.Surface",
    "Icon": "androidx.compose.material3.Icon",
    "MaterialTheme": "androidx.compose.material3.MaterialTheme",
    "OutlinedTextField": "androidx.compose.material3.OutlinedTextField",
    "Scaffold": "androidx.compose.material3.Scaffold",
    "TopAppBar": "androidx.compose.material3.TopAppBar",
    "NavigationBar": "androidx.compose.material3.NavigationBar",
    "NavigationBarItem": "androidx.compose.material3.NavigationBarItem",
    "ButtonDefaults": "androidx.compose.material3.ButtonDefaults",
    "ExperimentalMaterial3Api": "androidx.compose.material3.ExperimentalMaterial3Api",
    # 图标
    "Icons": "androidx.compose.material.icons.Icons",
    # UI 基础
    "Modifier": "androidx.compose.ui.Modifier",
    "Alignment": "androidx.compose.ui.Alignment",
    "FontFamily": "androidx.compose.ui.text.font.FontFamily",
    "FontWeight": "androidx.compose.ui.text.font.FontWeight",
    "ImageVector": "androidx.compose.ui.graphics.vector.ImageVector",
    "Color": "androidx.compose.ui.graphics.Color",
    "LocalContext": "androidx.compose.ui.platform.LocalContext",
    "RoundedCornerShape": "androidx.compose.foundation.shape.RoundedCornerShape",
    "background": "androidx.compose.foundation.background",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
    "items": "androidx.compose.foundation.lazy.items",
    # 其他
    "collectAsStateWithLifecycle": "androidx.lifecycle.compose.collectAsStateWithLifecycle",
    "launch": "kotlinx.coroutines.launch",
    "delay": "kotlinx.coroutines.delay",
    "abs": "kotlin.math.abs",
    "sqrt": "kotlin.math.sqrt",
    "SensorManager": "android.hardware.SensorManager",
    "SensorEvent": "android.hardware.SensorEvent",
    "SensorEventListener": "android.hardware.SensorEventListener",
    "Sensor": "android.hardware.Sensor",
    "Context": "android.content.Context",
    "BluetoothAdapter": "android.bluetooth.BluetoothAdapter",
    "Intent": "android.content.Intent",
    "Bundle": "android.os.Bundle",
    # 显式 dp 扩展
    "dp": "androidx.compose.ui.unit.dp",
    # ---- 律动 / 闹钟相关新增符号 ----
    "rememberLauncherForActivityResult":
        "androidx.activity.compose.rememberLauncherForActivityResult",
    "ActivityResultContracts": "androidx.activity.result.contract.ActivityResultContracts",
    "Activity": "android.app.Activity",
    "MediaProjectionManager": "android.media.projection.MediaProjectionManager",
    "MediaProjection": "android.media.projection.MediaProjection",
    "AudioRecord": "android.media.AudioRecord",
    "MediaRecorder": "android.media.MediaRecorder",
    "Process": "android.os.Process",
    "thread": "kotlin.concurrent.thread",
    "LinearProgressIndicator": "androidx.compose.material3.LinearProgressIndicator",
    "TimePickerDialog": "android.app.TimePickerDialog",
    "AlarmManager": "android.app.AlarmManager",
    "PendingIntent": "android.app.PendingIntent",
    "Calendar": "java.util.Calendar",
    "Service": "android.app.Service",
    "IBinder": "android.os.IBinder",
    "Notification": "android.app.Notification",
    "NotificationChannel": "android.app.NotificationChannel",
    "NotificationManager": "android.app.NotificationManager",
    "NotificationCompat": "androidx.core.app.NotificationCompat",
    "BroadcastReceiver": "android.content.BroadcastReceiver",
    "ContextCompat": "androidx.core.content.ContextCompat",
    "FileProvider": "androidx.core.content.FileProvider",
    "File": "java.io.File",
    "Uri": "android.net.Uri",
    "CoroutineScope": "kotlinx.coroutines.CoroutineScope",
    "Dispatchers": "kotlinx.coroutines.Dispatchers",
    "Job": "kotlinx.coroutines.Job",
    "SupervisorJob": "kotlinx.coroutines.SupervisorJob",
    "cancel": "kotlinx.coroutines.cancel",
    "isActive": "kotlinx.coroutines.isActive",
}

problems = 0
files = []
for dirpath, _, names in os.walk(SRC):
    for n in names:
        if n.endswith(".kt"):
            files.append(os.path.join(dirpath, n))

for path in sorted(files):
    text = open(path, encoding="utf-8").read()
    # 去掉注释，避免注释里的词造成误报
    # 注意顺序：先块注释（含 KDoc /** */），再去行注释，否则 KDoc 内容会被残留
    code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)
    # 去掉字符串字面量：日志文本里出现的 "Service" 之类不算代码引用
    code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', code)
    # 去掉包声明与 import 行本身
    body = "\n".join(
        ln for ln in code.splitlines()
        if not ln.strip().startswith("import ") and not ln.strip().startswith("package ")
    )
    name = os.path.basename(path)
    for sym, imp in REQUIRED.items():
        # 要求符号前面不是 '.'，否则是成员方法调用（例如 permissionLauncher.launch(...)）
        if not re.search(r"(?<![.\w])" + re.escape(sym) + r"\b", body):
            continue
        if f"import {imp}" in text:
            continue
        # 同文件内定义的类/函数/变量名不算
        if re.search(r"\b(class|object|fun|val|var|data class|enum class)\s+" + re.escape(sym) + r"\b", text):
            continue
        print(f"  {name}: 用到 {sym} 但缺少 import {imp}")
        problems += 1

print()
if problems == 0:
    print("import 完整性检查通过")
else:
    print(f"发现 {problems} 处 import 缺失")
sys.exit(0 if problems == 0 else 1)
