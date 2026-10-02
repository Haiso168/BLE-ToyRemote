package com.ycm.remote.util

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文本文件的分享与选择。
 *
 * 用系统 SAF（Storage Access Framework）而不是自己申请存储权限：
 *   - 导出：写 cacheDir 后经 FileProvider 分享，用户自己决定发给谁 / 存哪里
 *   - 导入：用 OpenDocument 让用户选文件，**不需要任何存储权限**
 */
object FileTransfer {

    private const val MIME_TEXT = "text/plain"

    /** 分享文本内容。依赖 Manifest 里声明的 FileProvider。 */
    fun shareText(context: Context, fileName: String, content: String) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "导出波形").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** 读取用户选中的文本文件。失败返回 null。 */
    fun readText(context: Context, uri: android.net.Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Compose 用的文件选择器。
     *
     * 返回一个 `(onPicked: (String?) -> Unit) -> Unit` 函数：
     * 调用它就弹出选择器，选完在回调里拿到文件文本（取消/失败为 null）。
     */
    @Composable
    fun rememberTextPicker(): ((String?) -> Unit) -> Unit {
        val context = LocalContext.current
        // 保存"本次选择完成后要回调谁"
        val pending = remember { arrayOfNulls<(String?) -> Unit>(1) }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val cb = pending[0]
            pending[0] = null
            if (cb == null) return@rememberLauncherForActivityResult
            cb(if (uri == null) null else readText(context, uri))
        }

        return remember(launcher) {
            { onPicked: (String?) -> Unit ->
                pending[0] = onPicked
                launcher.launch(
                    arrayOf("text/plain", "application/octet-stream", "*/*")
                )
            }
        }
    }

    /** 选中的音频文件 */
    data class PickedAudio(val uri: android.net.Uri, val displayName: String)

    /** 从 content URI 取显示名，失败给兜底名。 */
    fun queryDisplayName(context: Context, uri: android.net.Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Exception) {
            // 忽略，用兜底名
        }
        return (name ?: "导入的音频").substringBeforeLast('.')
    }

    /**
     * 音频文件选择器（用于「离线预分析」路线）。
     * 返回 `(onPicked: (PickedAudio?) -> Unit) -> Unit`。
     */
    @Composable
    fun rememberAudioPicker(): ((PickedAudio?) -> Unit) -> Unit {
        val context = LocalContext.current
        val pending = remember { arrayOfNulls<(PickedAudio?) -> Unit>(1) }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val cb = pending[0]
            pending[0] = null
            if (cb == null) return@rememberLauncherForActivityResult
            cb(
                if (uri == null) null
                else PickedAudio(uri, queryDisplayName(context, uri))
            )
        }

        return remember(launcher) {
            { onPicked: (PickedAudio?) -> Unit ->
                pending[0] = onPicked
                launcher.launch(arrayOf("audio/*"))
            }
        }
    }
}
