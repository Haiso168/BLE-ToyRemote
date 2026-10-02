package com.ycm.remote.protocol

/**
 * 自定义波形：一串「力度 + 持续时长」步骤。
 *
 * 这是官方小程序完全没有的能力——它只能发单个力度值，
 * 而我们可以按时间轴编排任意序列，做出爬升、脉冲、心跳、随机游走等效果。
 */
data class Pattern(
    val name: String,
    val steps: List<Step>,
    val loop: Boolean = false,
    /** 整套波形的重复次数，loop 为 true 时忽略 */
    val repeat: Int = 1,
) {
    /**
     * @param strength 0 表示暂停
     * @param durationMs 持续时长，毫秒
     */
    data class Step(val strength: Int, val durationMs: Long)

    /** 整套波形总时长（毫秒），不含写入开销 */
    val totalDurationMs: Long get() = steps.sumOf { it.durationMs } * if (loop) 1 else repeat

    companion object {

        /** 单步默认时长 */
        const val DEFAULT_DURATION_MS = 300L

        /**
         * 解析脚本文本。每行一步，格式：
         *
         *     力度 [持续毫秒]
         *
         * 规则：
         *   - `#` 开头或行尾 `#` 之后为注释
         *   - 空行忽略
         *   - 省略时长时用 [DEFAULT_DURATION_MS]
         *   - 力度 0 表示暂停（会发出 AA 01 00 AB）
         *   - 力度范围 0..255
         *
         * 示例：
         * ```
         * # 缓慢爬升再回落
         * 40  400
         * 60  400
         * 80  600
         * 100 1500
         * 60  400
         * 0   200
         * ```
         */
        fun parse(text: String, name: String = "未命名", loop: Boolean = false, repeat: Int = 1):
            Result<Pattern> {
            val steps = ArrayList<Step>()

            text.lineSequence().forEachIndexed { index, rawLine ->
                val lineNo = index + 1
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed

                val parts = line.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                if (parts.isEmpty()) return@forEachIndexed

                val strength = parts[0].toIntOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException("第 $lineNo 行：力度「${parts[0]}」不是整数")
                    )

                val duration = if (parts.size >= 2) {
                    parts[1].toLongOrNull()
                        ?: return Result.failure(
                            IllegalArgumentException("第 $lineNo 行：时长「${parts[1]}」不是整数")
                        )
                } else {
                    DEFAULT_DURATION_MS
                }

                if (strength !in Protocol.STRENGTH_MIN_PROTO..Protocol.STRENGTH_MAX_PROTO) {
                    return Result.failure(
                        IllegalArgumentException(
                            "第 $lineNo 行：力度 $strength 超出 " +
                                "${Protocol.STRENGTH_MIN_PROTO}..${Protocol.STRENGTH_MAX_PROTO}"
                        )
                    )
                }
                if (duration < 0) {
                    return Result.failure(
                        IllegalArgumentException("第 $lineNo 行：时长不能为负")
                    )
                }
                if (duration > 600_000) {
                    return Result.failure(
                        IllegalArgumentException("第 $lineNo 行：单步时长过大（上限 600000ms）")
                    )
                }

                steps.add(Step(strength, duration))
            }

            if (steps.isEmpty()) {
                return Result.failure(IllegalArgumentException("脚本为空，至少需要一步"))
            }
            return Result.success(Pattern(name, steps, loop, repeat.coerceAtLeast(1)))
        }
    }
}

/**
 * 内置示例波形，供 UI 一键填入。
 */
object BuiltinPatterns {

    val ramp = """
        # 缓慢爬升到顶再回落
        40  400
        55  400
        70  500
        85  600
        100 1500
        70  400
        50  300
        0   200
    """.trimIndent()

    val pulse = """
        # 脉冲：强 120ms / 停 180ms，配合循环
        100 120
        0   180
    """.trimIndent()

    val heartbeat = """
        # 心跳：一下重、一下轻，中间留长间隔
        100 120
        0   200
        70  100
        0   900
    """.trimIndent()

    val stairs = """
        # 阶梯遍历：每档停 2.5 秒
        40  2500
        60  2500
        80  2500
        100 2500
        0   300
    """.trimIndent()

    val wave = """
        # 呼吸：慢升慢降，配合循环
        35  800
        50  800
        70  800
        90  800
        100 1000
        90  800
        70  800
        50  800
        35  800
        0   400
    """.trimIndent()

    val all: List<Pair<String, String>> = listOf(
        "爬升" to ramp,
        "脉冲" to pulse,
        "心跳" to heartbeat,
        "阶梯" to stairs,
        "呼吸" to wave,
    )
}
