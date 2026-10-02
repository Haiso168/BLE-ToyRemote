package com.ycm.remote.protocol

/**
 * YCM-BL001 协议实现。
 *
 * 帧格式（4 或 5 字节）：
 *
 *     偏移  长度  含义
 *      0     1   帧头，固定 0xAA
 *      1     1   group 命令组：0x01 = 模式/启停，0x02 = 力度
 *      2     1   cmd 命令字
 *      3     1   param（可选，仅 5 字节帧有）
 *      末位   1   checksum = 前面所有字节之和 & 0xFF
 *
 * 依据：玩具官方小程序 utils/deviceUtils.js 的 sendData(e=170, t, a, p)
 *      原始实现是 l = e+t+a+(p||0) 再 setUint8 取低字节，与 sum & 0xFF 等价。
 *      已用 22 组样本与原始 JS 逻辑双向验证。
 */
object Protocol {

    const val HEAD: Int = 0xAA

    const val GROUP_MODE: Int = 0x01
    const val GROUP_SHAKE: Int = 0x02

    const val CMD_PAUSE: Int = 0x00
    const val CMD_START: Int = 0x01
    const val CMD_SHAKE: Int = 0x12   // 18，力度通道

    /** 模式名 → 命令字。数值即小程序 CMDS 表中的值。 */
    val MODES: List<Mode> = listOf(
        Mode("微风", 0x02),
        Mode("心跳", 0x03),
        Mode("冲击", 0x04),
        Mode("拍打", 0x05),
        Mode("深度", 0x06),
        Mode("宇宙", 0x07),
        Mode("海啸", 0x08),
        Mode("瀑布", 0x09),
        Mode("火山", 0x10),
        Mode("神风", 0x11),
    )

    /**
     * 力度范围：**真机实测为 0-100**。
     *
     * 官方小程序只暴露 35-100（低于 34 它当作"停止"处理），
     * 但实测固件本身就接受 0-100，所以本 App 直接铺满整个范围。
     */
    const val STRENGTH_MIN: Int = 0
    const val STRENGTH_MAX: Int = 100

    /** 协议层面 param 是单字节，理论可写到 255；超过 100 的部分固件如何处理未知。 */
    const val STRENGTH_PROTO_MAX: Int = 255

    data class Mode(val name: String, val cmd: Int)

    // ------------------------------------------------------------------ 编码

    /**
     * 构造一条下发帧。param 为 null 时输出 4 字节，否则 5 字节。
     */
    fun frame(group: Int, cmd: Int, param: Int? = null): ByteArray {
        val body = ArrayList<Int>(5)
        body.add(HEAD)
        body.add(group and 0xFF)
        body.add(cmd and 0xFF)
        if (param != null) body.add(param and 0xFF)

        var sum = 0
        for (b in body) sum += b
        body.add(sum and 0xFF)

        return ByteArray(body.size) { body[it].toByte() }
    }

    fun frameMode(cmd: Int): ByteArray = frame(GROUP_MODE, cmd)

    fun frameStart(): ByteArray = frame(GROUP_MODE, CMD_START)

    fun framePause(): ByteArray = frame(GROUP_MODE, CMD_PAUSE)

    /**
     * 力度/速度帧。
     * 实测有效范围 0-100；这里只做单字节保护，不强绑 UI 范围，
     * 以便波形脚本和命令探测仍能写 0..255。
     */
    fun frameStrength(value: Int): ByteArray =
        frame(GROUP_SHAKE, CMD_SHAKE, value.coerceIn(0, STRENGTH_PROTO_MAX))

    /** 心跳/开机帧：AA AA AA AA A8。实测可省略，保留备用。 */
    fun frameHeartbeat(): ByteArray = byteArrayOf(
        0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xA8.toByte()
    )

    // ------------------------------------------------------------------ 工具

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /** 校验一条帧是否合法（帧头 + 校验和）。 */
    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if ((bytes[0].toInt() and 0xFF) != HEAD) return false
        var sum = 0
        for (i in 0 until bytes.size - 1) sum += (bytes[i].toInt() and 0xFF)
        return (sum and 0xFF) == (bytes[bytes.size - 1].toInt() and 0xFF)
    }

    /**
     * 解析用户手输的 HEX 字符串，例如 "AA 01 02 AD" 或 "AA0102AD"。
     * 返回 null 表示格式不合法。
     */
    fun parseHex(input: String): ByteArray? {
        val clean = input.replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
