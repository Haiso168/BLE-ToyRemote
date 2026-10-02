package com.ycm.remote.data

/** 闹钟"铃声"的类型 */
enum class RingtoneKind {
    /** 预设模式：发对应模式帧 + 一个力度值 */
    MODE,

    /** 自定义波形：播放本地保存的波形 */
    PATTERN,
}

/**
 * 闹钟铃声规格。会序列化到 SharedPreferences，
 * 所以字段都是原始类型，避免解析歧义。
 */
data class RingtoneSpec(
    val kind: RingtoneKind = RingtoneKind.MODE,
    /** kind=MODE 时使用 */
    val modeCmd: Int = 0x08,
    /** kind=PATTERN 时使用 */
    val patternName: String? = null,
    /** kind=MODE 时的力度 0-100 */
    val strength: Int = 80,
) {
    fun encode(): String = listOf(
        kind.name,
        modeCmd.toString(),
        patternName ?: "",
        strength.toString(),
    ).joinToString("|")

    companion object {
        fun decode(s: String?): RingtoneSpec {
            if (s.isNullOrBlank()) return RingtoneSpec()
            val parts = s.split("|")
            return RingtoneSpec(
                kind = runCatching { RingtoneKind.valueOf(parts.getOrNull(0) ?: "MODE") }
                    .getOrDefault(RingtoneKind.MODE),
                modeCmd = parts.getOrNull(1)?.toIntOrNull() ?: 0x08,
                patternName = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                strength = parts.getOrNull(3)?.toIntOrNull() ?: 80,
            )
        }
    }
}
