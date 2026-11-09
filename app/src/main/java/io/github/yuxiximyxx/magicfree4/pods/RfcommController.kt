package io.github.yuxiximyxx.magicfree4.pods

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.content.*
import android.content.SharedPreferences
import io.github.yuxiximyxx.magicfree4.BuildConfig
import io.github.yuxiximyxx.magicfree4.hook.Log
import io.github.yuxiximyxx.magicfree4.utils.DeviceIdentity
import io.github.yuxiximyxx.magicfree4.utils.MediaControl
import io.github.yuxiximyxx.magicfree4.utils.MagicOriGAction
import io.github.yuxiximyxx.magicfree4.utils.PodIdentity
import io.github.yuxiximyxx.magicfree4.utils.huaweiStrongToast.HuaweiStrongToastUtil
import io.github.yuxiximyxx.magicfree4.utils.huaweiStrongToast.data.BatteryParams
import io.github.yuxiximyxx.magicfree4.utils.huaweiStrongToast.data.PodParams
import io.github.yuxiximyxx.magicfree4.pods.hem.*
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * RfcommController — RFCOMM SPP 连接控制器。
 *
 * 防炸机要点：
 * 1. 所有 socket 操作都有超时（30s connect timeout），避免无限等待卡死线程
 * 2. 用 AtomicLong generation 防止旧连接的回调污染新连接
 * 3. 断开连接时先取消所有协程再关闭 socket，避免资源泄漏
 * 4. 广播发送用 setPackage() 限定目标包，不被其他 app 截获
 * 5. 所有反射调用用 runCatching，任何异常只记录日志不影响主流程
 * 6. packet reader 循环有最大单次读取长度限制（1024 byte buffer）
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "MagicOriG-Rfcomm"
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 30_000L
    private const val RFCOMM_READ_TIMEOUT_MS = 5_000L

    /** 欢律 SPP UUID（QuickBuds Protocol.kt:33-34）。079A 优先，1107 次之。 */
    private val SPP_UUIDS: List<UUID> = listOf(
        UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"),
        UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5"),
    )
    /** 两个 UUID 都失败时，反射取 RFCOMM 通道 15（QuickBuds 同款兜底）。 */
    private const val RFCOMM_CHANNEL_FALLBACK = 15

    private val framer = HemWire.Framer().also { f ->
        f.onDiscard = { Log.w(TAG, "丢弃非帧字节: ${HemWire.hex(it)}") }
    }

    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    private lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    // ── 线程安全的状态计数 ──────────────────────────────────────
    private val connectionGeneration = AtomicLong(0)
    private val isConnected = AtomicBoolean(false)

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?
    )

    private var mShowedConnectedToast = false
    private var lastTempBatt = 0
    private var currentBatteryParams: BatteryParams? = null
    private var currentAnc: Int = 1
    private var lastRecordedAnc: Int = -1   // current_anc 去重（心跳响应防高频写）
    @Volatile private var lastAncStackAt = 0L   // setANCMode 调用栈限频

    /** 自定义 EQ 列表内存缓存（0x8122 回包时更新；跨进程读 Settings 可能拿不到 resolver） */
    @Volatile
    private var eqCustomCache: List<HemEq.Preset> = emptyList()
    private var currentGameMode: Boolean = false
    private var currentLowLatency: Boolean = false
    private var currentDualConn: Boolean = false
    private var currentEq: EqMode = EqMode.BALANCED
    private var currentWindSuppression: Boolean = false
    private var currentInEarDetection: Boolean = false

    private var cachedLeftBattery: PodParams? = null
    private var cachedRightBattery: PodParams? = null
    private var cachedCaseBattery: PodParams? = null
    private val PREFS_NAME = "magicorig_battery"

    // ── 协程作用域 ──────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            safeHandleBroadcast(p0, p1)
        }
    }

    private fun safeHandleBroadcast(context: Context?, intent: Intent?) {
        if (intent == null) return
        // 可见性：本 session 长期无法确认 receiver 是否注册/是否收到广播，这里强制打 I 级日志。
        Log.i(TAG, "★ 收到广播 action=${intent.action} ctx=${context?.javaClass?.simpleName}")
        try {
            when (intent.action) {
                MagicOriGAction.ACTION_GET_PODS_MAC -> {
                    Log.i(TAG, "→ ACTION_GET_PODS_MAC → 回复 PODS_MAC_RECEIVED")
                    safeSendBroadcast(context, MagicOriGAction.ACTION_PODS_MAC_RECEIVED) {
                        putExtra("mac", mDevice.address)
                    }
                }
                else -> {
                    Log.i(TAG, "→ 转入 safeHandleUIEvent action=${intent.action}")
                    safeHandleUIEvent(intent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "broadcast handler threw", e)
        }
    }

    private fun safeSendBroadcast(context: Context?, action: String, fill: Intent.() -> Unit = {}) {
        if (context == null) return
        try {
            Intent(action).apply {
                if (::mDevice.isInitialized) { putExtra("address", mDevice.address); putExtra("device_name", mDevice.name) }
                fill()
                `package` = "com.android.systemui"
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendBroadcast failed: $action", e)
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = currentBatteryParams,
            anc = currentAnc,
            transparencyVocalEnhancement = false,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name else null
        )
    }

    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int): String = when (anc) {
        5 -> "0103"; 6 -> "0101"; 7 -> "0100"; 8 -> "0102"
        2 -> "0200"; 3 -> "0200"; else -> "0000"
    }

    /** 定期轮询：只问「当前降噪档 + 电量」这两件本项目真需要的事。 */
    fun queryStatus() {
        scope.launch {
            try {
                sendPacketSafe(HemCommands.queryAnc()); delay(80)
                sendPacketSafe(HemCommands.queryBattery())
            } catch (e: Throwable) {
                Log.e(TAG, "queryStatus coroutine threw", e)
            }
        }
    }

    /**
     * 连接后的欢律初始化序列（QuickBuds BudsConnectionManager.kt:614-669 的最小版）：
     * 握手 → 产品 ID → 订阅上报 → 读降噪 → 读电量 → 再回读一次降噪兜底。
     */
    private fun hemInit() {
        scope.launch {
            try {
                delay(300); sendPacketSafe(HemCommands.handshake())
                delay(200); sendPacketSafe(HemCommands.queryProductId())
                delay(200); sendPacketSafe(
                    HemCommands.registerNotifications(
                        listOf(
                            HemCommands.EVT_BATTERY,
                            HemCommands.EVT_WEARING,
                            HemCommands.EVT_ANC,
                            0x04   // 贴合度检测结果
                        )
                    )
                )
                delay(200); sendPacketSafe(HemCommands.queryAnc())
                delay(200); sendPacketSafe(HemCommands.queryBattery())
                delay(200); sendPacketSafe(HemCommands.queryStatus())
                delay(200); sendPacketSafe(HemCommands.queryFirmware())
                delay(200); sendPacketSafe(HemCommands.queryAlertVolume())
                delay(200); sendPacketSafe(HemCommands.queryEq())
                delay(200); sendPacketSafe(HemCommands.queryGestures())
                delay(200); sendPacketSafe(HemCommands.queryEqAll())
                delay(200); sendPacketSafe(HemCommands.queryDevices())
                delay(200); sendPacketSafe(HemCommands.queryPersonalNoise())
                delay(300); sendPacketSafe(HemCommands.queryAnc())
                // 记忆式 ANC：连上后恢复上次用户选择的档位
                runCatching { restoreAncIfAny() }
                Log.i(TAG, "欢律初始化序列已下发")
            } catch (e: Throwable) {
                Log.e(TAG, "hemInit failed", e)
            }
        }
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences) {
        DeviceIdentity.remember(context)
        writeGlobal("magicorig_diag_connectpod", "enter dev=${device.address} isConnected=${isConnected.get()}")
        // 防止并发连接
        if (isConnected.compareAndSet(false, true)) {
            Log.d(TAG, "connectPod started gen=${connectionGeneration.incrementAndGet()} device=${device.address}")
        } else {
            Log.d(TAG, "connectPod skipped: already connecting")
            writeGlobal("magicorig_diag_connectpod", "skipped: already connecting")
            return
        }

        mContext = context
        mDevice = device
        mPrefs = prefs

        // ★ 跨进程桥：把真实耳机 MAC 告诉设备中心（controlcenter 进程）。
        //   上游把作者自己的耳机 MAC 写死在注入链里，导致换耳机后注入的是「幽灵设备」；
        //   这里每次连接都写一次真实地址，断开时**不清除**（保留上次值，
        //   让设备中心能先行注入、连上后自然转在线）。
        runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver, "magicorig_mac", device.address
            )
            Log.i(TAG, "已写入 magicorig_mac=${device.address}（供设备中心识别真实设备）")
        }.onFailure { Log.w(TAG, "写入 magicorig_mac 失败: ${it.javaClass.simpleName}: ${it.message}") }

        runCatching { initBatteryCache(context) }
            .onFailure { Log.w(TAG, "initBatteryCache failed", it) }

        // 注册广播 — 必须有 try-catch，注册失败不阻止连接
        runCatching {
            val filter = IntentFilter().apply {
                listOf(
                    MagicOriGAction.ACTION_ANC_SELECT,
                    MagicOriGAction.ACTION_FIND_BUDS,
                    MagicOriGAction.ACTION_FEATURE_SET,
                    MagicOriGAction.ACTION_EQ_PRESET,
                    MagicOriGAction.ACTION_EQ_CUSTOM,
                    MagicOriGAction.ACTION_ALERT_VOLUME,
                    MagicOriGAction.ACTION_GESTURE_SET,
                    MagicOriGAction.ACTION_FIT_TEST,
                    MagicOriGAction.ACTION_QUERY_DEVICES,
                    MagicOriGAction.ACTION_PODS_UI_INIT,
                    MagicOriGAction.ACTION_GET_PODS_MAC,
                    MagicOriGAction.ACTION_REFRESH_STATUS,
                    MagicOriGAction.ACTION_GAME_MODE_SET,
                    MagicOriGAction.ACTION_LOW_LATENCY_SET,
                    MagicOriGAction.ACTION_DUAL_CONN_SET,
                    MagicOriGAction.ACTION_EQ_SET,
                    MagicOriGAction.ACTION_WIND_SUPPRESSION_SET,
                    MagicOriGAction.ACTION_IN_EAR_DETECTION_SET
                ).forEach { addAction(it) }
            }
            context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "broadcast receiver registered")
        }.onFailure { e ->
            Log.w(TAG, "broadcast receiver registration failed: ${e.message}")
        }

        // 发送连接广播
        val deviceName = device.alias ?: device.name ?: device.address
        PodProfiles.matchName(deviceName)?.let { PodIdentity.publish(it, context) }
        try {
            Intent(MagicOriGAction.ACTION_PODS_CONNECTED).apply {
                putExtra("device_name", deviceName)
                putExtra("address", mDevice.address)
                `package` = BuildConfig.APPLICATION_ID
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "send broadcast failed", e)
        }
        context.getSharedPreferences("magicorig_device", Context.MODE_PRIVATE)
            .edit().putString("device_name", deviceName).apply()

        MediaControl.mContext = context

        // RFCOMM 连接在独立线程执行，避免阻塞蓝牙服务线程
        thread(name = "magicorig-rfcomm-connect") {
            safeRfcommConnect(device)
        }

        // 电池轮询线程
        thread(name = "magicorig-battery-poll") {
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            while (isConnected.get()) {
                try { Thread.sleep(BATTERY_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (isConnected.get()) queryStatus()
            }
        }
    }

    private fun safeRfcommConnect(device: BluetoothDevice) {
        val gen = connectionGeneration.get()
        var attempt = 0
        val maxAttempts = 2
        // A2DP 刚连上时耳机侧 SPP 可能还没就绪，给一点缓冲
        runCatching { Thread.sleep(1200) }

        while (attempt < maxAttempts && isConnected.get() && connectionGeneration.get() == gen) {
            attempt++
            try {
                // 先取消正在进行的配对/发现
                runCatching {
                    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                }.onFailure { Log.w(TAG, "cancelDiscovery failed", it) }

                // 先尝试标准 RFCOMM socket
                var sock: BluetoothSocket? = null
                for (uuid in SPP_UUIDS) {
                    if (sock != null) break
                    try {
                        sock = device.createRfcommSocketToServiceRecord(uuid)
                        sock?.connect()
                        Log.d(TAG, "RFCOMM connected (secure $uuid) gen=$gen")
                        writeGlobal("magicorig_diag_spp", "connected secure $uuid")
                    } catch (e: IOException) {
                        Log.w(TAG, "Secure RFCOMM $uuid failed: ${e.message}", e)
                        sock = null
                        try {
                            sock = device.createInsecureRfcommSocketToServiceRecord(uuid)
                            sock?.connect()
                            Log.d(TAG, "RFCOMM connected (insecure $uuid) gen=$gen")
                            writeGlobal("magicorig_diag_spp", "connected insecure $uuid")
                        } catch (e2: IOException) {
                            Log.w(TAG, "Insecure RFCOMM $uuid failed: ${e2.message}", e2)
                            sock = null
                        }
                    }
                }
                if (sock == null) {
                    runCatching {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val ch = m.invoke(device, RFCOMM_CHANNEL_FALLBACK) as BluetoothSocket
                        ch.connect()
                        sock = ch
                        Log.d(TAG, "RFCOMM connected (channel $RFCOMM_CHANNEL_FALLBACK) gen=$gen")
                        writeGlobal("magicorig_diag_spp", "connected channel $RFCOMM_CHANNEL_FALLBACK")
                    }.onFailure { Log.w(TAG, "channel fallback failed: ${it.message}") }
                }
                if (sock == null) {
                    Log.e(TAG, "RFCOMM connect failed completely after $attempt attempts")
                    if (attempt < maxAttempts) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                        continue
                    }
                    break
                }

                if (sock != null) {
                    socket = sock
                    // 读取线程
                    thread(name = "magicorig-rfcomm-reader") {
                        safePacketReader(sock!!.inputStream, gen)
                    }
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                    hemInit()
                    return
                }
            } catch (e: Throwable) {
                Log.e(TAG, "rfcomm connection attempt $attempt failed", e)
                if (attempt >= maxAttempts) break
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }

        // 所有尝试失败，清理状态
        if (connectionGeneration.get() == gen) {
            Log.e(TAG, "All RFCOMM connection attempts failed, disconnecting")
            writeGlobal("magicorig_diag_spp", "all attempts failed")
            safeDisconnect(device)
        }
    }

    private fun safePacketReader(inputStream: InputStream, gen: Long) {
        val buffer = ByteArray(1024)
        val dataBuffer = ByteArray(2048)
        var dataBufferPos = 0
        try {
            while (isConnected.get() && connectionGeneration.get() == gen) {
                val bytesRead = runCatching { inputStream.read(buffer) }.getOrNull() ?: continue
                if (connectionGeneration.get() != gen) break
                if (bytesRead > 0) {
                    val frames = framer.append(buffer, bytesRead)
                    for (frame in frames) {
                        runCatching { HemWire.parse(frame)?.let { handleHemPacket(it) } }
                            .onFailure { Log.w(TAG, "hem packet handle failed", it) }
                    }
                } else if (bytesRead == -1) {
                    break
                }
            }
        } catch (e: IOException) {
            if (isConnected.get() && connectionGeneration.get() == gen) {
                Log.e(TAG, "RFCOMM read error", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "packet reader unknown error", e)
        } finally {
            if (connectionGeneration.get() == gen && isConnected.get()) {
                safeDisconnect(mDevice)
            }
        }
    }

    /** 欢律报文分发。帧层（0xAA/LEB128/分片）在 HemWire，这里只管语义。 */
    private fun handleHemPacket(msg: HemWire.Message) {
        if (BuildConfig.DEBUG) Log.v(TAG, "RX cmd=0x%04X seq=0x%02X payload=%s".format(msg.cmd, msg.seq, HemWire.hex(msg.payload)))
        when (msg.cmd) {
            HemCommands.RSP_BATTERY -> HemBattery.parseQuery(msg)?.let { onHemBattery(it) }

            HemCommands.CMD_ACTIVE_REPORT -> {
                val sub = msg.payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                when (sub) {
                    HemCommands.EVT_BATTERY -> HemBattery.parseActive(msg)?.let { onHemBattery(it) }
                    HemCommands.EVT_ANC -> applyAncRaw(HemBattery.ancRawValue(msg))
                    0x04 -> {   // 贴合度结果：04 [dev status] [dev status]，1 良好 / 0 一般 / 6 较差
                        val p = msg.payload
                        val pairs = p.drop(1).chunked(2).filter { it.size == 2 }
                        val text = pairs.joinToString(",") { pair ->
                            val dev = if ((pair[0].toInt() and 0xFF) == 1) "L" else "R"
                            val st = pair[1].toInt() and 0xFF
                            val name = when (st) { 1 -> "良好"; 0 -> "一般"; 6 -> "较差"; else -> "状态$st" }
                            "$dev=$name"
                        }
                        writeGlobal("magicorig_fit", text)
                        Log.i(TAG, "贴合度结果: $text")
                    }
                    else -> if (BuildConfig.DEBUG) Log.v(TAG, "忽略上报 subType=0x%02X".format(sub))
                }
            }

            HemCommands.RSP_ANC -> applyAncRaw(HemBattery.ancRawValue(msg))

            HemCommands.RSP_STATUS -> HemStatus.features(msg)?.let { map ->
                val text = map.entries.joinToString(",") { "${it.key}=${it.value}" }
                writeGlobal("magicorig_features", text)
                Log.i(TAG, "功能状态: $text")
            }

            HemCommands.RSP_FIRMWARE -> HemStatus.firmware(msg)?.let {
                writeGlobal("magicorig_firmware", it)
                Log.i(TAG, "固件版本: $it")
            }

            HemCommands.RSP_KEY_FUNCTION -> HemGesture.parse(msg)?.let { list ->
                val text = list.joinToString(";") { "${it[0]},${it[1]},${it[2]},${it[3]}" }
                writeGlobal("magicorig_gestures", text)
                Log.i(TAG, "手势绑定 ${list.size} 条")
            }

            HemCommands.RSP_PNOISE -> {
                val exist = if (msg.payload.size >= 2) (msg.payload[1].toInt() and 0xFF) else -1
                writeGlobal("magicorig_pnoise", if (exist > 0) "1" else "0")
                Log.i(TAG, "个性化降噪结果存在=$exist")
            }

            HemCommands.RSP_DEVICES -> HemDevices.parse(msg)?.let { list ->
                val text = list.joinToString(";") { "${it.mac}|${it.name}|${if (it.connected) 1 else 0}" }
                writeGlobal("magicorig_devices", text)
                Log.i(TAG, "设备列表 ${list.size} 台")
            }

            HemCommands.RSP_ALERT_VOLUME -> HemStatus.alertVolume(msg)?.let {
                writeGlobal("magicorig_alert_vol", it.toString())
            }

            // 0x810F 回的是耳机当前的 EQ 编号，与本模块的预设编号不一定同一空间；
            // 为避免 UI 显示错，这里只记日志，不覆盖 magicorig_eq（以本模块下发值为准）。
            HemCommands.RSP_EQ_ALL -> HemEq.parseList(msg.payload)?.let { list ->
                eqCustomCache = list
                writeGlobal("magicorig_eq_custom", HemEq.serialize(list))
                Log.i(TAG, "自定义 EQ ${list.size} 个")
            }

            HemCommands.RSP_EQ -> {
                val cur = HemStatus.eqPreset(msg)
                Log.i(TAG, "EQ 当前编号=$cur")
                writeGlobal("magicorig_diag_eq_cur", cur?.toString() ?: "null")
                // 现在 id 空间已统一（0/1/2/7 = slot），回读值直接作为 UI 的当前项
                cur?.let { writeGlobal("magicorig_eq", it.toString()) }
            }
            HemCommands.RSP_PRODUCT_ID -> {
                val productId = PodProfiles.parseProductId(msg.payload)
                val matched = PodProfiles.byProductId(productId)
                Log.i(
                    TAG,
                    "耳机 productId payload=${HemWire.hex(msg.payload)} parsed=$productId model=${matched?.id ?: "未识别"}"
                )
                if (matched != null) PodIdentity.publish(matched, mContext)
            }
            HemCommands.RSP_HANDSHAKE -> {
                Log.i(TAG, "握手成功 payload=${HemWire.hex(msg.payload)}")
                HemCapabilities.parse(msg.payload)?.let { caps ->
                    writeGlobal("magicorig_caps", caps.sorted().joinToString(","))
                    Log.i(TAG, "能力位 ${caps.size} 项")
                }
            }
            else -> if (BuildConfig.DEBUG) Log.v(TAG, "未处理 cmd=0x%04X".format(msg.cmd))
        }
    }

    /** 欢律上报值 → 荣耀 status → UI。未知值不改状态（避免在档位间抖动）。 */
    private fun applyAncRaw(raw: Int) {
        if (raw <= 0) {
            Log.w(TAG, "ANC 上报无有效值（回声帧或空），忽略")
            return
        }
        val status = HemAnc.statusForRaw(raw, currentProfile())
        if (status == null) {
            Log.w(TAG, "ANC 上报 raw=0x%04X 未知档位，保持原状态".format(raw))
            return
        }
        Log.i(TAG, "ANC 回读 raw=0x%04X → status=$status（${HemAnc.nameOfStatus(status)}）".format(raw))
        // 详情页要区分「自适应」与「降噪」（两者 status 可能相同）→ 另存原始位掩码
        writeGlobal("magicorig_anc_raw", raw.toString())
        currentAnc = status
        changeUIAncStatus(status)
    }

    private fun onHemBattery(r: HemBattery.Result) {
        if (!r.any) { Log.w(TAG, "电量报文无有效条目"); return }
        handleBatteryChanged(
            BatteryParser.BatteryResult(
                left = r.left?.let { BatteryParser.BatteryInfo(it.level, it.isCharging) },
                right = r.right?.let { BatteryParser.BatteryInfo(it.level, it.isCharging) },
                case = r.case?.let { BatteryParser.BatteryInfo(it.level, it.isCharging) }
            )
        )
    }

    private fun safeDisconnect(device: BluetoothDevice) {
        connectionGeneration.incrementAndGet()
        isConnected.set(false)
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        mContext = null
        MediaControl.mContext = null
        runCatching { mContext?.unregisterReceiver(broadcastReceiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver failed") }
        mShowedConnectedToast = false
        Log.d(TAG, "disconnected device=${device.address}")
    }

    private fun safeHandleUIEvent(intent: Intent) {
        try {
            when (intent.action) {
                MagicOriGAction.ACTION_PODS_UI_INIT -> {
                    Log.i(TAG, "UI Init")
                    val deviceName = mDevice.alias ?: mDevice.name ?: mDevice.address
                    safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", deviceName)
                        putExtra("address", mDevice.address)
                    }
                    mContext?.getSharedPreferences("magicorig_device", Context.MODE_PRIVATE)
                        ?.edit()?.putString("device_name", deviceName)?.apply()
                    queryStatus()
                }
                MagicOriGAction.ACTION_ANC_SELECT -> setANCMode(intent.getIntExtra("status", 0))
                MagicOriGAction.ACTION_FIND_BUDS -> setFindBuds(intent.getBooleanExtra("on", true))
                MagicOriGAction.ACTION_FEATURE_SET ->
                    setFeature(intent.getIntExtra("id", -1), intent.getBooleanExtra("on", false))
                MagicOriGAction.ACTION_EQ_PRESET -> setEqPreset(intent.getIntExtra("id", -1))
                MagicOriGAction.ACTION_EQ_CUSTOM -> eqCustom(
                    intent.getIntExtra("action", -1),
                    intent.getIntExtra("id", -1),
                    intent.getStringExtra("name"),
                    intent.getStringExtra("gains")
                )
                MagicOriGAction.ACTION_ALERT_VOLUME -> setAlertVolume(intent.getIntExtra("level", 5))
                MagicOriGAction.ACTION_FIT_TEST -> setFitTest(intent.getBooleanExtra("on", true))
                MagicOriGAction.ACTION_QUERY_DEVICES -> { ensureLink(); sendPacketSafe(HemCommands.queryDevices()) }
                MagicOriGAction.ACTION_GESTURE_SET ->
                    setGestureBinding(
                        intent.getIntExtra("dev", -1),
                        intent.getIntExtra("btn", -1),
                        intent.getIntExtra("act", -1),
                        intent.getIntExtra("fn", 0)
                    )
                MagicOriGAction.ACTION_REFRESH_STATUS -> queryStatus()
                MagicOriGAction.ACTION_GAME_MODE_SET -> setGameMode(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_LOW_LATENCY_SET -> setLowLatency(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_DUAL_CONN_SET -> setDualConn(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_EQ_SET -> setEq(EqMode.fromValue(intent.getIntExtra("value", 0)))
                MagicOriGAction.ACTION_WIND_SUPPRESSION_SET -> setWindSuppression(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_IN_EAR_DETECTION_SET -> setInEarDetection(intent.getBooleanExtra("enabled", false))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "UI event handler threw", e)
        }
    }

    private fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        runCatching {
            if (result.left != null) {
                cachedLeftBattery = PodParams(result.left.level, result.left.isCharging, true, 0)
                saveBattery("left_battery", "left_charging", result.left.level, result.left.isCharging)
            }
            if (result.right != null) {
                cachedRightBattery = PodParams(result.right.level, result.right.isCharging, true, 0)
                saveBattery("right_battery", "right_charging", result.right.level, result.right.isCharging)
            }
            if (result.case != null) {
                cachedCaseBattery = PodParams(result.case.level, result.case.isCharging, true, 0)
                saveBattery("case_battery", "case_charging", result.case.level, result.case.isCharging)
            }
        }.onFailure { Log.e(TAG, "battery cache update failed", it) }

        val left = cachedLeftBattery ?: PodParams(result.left?.level ?: 0, result.left?.isCharging == true, result.left != null, 0)
        val right = cachedRightBattery ?: PodParams(result.right?.level ?: 0, result.right?.isCharging == true, result.right != null, 0)
        val case = cachedCaseBattery ?: PodParams(result.case?.level ?: 0, result.case?.isCharging == true, result.case != null, 0)

        if (BuildConfig.DEBUG) Log.v(TAG, "batt L${left.battery} R${right.battery} C${case.battery}")

        val shouldShowToast = !mShowedConnectedToast
        val hasValidData = (left.isConnected && left.battery > 0) || (right.isConnected && right.battery > 0)
        currentBatteryParams = BatteryParams(left, right, case)

        runCatching {
            val ctx = mContext ?: return@runCatching
            if (shouldShowToast && hasValidData) {
                HuaweiStrongToastUtil.showConnectedToast(ctx, currentBatteryParams!!)
                mShowedConnectedToast = true
            }
            HuaweiStrongToastUtil.showPodsBatteryToast(ctx, currentBatteryParams!!)
            // ★ 任务4：双耳电量通知迁移到「音频切换」app 的连接耳机通知（imedia.sws），
            //   格式「OPPO Enco Free4 左耳 xx% 右耳 xx%」—— 蓝牙进程不再独立弹电量通知。
            // HuaweiStrongToastUtil.showBtNotification(ctx, mDevice, currentBatteryParams!!)
            // 取消旧通知（迁移后清掉可能残留的历史通知）
            runCatching { HuaweiStrongToastUtil.cancelPodsNotification(ctx) }
            changeUIBatteryStatus(currentBatteryParams!!)
            // 跨进程电量通道：SharedPreferences 是 bluetooth 私有目录，Settings/NoticeFlow 读不到，
            // 转存 Settings.Global（全进程只读可见），供通知 hook 追加电量。
            runCatching {
                val res = ctx.contentResolver
                android.provider.Settings.Global.putString(
                    res, "magicorig_battery",
                    "L${left.battery},R${right.battery},C${case.battery}"
                )
                // 连接态信号（任务6）：电量数据到达 = 耳机在线；断连时 disconnectedPod 置 0。
                // 设备中心球列表注入据此门控 —— 断连后球/卡片消失。
                android.provider.Settings.Global.putString(res, "magicorig_connected", "1")
                Log.i(TAG, "电量已写入 Settings.Global: L${left.battery} R${right.battery} C${case.battery} connL=${left.isConnected} connR=${right.isConnected}")
                runCatching {
                    historyAndLowBattery(
                        ctx,
                        left.battery, right.battery, case.battery,
                        left.isCharging, right.isCharging, case.isCharging
                    )
                }
            }.onFailure { Log.w(TAG, "Global 写入失败: ${it.javaClass.simpleName}") }
        }.onFailure { Log.e(TAG, "battery toast/notification failed", it) }

        lastTempBatt = when {
            left.isConnected && right.isConnected -> minOf(left.battery, right.battery)
            left.isConnected -> left.battery
            right.isConnected -> right.battery
            else -> -1
        }
    }

    /**
     * 电量快照：写入历史（供「电量历史」页）并在低电量时发一条通知。
     * 历史格式：`HH:mm,L,R,C;...`，最多保留 40 条。
     */
    private fun historyAndLowBattery(
        ctx: Context,
        l: Int, r: Int, c: Int,
        lCharging: Boolean, rCharging: Boolean, cCharging: Boolean,
    ) {
        val res = ctx.contentResolver
        val stamp = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val entry = "$stamp,$l,$r,$c"
        val old = runCatching {
            android.provider.Settings.Global.getString(res, "magicorig_batt_history")
        }.getOrNull().orEmpty()
        val list = old.split(';').filter { it.isNotBlank() }.toMutableList()
        // 同一分钟内同值不重复记录
        if (list.lastOrNull()?.substringAfter(',') != "$l,$r,$c") {
            list.add(entry)
            while (list.size > 40) list.removeAt(0)
            android.provider.Settings.Global.putString(res, "magicorig_batt_history", list.joinToString(";"))
        }

        // 低电量通知：耳机电量 ≤20% 且未充电
        val low = buildList {
            if (l in 1..20 && !lCharging) add("左耳 $l%")
            if (r in 1..20 && !rCharging) add("右耳 $r%")
        }
        if (low.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastLowBatteryAt < 30 * 60 * 1000L) return
        lastLowBatteryAt = now
        runCatching { postLowBatteryNotification(ctx, low.joinToString("、")) }
    }

    @Volatile
    private var lastLowBatteryAt = 0L

    private fun postLowBatteryNotification(ctx: Context, detail: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "magicorig_low_battery"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "耳机电量提醒", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "耳机电量低于 20% 时提醒" }
            )
        }
        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("耳机电量偏低")
            .setContentText("$detail，请及时充电")
            .setAutoCancel(true)
            .build()
        nm.notify(0x4D46, n)
        Log.i(TAG, "低电量通知: $detail")
    }

    fun initBatteryCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt("left_battery", 0) > 0) cachedLeftBattery = PodParams(
            prefs.getInt("left_battery", 0), prefs.getBoolean("left_charging", false), true, 0
        )
        if (prefs.getInt("right_battery", 0) > 0) cachedRightBattery = PodParams(
            prefs.getInt("right_battery", 0), prefs.getBoolean("right_charging", false), true, 0
        )
        if (prefs.getInt("case_battery", 0) > 0) cachedCaseBattery = PodParams(
            prefs.getInt("case_battery", 0), prefs.getBoolean("case_charging", false), true, 0
        )
    }

    private fun saveBattery(keyBattery: String, keyCharging: String, battery: Int, isCharging: Boolean) {
        runCatching {
            mContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
                putInt(keyBattery, battery)
                putBoolean(keyCharging, isCharging)
                apply()
            }
        }.onFailure { Log.w(TAG, "saveBattery failed", it) }
    }

    private fun sendPacketSafe(packet: ByteArray) {
        // 最后一环可见性：确认 SPP 帧真的写出去了（本 session 无法用 btm 日志验证数据包）
        Log.i(TAG, "→→ SPP 写出 len=${packet.size} hex=${packet.joinToString(" ") { String.format("%02X", it) }} socket=${socket != null}")
        try {
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
        } catch (e: Throwable) {
            Log.e(TAG, "sendPacketSafe unknown error", e)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("address", mDevice.address)
            putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(MagicOriGAction.ACTION_PODS_ANC_CHANGED) { putExtra("status", status) }
        // ★ 真实档位统一记录点（bug修复）：本函数是所有 currentAnc 更新的汇合点 ——
        //   ① 连接时 QUERY_ANC 查询帧的响应（AncModeParser 解析）
        //   ② 耳机本体触控切换（耳机主动上报）
        //   ③ 手机端 setANCMode 下发
        //   三条路径都会写 magicorig_current_anc，设备中心/蓝牙页据此**显示**当前模式，
        //   查询/上报只是读取，不产生"调模式"数据包。
        //   ★ 去重：心跳响应会被 AncModeParser 反复解析出同值（实测每 12ms 一次），
        //     相同状态不再重复写 Settings/发日志（高频写入拖慢系统）。
        if (status != lastRecordedAnc) {
            lastRecordedAnc = status
            val recCtx = mContext ?: DeviceIdentity.currentApp()
            recCtx?.let { ctx ->
                runCatching {
                    android.provider.Settings.Global.putString(
                        ctx.contentResolver, "magicorig_current_anc", status.toString()
                    )
                    Log.i(TAG, "真实 ANC 模式记录(汇合点): $status")
                }.onFailure { Log.w(TAG, "current_anc 写入失败: ${it.javaClass.simpleName}") }
            } ?: Log.w(TAG, "current_anc 未记录：context 均为 null")
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", mDevice.address)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(MagicOriGAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", mDevice.address)
            putBatteryExtras(status)
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        // ★ mContext 可能尚未初始化（实测静默 return → 外部广播从未发出 →
        //   Settings 侧收不到真实档位）—— 兜底 currentApplication。
        val ctx = mContext ?: DeviceIdentity.currentApp() ?: run {
            Log.w(TAG, "sendExternalPodsStatusBroadcast 跳过：context 不可用 (action=$action)")
            return
        }
        val targets = listOf("com.android.bluetooth", "com.android.settings")
        targets.forEach { targetPackage ->
            try {
                Intent(action).apply {
                    if (::mDevice.isInitialized) { putExtra("address", mDevice.address); putExtra("device_name", mDevice.name) }
                    fill()
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    ctx.sendBroadcast(this)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "sendBroadcast to $targetPackage failed", e)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_GAME_MODE_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUILowLatencyStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_LOW_LATENCY_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIDualConnStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_DUAL_CONN_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIEqStatus(mode: EqMode) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_EQ_CHANGED) { putExtra("value", mode.value) }
    }
    private fun changeUIWindSuppressionStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_WIND_SUPPRESSION_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIInEarDetectionStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_IN_EAR_DETECTION_CHANGED) { putExtra("enabled", enabled) }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        if (::mDevice.isInitialized && mDevice.address != device.address) return
        safeDisconnect(device)
        // 断联时移除电量通知 —— 此前 cancelPodsNotification 从未被调用，导致通知不消失。
        runCatching {
            HuaweiStrongToastUtil.cancelPodsNotification(context)
            // 任务6：断连置 0 → 设备中心球列表注入据此消失
            android.provider.Settings.Global.putString(context.contentResolver, "magicorig_connected", "0")
            Log.i(TAG, "已移除电量通知（断联 ${device.address}）")
        }.onFailure { Log.w(TAG, "cancelPodsNotification failed", it) }
        try {
            Intent(MagicOriGAction.ACTION_PODS_DISCONNECTED).apply {
                if (::mDevice.isInitialized) putExtra("address", mDevice.address)
                `package` = BuildConfig.APPLICATION_ID
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect broadcast failed", e)
        }
    }

    // ── 控制命令 ────────────────────────────────────────────────
    // ── 原道协议独有、欢律侧未实现的功能 ─────────────────────────
    // 这些入口仍被上层 hook 调用，但发旧 0x4E 帧对 Free4 毫无意义（只会当噪声丢掉），
    // 因此明确做成 no-op 并打日志，保持「不撒谎」：不广播假状态。
    private fun unsupported(feature: String) {
        Log.i(TAG, "$feature 未在欢律协议中实现，忽略（不影响降噪/电量）")
    }

    fun setGameMode(enabled: Boolean) = unsupported("游戏模式")
    fun setLowLatency(enabled: Boolean) = unsupported("低延迟")
    fun setDualConn(enabled: Boolean) = unsupported("双设备连接")
    fun setEq(mode: EqMode) = unsupported("EQ 切换")
    fun setWindSuppression(enabled: Boolean) = unsupported("抗风噪")
    fun setInEarDetection(enabled: Boolean) = unsupported("入耳检测")
    /**
     * 按需重建 SPP：蓝牙栈重启后，A2DP 会回连但 SPP 不一定建立，
     * 此时任何命令都会静默失败。所有命令入口先调用它。
     */
    private fun ensureLink() {
        if (socket != null) return
        if (!::mDevice.isInitialized) return
        Log.i(TAG, "socket 为空 → 重建 SPP 链路")
        runCatching { writeGlobal("magicorig_diag_link", "rebuild requested") }
        val ctx = mContext ?: return
        thread(name = "magicorig-rfcomm-rebuild") {
            for (attempt in 1..3) {
                if (socket != null) break
                // 关键：safeRfcommConnect 的循环条件要求 isConnected=true，
                // 直接调用会「一次都不试就返回」→ 先清状态再走完整的 connectPod 流程。
                runCatching { safeDisconnect(mDevice) }
                runCatching { connectPod(ctx, mDevice, mPrefs) }
                    .onFailure { Log.w(TAG, "重建链路失败 attempt=$attempt", it) }
                runCatching { Thread.sleep(3000) }
            }
            runCatching {
                writeGlobal(
                    "magicorig_diag_link",
                    if (socket != null) "rebuild ok" else "rebuild failed"
                )
            }
        }
    }

    /** 当前耳机档案。未识别时是 Free4，协议行为与改机型前一致。 */
    private fun currentProfile() = PodIdentity.current(mContext)

    /** 跨进程写 Settings.Global（holder 进程权限足够）。子线程用主线程缓存的 Application。 */
    private fun writeGlobal(key: String, value: String) {
        runCatching {
            val ctx = mContext ?: DeviceIdentity.currentApp() ?: return@runCatching
            android.provider.Settings.Global.putString(ctx.contentResolver, key, value)
        }
    }

    /** 功能开关：0x0403 [id, on] */
    fun setFeature(id: Int, on: Boolean) {
        ensureLink()
        if (id < 0) return
        sendPacketSafe(HemCommands.setFeature(id, on))
        writeGlobal("magicorig_diag_featset", "id=$id on=$on socket=${socket != null}")
        Log.i(TAG, "欢律 SET_FEATURE id=0x%02X on=$on".format(id))
        // 本地先记，耳机回报 0x810D 后会覆盖
        runCatching {
            val cur = android.provider.Settings.Global.getString(
                (mContext ?: return@runCatching).contentResolver, "magicorig_features"
            ).orEmpty()
            val map = cur.split(',').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
            map[id.toString()] = if (on) "1" else "0"
            writeGlobal("magicorig_features", map.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
    }

    /**
     * 改一条按键绑定：读当前表 → 替换指定条目的 function 字节 → 整表写回 → 读回校验。
     * 只改 function，保留 deviceType/button/buttonAction。
     */
    fun setGestureBinding(dev: Int, btn: Int, act: Int, function: Int) {
        ensureLink()
        if (dev <= 0 || btn <= 0 || act <= 0) return
        val cur = runCatching {
            android.provider.Settings.Global.getString(
                (mContext ?: return@runCatching null)?.contentResolver, "magicorig_gestures"
            )
        }.getOrNull()
        if (cur.isNullOrBlank()) {
            Log.w(TAG, "没有手势表，先查询")
            sendPacketSafe(HemCommands.queryGestures())
            return
        }
        val rows = cur.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 4) null else p.map { it.trim().toIntOrNull() ?: 0 }.toIntArray()
        }.toMutableList()
        val idx = rows.indexOfFirst { it[0] == dev && it[1] == btn && it[2] == act }
        if (idx < 0) {
            Log.w(TAG, "手势表里没有 dev=$dev btn=$btn act=$act")
            writeGlobal("magicorig_diag_gestset", "notfound dev=$dev btn=$btn act=$act")
            return
        }
        rows[idx] = intArrayOf(dev, btn, act, function)
        sendPacketSafe(HemCommands.setGestures(rows))
        writeGlobal("magicorig_diag_gestset", "dev=$dev btn=$btn act=$act fn=$function socket=${socket != null}")
        Log.i(TAG, "手势写回 dev=$dev btn=$btn act=$act fn=$function")
        scope.launch { delay(500); sendPacketSafe(HemCommands.queryGestures()) }
    }

    /** 贴合度检测：0x0405 [01/00] */
    fun setFitTest(on: Boolean) {
        ensureLink()
        sendPacketSafe(HemCommands.fitTest(on))
        writeGlobal("magicorig_diag_fit", "on=$on socket=${socket != null}")
        if (on) writeGlobal("magicorig_fit", "检测中…")
        Log.i(TAG, "欢律 FIT_TEST on=$on")
    }

    /** EQ 预设：0x0406 [id] */
    fun setEqPreset(id: Int) {
        ensureLink()
        if (id < 0) return
        val profile = currentProfile()
        if (profile.eqPresets.none { it.slot == id }) {
            Log.w(TAG, "EQ slot=$id 不在 ${profile.displayName} 的预设里，忽略")
            return
        }
        sendPacketSafe(HemCommands.setEqPreset(id))
        writeGlobal("magicorig_diag_eqset", "id=$id socket=${socket != null}")
        writeGlobal("magicorig_eq", id.toString())
        Log.i(TAG, "欢律 SET_EQ id=$id")
        // 写后回读：以耳机回报的编号为准
        scope.launch { delay(400); sendPacketSafe(HemCommands.queryEq()) }
    }

    /**
     * 自定义 EQ：选中 / 新建 / 保存 / 删除。
     * @param action 1 新建 / 2 保存并选中 / 3 删除
     * @param gainsCsv 逗号分隔的增益（-6..6），新建与保存时使用；为空则沿用已有预设
     */
    fun eqCustom(action: Int, id: Int, newName: String?, gainsCsv: String?) {
        ensureLink()
        val profile = currentProfile()
        if (!profile.supportsCustomEq) {
            Log.i(TAG, "${profile.displayName} 没有自定义 EQ，忽略写入")
            return
        }
        val list = if (eqCustomCache.isNotEmpty()) {
            eqCustomCache
        } else {
            val cur = runCatching {
                android.provider.Settings.Global.getString(
                    (mContext ?: return@runCatching null)?.contentResolver, "magicorig_eq_custom"
                )
            }.getOrNull().orEmpty()
            HemEq.deserialize(cur)
        }

        val base = if (action == HemEq.ACTION_CREATE) {
            HemEq.newPreset(newName ?: "自定义")
        } else {
            list.firstOrNull { it.id == id }
        }
        if (base == null) {
            writeGlobal("magicorig_diag_eq", "not found id=$id")
            return
        }

        // 用 UI 传来的增益覆盖（频段沿用预设自身的）
        val gains = gainsCsv?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        val preset = if (gains != null && gains.isNotEmpty()) {
            base.copy(
                gains = base.freqs.indices.map { gains.getOrElse(it) { 0 }.coerceIn(HemEq.GAIN_MIN, HemEq.GAIN_MAX) }
            )
        } else base

        sendPacketSafe(HemCommands.saveEq(action, preset))
        writeGlobal(
            "magicorig_diag_eq",
            "action=$action id=${preset.id} name=${preset.name} gains=${preset.gains.joinToString(",")} socket=${socket != null}"
        )
        Log.i(TAG, "EQ 写入 action=$action id=${preset.id} gains=${preset.gains}")
        scope.launch { delay(600); sendPacketSafe(HemCommands.queryEqAll()) }
    }

    /** 提示音音量：0x0427 [level] */
    fun setAlertVolume(level: Int) {
        ensureLink()
        sendPacketSafe(HemCommands.setAlertVolume(level))
        writeGlobal("magicorig_diag_volset", "level=$level socket=${socket != null}")
        writeGlobal("magicorig_alert_vol", level.coerceIn(1, 10).toString())
        Log.i(TAG, "欢律 SET_ALERT_VOLUME level=$level")
    }

    /** 欢律 0x0400：让耳机自己响铃（对应荣耀设备中心的「响铃查找」）。 */
    fun setFindBuds(on: Boolean) {
        ensureLink()
        val ctx = mContext ?: DeviceIdentity.currentApp()
        fun stamp(v: String) {
            runCatching {
                ctx?.let {
                    android.provider.Settings.Global.putString(
                        it.contentResolver, "magicorig_diag_findtx", v.take(200)
                    )
                }
            }
        }
        stamp("entered on=$on socket=${socket != null}")
        runCatching {
            val payload = byteArrayOf(if (on) 0x01 else 0x00)
            val packet = HemWire.build(0x0400, HemCommands.nextSeq(), payload)
            sendPacketSafe(packet)
            Log.i(TAG, "欢律 FIND_BUDS on=$on 已下发")
            stamp("sent=${socket != null} hex=${HemWire.hex(packet)}")
        }.onFailure { stamp("fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 用户最后一次选择的档位（-1 表示没选过）。 */
    private fun rememberAnc(mode: Int) {
        runCatching {
            val ctx = mContext ?: return@runCatching
            android.provider.Settings.Global.putString(
                ctx.contentResolver, "magicorig_anc_last", mode.toString()
            )
        }
    }

    /** 重连后恢复上次档位（只在用户选过的情况下）。 */
    fun restoreAncIfAny() {
        val ctx = mContext ?: return
        val last = runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, "magicorig_anc_last")
        }.getOrNull()?.toIntOrNull() ?: return
        if (last <= 0) return
        Log.i(TAG, "恢复上次 ANC 档位=$last")
        setANCMode(last)
    }

    fun setANCMode(mode: Int) {
        Log.i(TAG, "★ setANCMode(mode=$mode) socketReady=${::mDevice.isInitialized}")
        // 诊断"自动切降噪"：抓调用栈（10s 限频）
        val nowS = System.currentTimeMillis()
        if (nowS - lastAncStackAt > 10000) {
            lastAncStackAt = nowS
            val st = Thread.currentThread().stackTrace
            val frames = st.drop(3).take(10)
                .filterNot { it.className.startsWith("io.github.yuxiximyxx.magicfree4") }
                .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
            Log.w(TAG, "  △ setANCMode 调用栈:\n    $frames")
        }
        if (mode == currentAnc) {
            // 原本直接 return → currentAnc 初值与目标相同时永远不发包（实测 mode=3 早退，无 SPP 写出）。
            // 改为记录但仍下发，确保 UI 点击总能真正到达耳机。
            Log.i(TAG, "mode=$mode 与 currentAnc=$currentAnc 相同，仍强制下发")
        }
        val profile = currentProfile()
        // Air4 Pro 没有自适应。若上次在 Free4 上记住了 status 7，落到中档而不是把 7 写进状态。
        val effective = if (mode == 7 && !profile.supportsAdaptiveAnc) 5 else mode
        val bit = HemAnc.bitForStatus(effective, profile)
        if (bit == null) {
            Log.w(TAG, "未知 ANC status=$mode，忽略（不发帧）")
            writeGlobal("magicorig_diag_ancset", "mode=$mode bit=null")
            return
        }
        writeGlobal("magicorig_diag_ancset", "mode=$effective bit=$bit socket=${socket != null} model=${profile.id}")
        rememberAnc(effective)
        currentAnc = effective
        sendPacketSafe(HemCommands.setAnc(bit))
        Log.i(TAG, "欢律 SET_ANC bit=$bit（${HemAnc.nameOfStatus(effective)}）")
        changeUIAncStatus(currentAnc)
        // 写后回读：以耳机回报的值为准，避免「点击的值」冒充真实档位
        scope.launch { delay(400); sendPacketSafe(HemCommands.queryAnc()) }
        // 跨进程记录耳机**真实** ANC 模式 —— 设备中心卡片按它显示当前档位。
        // 否则 controlcenter 侧只能注入固定 NOISE_CANCELLATION，用户会误以为
        // "一打开界面就被自动切到降噪"（其实耳机没动，只是 UI 显示了默认值）。
        // ★ mContext 可能尚未初始化（实测为 null → let 不进入、静默丢失），
        //   兜底走 currentApplication，保证任何时点调用都能写入。
        val recCtx = mContext ?: DeviceIdentity.currentApp()
        recCtx?.let { ctx ->
            runCatching {
                android.provider.Settings.Global.putString(
                    ctx.contentResolver, "magicorig_current_anc", currentAnc.toString()
                )
                Log.i(TAG, "真实 ANC 模式已跨进程记录: $currentAnc")
            }.onFailure { Log.w(TAG, "current_anc 写入失败: ${it.javaClass.simpleName}") }
        } ?: Log.w(TAG, "current_anc 未记录：context 不可用（mContext/app 均为 null）")
    }
}
