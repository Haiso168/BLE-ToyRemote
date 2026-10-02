package com.ycm.remote.data

import android.content.Context
import com.ycm.remote.protocol.Pattern
import com.ycm.remote.protocol.PatternCodec

/**
 * 自定义波形的本地存储 + 导入导出。
 *
 * 存储实现：SharedPreferences 里的一个 StringSet，
 * 每个元素就是 [PatternCodec] 的完整文本（自带名字），所以：
 *   - 存储格式和导出格式**完全一致**，不会出现"存进去能读、导出来读不了"
 *   - 出问题时可以直接看、直接改
 *
 * 数据量很小（一条波形几十个数字），不需要数据库。
 */
class PatternStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ycm_patterns"
        private const val KEY_PATTERNS = "patterns"
    }

    /**
     * 读取全部已保存的波形，按名称排序。
     * 解析失败的单条会被跳过（而不是让整个列表崩掉）。
     */
    fun loadAll(): List<Pattern> {
        val raw = prefs.getStringSet(KEY_PATTERNS, emptySet()) ?: emptySet()
        return raw
            .mapNotNull { text -> PatternCodec.decode(text).getOrNull() }
            .sortedBy { it.name }
    }

    /** 按名称读取单条。 */
    fun load(name: String): Pattern? =
        loadAll().firstOrNull { it.name == name }

    /**
     * 保存（同名覆盖）。
     * 因为 SharedPreferences 的 StringSet 是按字符串去重的，
     * 同名但内容不同的两条会同时存在——所以这里做"按名字替换"。
     */
    fun save(pattern: Pattern): Boolean {
        val others = rawTexts().filter { text ->
            val decodedName = PatternCodec.decode(text).getOrNull()?.name
            decodedName != pattern.name
        }
        return prefs.edit()
            .putStringSet(KEY_PATTERNS, (others + PatternCodec.encode(pattern)).toSet())
            .commit()
    }

    /** 删除指定名称的波形。 */
    fun delete(name: String): Boolean {
        val kept = rawTexts().filter { text ->
            PatternCodec.decode(text).getOrNull()?.name != name
        }
        return prefs.edit().putStringSet(KEY_PATTERNS, kept.toSet()).commit()
    }

    fun exists(name: String): Boolean = loadAll().any { it.name == name }

    /**
     * 导入：接受导出文件的内容或任意符合格式的文本。
     * @param rename 当名字已存在时，是否自动改名避免覆盖
     */
    fun import(text: String, rename: Boolean = true): Result<Pattern> {
        val decoded = PatternCodec.decode(text)
        val pattern = decoded.getOrElse { return Result.failure(it) }

        val finalPattern = if (rename && exists(pattern.name)) {
            pattern.copy(name = uniqueName(pattern.name))
        } else {
            pattern
        }

        return if (save(finalPattern)) Result.success(finalPattern)
        else Result.failure(IllegalStateException("写入本地存储失败"))
    }

    /** 导出为文本（就是文件内容）。 */
    fun export(pattern: Pattern): String = PatternCodec.encode(pattern)

    /** 生成一个不与现有名字冲突的名称，如 "爬升 (2)"。 */
    fun uniqueName(base: String): String {
        if (!exists(base)) return base
        var i = 2
        while (exists("$base ($i)")) i++
        return "$base ($i)"
    }

    private fun rawTexts(): List<String> =
        (prefs.getStringSet(KEY_PATTERNS, emptySet()) ?: emptySet()).toList()
}
