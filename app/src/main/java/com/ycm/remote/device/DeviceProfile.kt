package com.ycm.remote.device

import java.util.UUID

/**
 * 设备档案：描述一台玩具的 GATT 结构和指令协议。
 *
 * 为什么要抽象这一层：现在要支持两类完全不同的玩具——
 *
 *   1. YCM-BL001（第一台）：Service AE3A / 写 AE3B / 帧带累加校验和
 *   2. LEGACY 系列（DSJM / QUXIN / ZAOQUYUN）：Service F00/FFFE/FFF0 / 写 FE02 / 无校验和
 *
 * 它们的 UUID、帧格式、力度范围全都不一样，必须由档案来隔离。
 */
interface DeviceProfile {

    /** 内部标识，用于持久化选择 */
    val id: String

    /** 展示名 */
    val displayName: String

    /** 广播名（可能为空，空表示只按 Service UUID 识别） */
    val advertisingNames: List<String>

    /** 用于自动识别设备的 Service UUID 列表 */
    val serviceUuids: List<UUID>

    /** 写特征 UUID */
    val writeUuid: UUID

    /** 通知特征 UUID（没有则为 null） */
    val notifyUuid: UUID?

    /** 力度最小值 */
    val strengthMin: Int

    /** 力度最大值 */
    val strengthMax: Int

    /** 停止指令 */
    fun stopCommand(): ByteArray

    /** 力度指令 */
    fun strengthCommand(value: Int): ByteArray

    /** 模式指令。列表为空表示该设备不支持模式切换。 */
    fun modeCommands(): List<ModeCommand>

    /** 模式下发的一条指令 + 展示名 */
    data class ModeCommand(val name: String, val bytes: ByteArray)
}

/**
 * YCM-BL001 —— 最早那台玩具。
 *
 * 帧格式：AA | group | cmd | [param] | checksum，checksum = 前 N-1 字节之和 & 0xFF
 * 力度 0-100；group=0x01 下 0x02~0x09/0x10/0x11 是 10 个模式。
 */
object YcmBl001Profile : DeviceProfile {

    private val SERVICE: UUID = UUID.fromString("0000AE3A-0000-1000-8000-00805F9B34FB")
    private val WRITE: UUID = UUID.fromString("0000AE3B-0000-1000-8000-00805F9B34FB")
    private val NOTIFY: UUID = UUID.fromString("0000AE3C-0000-1000-8000-00805F9B34FB")

    private const val HEAD = 0xAA
    private const val GROUP_MODE = 0x01
    private const val GROUP_SHAKE = 0x02
    private const val CMD_PAUSE = 0x00
    private const val CMD_SHAKE = 0x12

    override val id = "ycm-bl001"
    override val displayName = "YCM-BL001"
    override val advertisingNames = listOf("YCM-BL001")
    override val serviceUuids = listOf(SERVICE)
    override val writeUuid = WRITE
    override val notifyUuid = NOTIFY
    override val strengthMin = 0
    override val strengthMax = 100

    private fun frame(group: Int, cmd: Int, param: Int? = null): ByteArray {
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

    override fun stopCommand(): ByteArray = frame(GROUP_MODE, CMD_PAUSE)

    override fun strengthCommand(value: Int): ByteArray =
        frame(GROUP_SHAKE, CMD_SHAKE, value.coerceIn(0, 255))

    override fun modeCommands(): List<DeviceProfile.ModeCommand> = listOf(
        "微风" to 0x02, "心跳" to 0x03, "冲击" to 0x04, "拍打" to 0x05,
        "深度" to 0x06, "宇宙" to 0x07, "海啸" to 0x08, "瀑布" to 0x09,
        "火山" to 0x10, "神风" to 0x11,
    ).map { (name, cmd) ->
        DeviceProfile.ModeCommand(name, frame(GROUP_MODE, cmd))
    }
}

/**
 * LEGACY 协议 —— 覆盖 DSJM / QUXIN / ZAOQUYUN 三个品牌。
 *
 * 指令格式（实测 QUXIN 验证通过）：
 *   停止     03 11 00
 *   模式 n   03 11 0n       (n = 1..10)
 *   强度 n   03 01 01 0n    (n = 0..10)
 *
 * ZAOQUYUN 用同一套语义，但编码不同：
 *   首字节 3 -> 6，次字节 17(0x11) -> 22(0x16)
 * 也就是 03 11 0n -> 06 16 0n，03 01 01 0n -> 06 01 01 0n
 *
 * 注意：本协议**没有校验和**，字节直发。
 */
class LegacyProfile(
    override val id: String,
    override val displayName: String,
    override val advertisingNames: List<String>,
    serviceUuid: String,
    /** true = zaoquyun 变体（首字节 6、次字节 22） */
    private val zaoquyunVariant: Boolean,
    private val modeNames: List<String> = ADULT_MODE_NAMES,
) : DeviceProfile {

    private val SERVICE: UUID = UUID.fromString(serviceUuid)
    private val WRITE: UUID = UUID.fromString("0000FE02-0000-1000-8000-00805F9B34FB")

    override val serviceUuids = listOf(SERVICE)
    override val writeUuid = WRITE
    override val notifyUuid: UUID? = null
    override val strengthMin = 0
    override val strengthMax = 10

    /**
     * 帧构造 + 协议变体转换。
     *
     * 与官方小程序 commandForDevice 的逻辑一致：
     *   1. 首字节 3 与 6 互换     （legacy=3 / zaoquyun=6）
     *   2. 次字节 17 与 22 互换   （legacy=0x11 / zaoquyun=0x16）
     *   3. 次字节为 1 且长度 >=4 时，第 3 字节强制为 1
     */
    private fun build(legacyBytes: ByteArray): ByteArray {
        if (!zaoquyunVariant) return legacyBytes
        val out = legacyBytes.copyOf()
        if (out.size < 2) return out
        if (out[0].toInt() == 3) out[0] = 6
        else if (out[0].toInt() == 6) out[0] = 3
        if (out[1].toInt() == 17) out[1] = 22
        else if (out[1].toInt() == 22) out[1] = 17
        if (out[1].toInt() == 1 && out.size >= 4) out[2] = 1
        return out
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    override fun stopCommand(): ByteArray = build(bytes(0x03, 0x11, 0x00))

    override fun strengthCommand(value: Int): ByteArray =
        build(bytes(0x03, 0x01, 0x01, value.coerceIn(0, 10)))

    override fun modeCommands(): List<DeviceProfile.ModeCommand> =
        modeNames.mapIndexed { index, name ->
            // 模式 n 从 1 开始
            DeviceProfile.ModeCommand(name, build(bytes(0x03, 0x11, index + 1)))
        }

    companion object {
        /** 用户提供的 10 个模式名（来自官方小程序界面） */
        val ADULT_MODE_NAMES = listOf(
            "初逗情欲", "徐徐温情", "波涛汹涌", "连绵起伏", "循序渐进",
            "出其不意", "撩情抚慰", "乱斗序曲", "灵肉交融", "欢呼雀跃",
        )
    }
}

/** 所有已知设备档案 */
object DeviceRegistry {

    val DSJM = LegacyProfile(
        id = "dsjm",
        displayName = "DSJM",
        advertisingNames = listOf("DSJM"),
        serviceUuid = "00000F00-0000-1000-8000-00805F9B34FB",
        zaoquyunVariant = false,
    )

    val QUXIN = LegacyProfile(
        id = "quxin",
        displayName = "QUXIN",
        advertisingNames = listOf("QUXIN"),
        serviceUuid = "0000FFFE-0000-1000-8000-00805F9B34FB",
        zaoquyunVariant = false,
    )

    val ZAOQUYUN = LegacyProfile(
        id = "zaoquyun",
        displayName = "ZAOQUYUN",
        advertisingNames = listOf("ZAOQUYUN"),
        serviceUuid = "0000FFF0-0000-1000-8000-00805F9B34FB",
        zaoquyunVariant = true,
    )

    val all: List<DeviceProfile> = listOf(YcmBl001Profile, DSJM, QUXIN, ZAOQUYUN)

    /** 所有可用于扫描过滤/自动识别的 Service UUID */
    val allServiceUuids: List<UUID> = all.flatMap { it.serviceUuids }

    /** 按广播名匹配档案 */
    fun byName(name: String?): DeviceProfile? {
        if (name.isNullOrBlank()) return null
        val upper = name.trim().uppercase()
        return all.firstOrNull { profile ->
            profile.advertisingNames.any { it.uppercase() == upper }
        }
    }

    /** 按已发现的 Service UUID 匹配档案 */
    fun byServiceUuid(uuid: UUID?): DeviceProfile? {
        if (uuid == null) return null
        return all.firstOrNull { profile ->
            profile.serviceUuids.any { it == uuid }
        }
    }

    fun byId(id: String): DeviceProfile? = all.firstOrNull { it.id == id }
}
