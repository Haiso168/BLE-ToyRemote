package com.ycm.remote.protocol

/**
 * 自定义波形的文本序列化格式。
 *
 * 设计目标：**人类可读、可手改、易分享**。所以不用 JSON，用带头部元信息的纯文本：
 *
 * ```
 * #YCM-PATTERN v1
 * name=我的波形
 * loop=false
 * repeat=1
 * ---
 * # 下面是 力度 持续毫秒
 * 40 400
 * 100 1500
 * 0 200
 * ```
 *
 * 分隔符 `---` 之前是元信息，之后是步骤（与 [Pattern.parse] 的脚本语法一致）。
 * 没有 `#YCM-PATTERN` 头部的纯脚本文本也能被 [decode] 接受，
 * 这样用户直接粘贴一段脚本也能导入。
 */
object PatternCodec {

    const val MAGIC = "#YCM-PATTERN"
    const val VERSION = 1
    const val SEPARATOR = "---"

    private const val KEY_NAME = "name"
    private const val KEY_LOOP = "loop"
    private const val KEY_REPEAT = "repeat"

    // ------------------------------------------------------------------ 编码

    fun encode(pattern: Pattern): String {
        val sb = StringBuilder()
        sb.append(MAGIC).append(' ').append('v').append(VERSION).append('\n')
        sb.append(KEY_NAME).append('=').append(escapeName(pattern.name)).append('\n')
        sb.append(KEY_LOOP).append('=').append(pattern.loop).append('\n')
        sb.append(KEY_REPEAT).append('=').append(pattern.repeat).append('\n')
        sb.append(SEPARATOR).append('\n')
        sb.append(pattern.toScript())
        return sb.toString()
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 解析文本。返回 [Result]，失败时带可读错误信息。
     *
     * 兼容三种输入：
     *   1. 完整格式（带 `#YCM-PATTERN` 头）
     *   2. 只有元信息没有 magic
     *   3. 纯脚本文本（无元信息）
     */
    fun decode(text: String): Result<Pattern> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var name: String? = null
        var loop = false
        var repeat = 1
        var bodyStart = 0
        var hasHeader = false

        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                // 头部之前的空行跳过
                if (!hasHeader && name == null) {
                    bodyStart = index + 1
                    continue
                }
                continue
            }
            if (line.startsWith(MAGIC)) {
                hasHeader = true
                bodyStart = index + 1
                continue
            }
            if (line == SEPARATOR) {
                bodyStart = index + 1
                break
            }
            // 元信息行：key=value
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    KEY_NAME -> name = unescapeName(value)
                    KEY_LOOP -> loop = value.equals("true", ignoreCase = true)
                    KEY_REPEAT -> repeat = value.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    else -> { /* 未知键忽略，向后兼容 */ }
                }
                bodyStart = index + 1
                continue
            }
            // 第一个既不是元信息也不是分隔符的行 —— 认定正文从这里开始
            bodyStart = index
            break
        }

        val body = lines.drop(bodyStart).joinToString("\n")
        if (body.isBlank()) {
            return Result.failure(IllegalArgumentException("文件里没有波形步骤"))
        }

        val finalName = name?.takeIf { it.isNotBlank() } ?: "导入的波形"
        return Pattern.parse(
            text = body,
            name = finalName,
            loop = loop,
            repeat = repeat,
        )
    }

    // ------------------------------------------------------------------ 文件名

    /** 生成一个安全的文件名（去掉文件系统不允许的字符）。 */
    fun safeFileName(name: String, ext: String = "ycmpat"): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .ifBlank { "pattern" }
            .take(60)
        return "$cleaned.$ext"
    }

    // ------------------------------------------------------------------ 内部

    /** 名称里的换行会破坏 key=value 结构，转义掉。 */
    private fun escapeName(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")

    private fun unescapeName(s: String): String =
        s.replace("\\n", "\n").replace("\\\\", "\\")
}

/**
 * 把波形还原成脚本文本（`力度 持续毫秒` 每行一步）。
 *
 * 放在这里而不是 [Pattern] 里，是为了让数据类保持纯粹、不掺入序列化细节。
 */
fun Pattern.toScript(): String =
    steps.joinToString("\n") { step ->
        "${step.strength} ${step.durationMs}"
    }
