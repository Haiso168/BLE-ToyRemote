package com.ycm.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * YCM-BL001 的 BLE 通信层。
 *
 * 关键点（均来自真机实测）：
 *  - Service 0xAE3A / 写 0xAE3B(WRITE NO RESPONSE) / 通知 0xAE3C(NOTIFY)
 *  - AE3C 不支持 Read
 *  - 连接后**不需要**订阅通知，**不需要**发开机帧，直接写 AE3B 即生效
 *  - 订阅 AE3C 会让玩具震一下
 *  - Android 无需系统配对即可建立 GATT 连接（这点和 Windows 不同）
 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000AE3A-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000AE3B-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000AE3C-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        const val DEVICE_NAME = "YCM-BL001"

        /** 两次写入之间的最小间隔。BLE 写入本身有开销，间隔过小会堆积。 */
        const val MIN_WRITE_INTERVAL_MS = 20L
    }

    enum class State { IDLE, SCANNING, CONNECTING, READY, DISCONNECTED, ERROR }

    data class FoundDevice(val address: String, val name: String?, val rssi: Int)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var lastWriteAt = 0L

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    /** AE3C 收到的回包（格式尚未解析，先原样记录） */
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scanCallback: ScanCallback? = null

    // ------------------------------------------------------------------ 权限

    fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun isBluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ------------------------------------------------------------------ 日志

    private fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        _log.value = (_log.value + "[$ts] $msg").takeLast(400)
    }

    fun clearLog() { _log.value = emptyList() }

    fun clearNotifications() { _notifications.value = emptyList() }

    // ------------------------------------------------------------------ 扫描

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            _error.value = "缺少蓝牙权限，请先授权"
            return
        }
        val bluetoothAdapter = adapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _error.value = "蓝牙未开启"
            _state.value = State.ERROR
            return
        }

        stopScan()
        _found.value = emptyList()
        _error.value = null
        _state.value = State.SCANNING
        addLog("开始扫描 $DEVICE_NAME ...")

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            _error.value = "无法获取 BLE 扫描器"
            _state.value = State.ERROR
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try {
                    result.device.name ?: result.scanRecord?.deviceName
                } catch (e: SecurityException) {
                    null
                }
                val addr = result.device.address
                val device = FoundDevice(addr, name, result.rssi)

                val current = _found.value
                val existing = current.indexOfFirst { it.address == addr }
                _found.value = if (existing >= 0) {
                    current.toMutableList().also { it[existing] = device }
                } else {
                    current + device
                }

                if (name == DEVICE_NAME) {
                    addLog("找到目标: $name [$addr] rssi=${result.rssi}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _error.value = "扫描失败，错误码 $errorCode"
                _state.value = State.ERROR
                addLog("扫描失败: $errorCode")
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { cb ->
            try {
                adapter()?.bluetoothLeScanner?.stopScan(cb)
            } catch (e: SecurityException) {
                // 权限在扫描途中被撤销，忽略
            }
        }
        scanCallback = null
        if (_state.value == State.SCANNING) _state.value = State.IDLE
    }

    // ------------------------------------------------------------------ 连接

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermissions()) {
            _error.value = "缺少蓝牙权限，请先授权"
            return
        }
        val bluetoothAdapter = adapter() ?: run {
            _error.value = "蓝牙不可用"
            return
        }
        stopScan()
        disconnect(quiet = true)
        _error.value = null
        _state.value = State.CONNECTING
        addLog("正在连接 $address ...")

        val device: BluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _error.value = "地址非法: $address"
            _state.value = State.ERROR
            return
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    addLog("已连接 (status=$status)，发现服务中 ...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("连接断开 (status=$status)")
                    _state.value = if (_error.value != null) State.ERROR else State.DISCONNECTED
                    closeQuietly(g)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("服务发现失败 status=$status")
                _error.value = "服务发现失败 status=$status"
                _state.value = State.ERROR
                return
            }

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                addLog("没有找到 Service ${SERVICE_UUID.toString().take(8)}")
                _error.value = "该设备没有 AE3A 服务，可能不是 YCM-BL001"
                _state.value = State.ERROR
                return
            }

            val wc = service.getCharacteristic(WRITE_UUID)
            if (wc == null) {
                addLog("没有找到写特征 AE3B")
                _error.value = "该设备没有写特征 AE3B"
                _state.value = State.ERROR
                return
            }
            writeChar = wc

            addLog("写特征就绪 AE3B  属性=" + describeProperties(wc.properties))
            _state.value = State.READY

            // 通知是可选的：实测不订阅也能控制，订阅会让玩具震一下。
            // 这里默认订阅，便于观察回包（格式尚未解析）。
            enableNotifications(g, service.getCharacteristic(NOTIFY_UUID))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("写入失败 status=$status")
                _error.value = "写入失败 status=$status"
            }
        }

        /** Android 13 (API 33) 以下走这个旧签名重载。 */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, char: BluetoothGattCharacteristic?) {
        if (char == null) {
            addLog("没有 AE3C 通知特征（不影响控制）")
            return
        }
        try {
            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            addLog("已订阅 AE3C 通知")
        } catch (e: SecurityException) {
            addLog("订阅通知失败: ${e.message}")
        }
    }

    private fun handleNotification(value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        _notifications.value = (_notifications.value + "[$ts] $hex").takeLast(200)
        addLog("NOTIFY <- $hex")
    }

    private fun describeProperties(props: Int): String {
        val list = ArrayList<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NO_RESPONSE")
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return if (list.isEmpty()) "(无)" else list.joinToString("|")
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 写入一帧。返回是否成功。
     * 内置最小间隔保护，避免高频调用把 BLE 栈压垮。
     */
    @SuppressLint("MissingPermission")
    suspend fun write(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || _state.value != State.READY) {
            addLog("未就绪，丢弃帧 ${frame.joinToString(" ") { "%02X".format(it) }}")
            return@withContext false
        }

        // 最小间隔保护
        val now = System.currentTimeMillis()
        val wait = MIN_WRITE_INTERVAL_MS - (now - lastWriteAt)
        if (wait > 0) delay(wait)
        lastWriteAt = System.currentTimeMillis()

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            ch.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }

        if (!ok) addLog("writeCharacteristic 返回失败: ${frame.joinToString(" ") { "%02X".format(it) }}")
        ok
    }

    /** 带重试的写入（偶发一次失败时有用）。 */
    suspend fun writeWithRetry(frame: ByteArray, attempts: Int = 3): Boolean {
        repeat(attempts) { i ->
            if (write(frame)) return true
            delay(60L * (i + 1))
        }
        return false
    }

    // ------------------------------------------------------------------ 断开

    @SuppressLint("MissingPermission")
    fun disconnect(quiet: Boolean = false) {
        if (!quiet) addLog("主动断开")
        gatt?.let { closeQuietly(it) }
        gatt = null
        writeChar = null
        if (!quiet) _state.value = State.IDLE
    }

    @SuppressLint("MissingPermission")
    private fun closeQuietly(g: BluetoothGatt) {
        try {
            g.disconnect()
        } catch (e: Exception) {
            // 忽略
        }
        try {
            g.close()
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 等待进入 READY（用于"连接后立刻发指令"的场景）。 */
    suspend fun awaitReady(timeoutMs: Long = 8000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (_state.value != State.READY) {
                if (_state.value == State.ERROR || _state.value == State.DISCONNECTED) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } ?: false

    fun setError(msg: String?) { _error.value = msg }
}
