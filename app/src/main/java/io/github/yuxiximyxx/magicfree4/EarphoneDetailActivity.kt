@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.yuxiximyxx.magicfree4

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuxiximyxx.magicfree4.pods.hem.EqPreset
import io.github.yuxiximyxx.magicfree4.pods.hem.PodProfile
import io.github.yuxiximyxx.magicfree4.pods.hem.PodProfiles
import io.github.yuxiximyxx.magicfree4.diag.ModuleDiagnostics
import io.github.yuxiximyxx.magicfree4.utils.DeviceIdentity
import io.github.yuxiximyxx.magicfree4.utils.MagicOriGAction
import io.github.yuxiximyxx.magicfree4.utils.PodIdentity
import kotlinx.coroutines.delay

/**
 * 耳机详情页 —— 系统设置风格（纯黑 + 卡片 + 顶部渐变模糊 + 圆形返回 + 大标题）。
 *
 * 三个页面共用一套壳：
 *   0 概览（电量 / 噪声控制 / 功能开关 / 设置入口）
 *   1 均衡器（预设列表）
 *   2 耳机设置（佩戴检测 / 查找耳机 / 提示音音量 / 固件版本 / 手势占位）
 */
class EarphoneDetailActivity : ComponentActivity() {

    /** 色板：跟随系统深浅色。深色对齐系统设置截图，浅色用 Honor 浅色取值。 */
    private object P {
        var bg = Color(0xFF000000); private set
        var card = Color(0xFF1C1C1E); private set
        var cardAlt = Color(0xFF2C2C2E); private set
        var line = Color(0xFF38383A); private set
        var textPrimary = Color(0xFFFFFFFF); private set
        var textSecondary = Color(0xFF8E8E93); private set
        var textTertiary = Color(0xFF636366); private set
        var accent = Color(0xFF0A84FF); private set
        var success = Color(0xFF32D74B); private set
        var danger = Color(0xFFFF453A); private set

        fun apply(dark: Boolean) {
            if (dark) {
                bg = Color(0xFF000000); card = Color(0xFF1C1C1E); cardAlt = Color(0xFF2C2C2E)
                line = Color(0xFF38383A); textPrimary = Color(0xFFFFFFFF)
                textSecondary = Color(0xFF8E8E93); textTertiary = Color(0xFF636366)
                accent = Color(0xFF0A84FF); success = Color(0xFF32D74B); danger = Color(0xFFFF453A)
            } else {
                bg = Color(0xFFF2F2F7); card = Color(0xFFFFFFFF); cardAlt = Color(0xFFE5E5EA)
                line = Color(0xFFD1D1D6); textPrimary = Color(0xFF000000)
                textSecondary = Color(0xFF6B7280); textTertiary = Color(0xFF9CA3AF)
                accent = Color(0xFF007DFF); success = Color(0xFF1E9E4A); danger = Color(0xFFD93025)
            }
        }
    }

    private val FEATURES = listOf(
        Triple(0x06, "低延迟模式", "与视频保持同步"),
        Triple(0x18, "高音质音频", "提高比特率与采样率"),
        Triple(0x1B, "3D 音效", "带头部追踪的空间音频"),
        Triple(0x04, "佩戴检测", "摘下耳机时自动暂停"),
        Triple(0x0B, "黄金听感", "听力增强，补偿高频细节"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceIdentity.remember(applicationContext)
        title = currentProfile().displayName
        // 状态栏保持透明，颜色交给页面自己画；深浅色图标在 setContent 里按主题设置
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
        setContent {
            val dark = isSystemInDarkTheme()
            // ★ 必须在这里先切换调色板：
            //   之前 P.apply 写在 Root() 内部，而 `Surface(color = P.bg)` 在它之前求值，
            //   于是 Surface 拿到的是默认的黑色，浅色模式下就变成「黑底 + 白卡片 + 顶部灰渐变」。
            P.apply(dark)
            val view = LocalView.current
            SideEffect {
                runCatching {
                    val act = view.context as? Activity
                    val w = act?.window ?: return@runCatching
                    val ctl = WindowCompat.getInsetsController(w, view)
                    ctl.isAppearanceLightStatusBars = !dark
                    ctl.isAppearanceLightNavigationBars = !dark
                    w.navigationBarColor = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
                }
            }
            // MIUIX 迁移（v2.11.0）：主题层换 MiuixTheme，深浅跟随系统（与 P 调色板同一信号 dark）
            val controller = remember(dark) {
                ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            }
            MiuixTheme(controller = controller) { Root() }
        }
    }

    private fun g(key: String): String? = runCatching {
        Settings.Global.getString(contentResolver, key)
    }.getOrNull()

    private fun currentProfile(): PodProfile =
        PodProfiles.byId(g(PodIdentity.MODEL_KEY)) ?: PodProfiles.FREE4

    private fun send(action: String, fill: Intent.() -> Unit = {}) {
        runCatching {
            sendBroadcast(Intent(action).apply {
                fill()
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    private fun openBt(mac: String) {
        val ok = runCatching {
            startActivity(
                Intent("com.hihonor.intent.action.DEVICE_PROFILES_SETTINGS").putExtra("DEVICE_ID", mac)
            )
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    @Composable
    private fun Root() {
        P.apply(isSystemInDarkTheme())
        // 子页面时拦截返回（含侧滑手势），先回概览而不是直接退出
        var raw by remember { mutableStateOf("") }
        var ancRaw by remember { mutableStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var mac by remember { mutableStateOf("") }
        var features by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
        var firmware by remember { mutableStateOf("--") }
        var alertVol by remember { mutableStateOf(5) }
        var eq by remember { mutableStateOf(-1) }
        var ringing by remember { mutableStateOf(false) }
        var gestRaw by remember { mutableStateOf("") }
        var fitRaw by remember { mutableStateOf("") }
        var fitSentAt by remember { mutableStateOf(0L) }
        var caps by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var devRaw by remember { mutableStateOf("") }
        var eqCustomRaw by remember { mutableStateOf("") }
        var battHistory by remember { mutableStateOf("") }
        var pnoise by remember { mutableStateOf("") }
        var editId by remember { mutableStateOf(-1) }
        var editName by remember { mutableStateOf("") }
        var editGains by remember { mutableStateOf(mutableListOf<Int>()) }
        var editFreqs by remember { mutableStateOf<List<Int>>(listOf(62, 250, 1000, 4000, 8000, 16000)) }
        var eqDeleteId by remember { mutableStateOf(-1) }
        var renameFor by remember { mutableStateOf(-1) }
        var renameText by remember { mutableStateOf("") }
        var showVol by remember { mutableStateOf(false) }
        var showGestPick by remember { mutableStateOf<IntArray?>(null) }
        var page by remember { mutableStateOf(0) }
        var modelId by remember { mutableStateOf(currentProfile().id) }

        // 侧滑/返回键先回上级页面，概览页才退出
        BackHandler(enabled = page != 0) { page = 0 }

        LaunchedEffect(Unit) {
            while (true) {
                raw = g("magicorig_battery") ?: ""
                ancRaw = g("magicorig_anc_raw")?.toIntOrNull() ?: 0
                connected = g("magicorig_connected") == "1"
                mac = g("magicorig_mac") ?: ""
                features = parseFeatures(g("magicorig_features"))
                firmware = g("magicorig_firmware") ?: "--"
                alertVol = g("magicorig_alert_vol")?.toIntOrNull() ?: 5
                eq = g("magicorig_eq")?.toIntOrNull() ?: -1
                gestRaw = g("magicorig_gestures") ?: ""
                fitRaw = g("magicorig_fit") ?: ""
                caps = g("magicorig_caps")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                    ?: emptySet()
                // 贴合度：15 秒没结果就提示重试，避免永远停在「检测中…」
                if (fitRaw == "检测中…" && fitSentAt > 0 &&
                    System.currentTimeMillis() - fitSentAt > 15_000
                ) {
                    fitRaw = "未收到结果，点击重试"
                }
                devRaw = g("magicorig_devices") ?: ""
                eqCustomRaw = g("magicorig_eq_custom") ?: ""
                battHistory = g("magicorig_batt_history") ?: ""
                pnoise = g("magicorig_pnoise") ?: ""
                modelId = g(PodIdentity.MODEL_KEY) ?: PodProfiles.FREE4.id
                delay(1000)
            }
        }

        val profile = PodProfiles.byId(modelId) ?: PodProfiles.FREE4
        SideEffect { title = profile.displayName }

        Box(modifier = Modifier.fillMaxSize()) {
            // —— MIUIX 骨架：Scaffold + SmallTopAppBar（返回钮进行为与旧自绘顶栏一致）——
            Scaffold(
                containerColor = P.bg,
                topBar = {
                    SmallTopAppBar(
                        title = when (page) {
                            0 -> profile.displayName
                            1 -> "均衡器"
                            3 -> "耳机手势"
                            4 -> "设备双连"
                            5 -> if (editId < 0) "新建均衡预设" else "编辑均衡预设"
                            6 -> "关于耳机"
                            7 -> "电量历史"
                            else -> "耳机设置"
                        },
                        color = Color.Transparent,
                        navigationIcon = {
                            IconButton(onClick = { if (page == 0) finish() else page = 0 }) {
                                Image(
                                    painter = painterResource(R.drawable.ic_back),
                                    contentDescription = "返回",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
            // —— 可滚动内容 ——
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // 标题只保留在顶部栏（与系统设置一致），这里只放连接态
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(6.dp)
                                .background(if (connected) P.success else P.textTertiary, CircleShape),
                        )
                        Text(
                            if (connected) "已连接 · $mac" else "未连接 · $mac",
                            fontSize = 12.sp,
                            color = P.textSecondary,
                        )
                    }

                    when (page) {
                        0 -> OverviewPage(
                            profile = profile,
                            battHistory = battHistory,
                            raw = raw, ancRaw = ancRaw, connected = connected,
                            features = features, eq = eq, alertVol = alertVol, firmware = firmware,
                            onNavigate = { page = it },
                            onOpenBt = { openBt(mac) },
                            onEq = { id -> send(MagicOriGAction.ACTION_EQ_PRESET) { putExtra("id", id) }; eq = id },
                            onFeature = { id, value ->
                                send(MagicOriGAction.ACTION_FEATURE_SET) {
                                    putExtra("id", id); putExtra("on", value != 1)
                                }
                                features = features.toMutableMap().apply { put(id, if (value == 1) 0 else 1) }
                            },
                            onFind = {
                                ringing = !ringing
                                send(MagicOriGAction.ACTION_FIND_BUDS) { putExtra("on", ringing) }
                            },
                            onVolume = { showVol = true },
                        )
                        1 -> EqPage(
                            profile = profile,
                            eq = eq,
                            customRaw = eqCustomRaw,
                            onSelect = { id ->
                                send(MagicOriGAction.ACTION_EQ_PRESET) { putExtra("id", id) }
                                eq = id
                            },
                            onSelectCustom = { id ->
                                val c = parseCustomEq(eqCustomRaw).firstOrNull { it.id == id }
                                editId = id
                                editName = c?.name ?: "自定义"
                                editGains = (c?.gains ?: listOf(0, 0, 0, 0, 0, 0)).toMutableList()
                                editFreqs = c?.freqs ?: listOf(62, 250, 1000, 4000, 8000, 16000)
                                page = 5
                            },
                            onLongPress = { eqDeleteId = it },
                            onCreateCustom = {
                                editId = -1
                                editName = "自定义 ${System.currentTimeMillis() % 1000}"
                                editGains = mutableListOf(0, 0, 0, 0, 0, 0)
                                editFreqs = listOf(62, 250, 1000, 4000, 8000, 16000)
                                page = 5
                            },
                        )
                        3 -> GesturePage(gestRaw = gestRaw, onPick = { showGestPick = it })
                        6 -> AboutPage(
                            profile = profile,
                            mac = mac, firmware = firmware, caps = caps,
                        )
                        7 -> BatteryHistoryPage(history = battHistory)
                        5 -> EqEditPage(
                            name = editName,
                            freqs = editFreqs,
                            gains = editGains,
                            isNew = editId < 0,
                            onGain = { i, v -> editGains = editGains.toMutableList().also { it[i] = v } },
                            onSave = {
                                send(MagicOriGAction.ACTION_EQ_CUSTOM) {
                                    putExtra("action", if (editId < 0) 1 else 2)
                                    if (editId >= 0) putExtra("id", editId)
                                    putExtra("name", editName)
                                    putExtra("gains", editGains.joinToString(","))
                                }
                                page = 1
                            },
                            onDelete = {
                                if (editId >= 0) {
                                    send(MagicOriGAction.ACTION_EQ_CUSTOM) {
                                        putExtra("action", 3)
                                        putExtra("id", editId)
                                    }
                                }
                                page = 1
                            },
                            onRename = {
                                renameFor = if (editId < 0) -2 else editId
                                renameText = editName
                            },
                        )
                        4 -> DevicesPage(
                            devRaw = devRaw,
                            connected = connected,
                            features = features,
                            onToggleDual = {
                                val v = features[0x11]
                                send(MagicOriGAction.ACTION_FEATURE_SET) {
                                    putExtra("id", 0x11)
                                    putExtra("on", v != 1)
                                }
                                features = features.toMutableMap().apply { put(0x11, if (v == 1) 0 else 1) }
                            },
                            onAddDevice = { openBt(mac) },
                            onRefresh = { send(MagicOriGAction.ACTION_QUERY_DEVICES) },
                        )
                        else -> SettingsPage(
                            onNavigate = { page = it },
                            connected = connected, features = features, firmware = firmware,
                            alertVol = alertVol, ringing = ringing,
                            onFeature = { id, value ->
                                send(MagicOriGAction.ACTION_FEATURE_SET) {
                                    putExtra("id", id); putExtra("on", value != 1)
                                }
                                features = features.toMutableMap().apply { put(id, if (value == 1) 0 else 1) }
                            },
                            onFind = {
                                ringing = !ringing
                                send(MagicOriGAction.ACTION_FIND_BUDS) { putExtra("on", ringing) }
                            },
                            onVolume = { showVol = true },
                            onOpenBt = { openBt(mac) },
                            fitRaw = fitRaw,
                            fitSupported = caps.isEmpty() || 0x0405 in caps,
                            pnoise = pnoise,
                            onFit = {
                                fitSentAt = System.currentTimeMillis()
                                fitRaw = "检测中…"
                                send(MagicOriGAction.ACTION_FIT_TEST) { putExtra("on", true) }
                            },
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }

            }
            }
        }

        val pick = showGestPick
        if (pick != null) {
            val onCall = pick[1] == 0x06
            val options = if (onCall) FUNCTIONS_ON_CALL else FUNCTIONS
            WindowDialog(
                show = true,
                title = if (onCall) "通话中 · 设为功能" else "主按键 · 设为功能",
                onDismissRequest = { showGestPick = null },
            ) {
                Column {
                    for ((fn, name) in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    send(MagicOriGAction.ACTION_GESTURE_SET) {
                                        putExtra("dev", pick[0])
                                        putExtra("btn", pick[1])
                                        putExtra("act", pick[2])
                                        putExtra("fn", fn)
                                    }
                                    showGestPick = null
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, fontSize = 15.sp, color = P.textPrimary)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { showGestPick = null },
                            colors = ButtonDefaults.textButtonColors(textColor = P.accent),
                        )
                    }
                }
            }
        }

        if (eqDeleteId >= 0) {
            val target = parseCustomEq(eqCustomRaw).firstOrNull { it.id == eqDeleteId }
            WindowDialog(
                show = true,
                title = "删除预设",
                summary = "删除「${target?.name ?: eqDeleteId}」？此操作不可恢复。",
                onDismissRequest = { eqDeleteId = -1 },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { eqDeleteId = -1 },
                        colors = ButtonDefaults.textButtonColors(textColor = P.textSecondary),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "删除",
                        onClick = {
                            send(MagicOriGAction.ACTION_EQ_CUSTOM) {
                                putExtra("action", 3)
                                putExtra("id", eqDeleteId)
                            }
                            eqDeleteId = -1
                        },
                        colors = ButtonDefaults.textButtonColors(textColor = P.danger),
                    )
                }
            }
        }

        if (renameFor != -1) {
            WindowDialog(
                show = true,
                title = "预设名称",
                onDismissRequest = { renameFor = -1 },
            ) {
                Column {
                    TextField(
                        value = renameText,
                        onValueChange = { if (it.length <= 20) renameText = it },
                        label = "预设名称",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { renameFor = -1 },
                            colors = ButtonDefaults.textButtonColors(textColor = P.textSecondary),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = "保存",
                            onClick = {
                                editName = renameText.ifBlank { editName }
                                if (renameFor >= 0) {
                                    // 直接保存改名（沿用当前增益）
                                    send(MagicOriGAction.ACTION_EQ_CUSTOM) {
                                        putExtra("action", 2)
                                        putExtra("id", renameFor)
                                        putExtra("name", editName)
                                        putExtra("gains", editGains.joinToString(","))
                                    }
                                }
                                renameFor = -1
                            },
                            colors = ButtonDefaults.textButtonColors(textColor = P.accent),
                        )
                    }
                }
            }
        }

        if (showVol) {
            WindowDialog(
                show = true,
                title = "提示音音量",
                onDismissRequest = { showVol = false },
            ) {
                Column {
                    Text("$alertVol / 10", fontSize = 18.sp, color = P.textPrimary)
                    Slider(
                        value = alertVol.toFloat(),
                        onValueChange = { alertVol = it.toInt().coerceIn(1, 10) },
                        valueRange = 1f..10f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { showVol = false },
                            colors = ButtonDefaults.textButtonColors(textColor = P.textSecondary),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = "保存",
                            onClick = {
                                send(MagicOriGAction.ACTION_ALERT_VOLUME) { putExtra("level", alertVol) }
                                showVol = false
                            },
                            colors = ButtonDefaults.textButtonColors(textColor = P.accent),
                        )
                    }
                }
            }
        }
    }

    // ── 页面 0：概览 ────────────────────────────────────────

    @Composable
    private fun OverviewPage(
        profile: PodProfile,
        battHistory: String,
        raw: String,
        ancRaw: Int,
        connected: Boolean,
        features: Map<Int, Int>,
        eq: Int,
        alertVol: Int,
        firmware: String,
        onNavigate: (Int) -> Unit,
        onOpenBt: () -> Unit,
        onEq: (Int) -> Unit,
        onFeature: (Int, Int) -> Unit,
        onFind: () -> Unit,
        onVolume: () -> Unit,
    ) {
        val bat = parseBattery(raw)
        val ancBit = ancBitOf(ancRaw, profile.supportsAdaptiveAnc)

        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Card20 {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BatteryRing("左耳", bat[0])
                    BatteryRing("右耳", bat[1])
                    BatteryRing("充电仓", bat[2])
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("噪声控制")
                Card20 {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModePill("关闭", ancBit == 0, connected, Modifier.weight(1f)) {
                            send(MagicOriGAction.ACTION_ANC_SELECT) { putExtra("status", 1) }
                        }
                        ModePill("降噪", ancBit == 5, connected, Modifier.weight(1f)) {
                            send(MagicOriGAction.ACTION_ANC_SELECT) { putExtra("status", 5) }
                        }
                        if (profile.supportsAdaptiveAnc) {
                            ModePill("自适应", ancBit == 11, connected, Modifier.weight(1f)) {
                                send(MagicOriGAction.ACTION_ANC_SELECT) { putExtra("status", 7) }
                            }
                        }
                        ModePill("通透", ancBit == 2, connected, Modifier.weight(1f)) {
                            send(MagicOriGAction.ACTION_ANC_SELECT) { putExtra("status", 2) }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("功能")
                Card20(padding = 0.dp) {
                    Column {
                        for ((idx, f) in FEATURES.withIndex()) {
                            val (id, name, desc) = f
                            val value = features[id]
                            SwitchRow(
                                name,
                                if (value == null) "$desc（固件未回报）" else desc,
                                checked = value == 1,
                                enabled = connected,
                            ) { onFeature(id, value ?: 0) }
                            if (idx != FEATURES.lastIndex) Line()
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("设置")
                Card20(padding = 0.dp) {
                    Column {
                        NavRow("均衡器", eqName(eq, profile.eqPresets)) { onNavigate(1) }
                        Line()
                        NavRow("设备双连", "开关与已连接设备") { onNavigate(4) }
                        Line()
                        NavRow("电量历史", if (battHistory.isBlank()) "暂无记录" else "最近 ${battHistory.split(';').size} 条") { onNavigate(7) }
                        Line()
                        NavRow("关于耳机", "型号、固件、协议") { onNavigate(6) }
                        Line()
                        NavRow("耳机设置", "手势、佩戴检测、提示音、固件") { onNavigate(2) }
                    }
                }
            }

            Text(
                "开关状态以耳机回报为准；灰色表示当前固件未报该项能力。",
                fontSize = 12.sp,
                color = P.textTertiary,
            )
        }
    }

    // ── 页面 1：均衡器 ──────────────────────────────────────

    @Composable
    private fun EqPage(
        profile: PodProfile,
        eq: Int,
        customRaw: String,
        onSelect: (Int) -> Unit,
        onSelectCustom: (Int) -> Unit,
        onCreateCustom: () -> Unit,
        onLongPress: (Int) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("推荐")
                Card20(padding = 0.dp) {
                    Column {
                        val presets = profile.eqPresets
                        for ((idx, p) in presets.withIndex()) {
                            val id = p.slot
                            val name = p.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable { onSelect(id) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    name,
                                    fontSize = 15.sp,
                                    color = if (id == eq) P.accent else P.textPrimary,
                                )
                                if (id == eq) Text("✓", color = P.accent, fontSize = 16.sp)
                            }
                            if (idx != presets.lastIndex) Line()
                        }
                    }
                }
            }
            if (profile.supportsCustomEq) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("自定义")
                    Card20(padding = 0.dp) {
                        Column {
                            val customs = parseCustomEq(customRaw)
                            customs.forEachIndexed { i, c ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .combinedClickable(
                                            onClick = { onSelectCustom(c.id) },
                                            onLongClick = { onLongPress(c.id) },
                                        )
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        c.name.ifBlank { "预设 ${c.id}" },
                                        fontSize = 15.sp,
                                        color = if (c.selected) P.accent else P.textPrimary,
                                    )
                                    if (c.selected) Text("✓", color = P.accent, fontSize = 16.sp)
                                }
                                if (i != customs.lastIndex) Line()
                            }
                            if (customs.isNotEmpty()) Line()
                            NavRow("新建自定义预设", "在耳机内创建一个平坦曲线") { onCreateCustom() }
                        }
                    }
                }
                Text(
                    "自定义预设上限由耳机固件决定（当前机型 3 个）；点预设可编辑增益或删除，保存走 0x0418。",
                    fontSize = 12.sp,
                    color = P.textTertiary,
                )
            }
        }
    }

    // ── 页面 3：耳机手势 ────────────────────────────────────

    private val ACTIONS = mapOf(
        1 to "单击", 2 to "双击", 3 to "三击", 4 to "长按", 5 to "滑动", 6 to "超长按",
    )

    /** 通话中按键可绑的功能（QuickBuds 实测：0x1D 接听/挂断、0x1C 拒接）。 */
    private val FUNCTIONS_ON_CALL = listOf(
        0x00 to "无",
        0x1D to "接听 / 挂断",
        0x1C to "拒接",
    )

    private val FUNCTIONS = listOf(
        0x00 to "无",
        0x01 to "播放 / 暂停",
        0x05 to "上一首",
        0x06 to "下一首",
        0x03 to "语音助手",
        0x0B to "音量 +",
        0x0C to "音量 -",
        0x0A to "切换歌曲",
        0x0D to "切换设备",
        0x11 to "游戏模式",
        0x07 to "音量（上滑增 / 下滑减）",
        0x08 to "降噪循环",
    )

    @Composable
    private fun GesturePage(gestRaw: String, onPick: (IntArray) -> Unit) {
        val all = parseGestures(gestRaw)
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (all.isEmpty()) {
                Text("正在读取按键绑定…", fontSize = 14.sp, color = P.textSecondary)
            } else {
                // 左耳(dev=1) / 右耳(dev=2)，组内再分主按键(btn=1)与通话中(btn=6)
                for (dev in listOf(1, 2)) {
                    val devRows = all.filter { it[0] == dev }
                    if (devRows.isEmpty()) continue
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(if (dev == 1) "左耳" else "右耳")
                        Card20(padding = 0.dp) {
                            Column {
                                var first = true
                                for (btn in listOf(1, 6)) {
                                    val rows = devRows.filter { it[1] == btn }.sortedBy { it[2] }
                                    if (rows.isEmpty()) continue
                                    for (row in rows) {
                                        if (!first) Line()
                                        first = false
                                        val act = ACTIONS[row[2]] ?: "动作${row[2]}"
                                        val fn = funcName(btn, row[3])
                                        val prefix = if (btn == 6) "通话中 · " else ""
                                        NavRow(prefix + act, fn) { onPick(row) }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "主按键=听歌时的点按；通话中=打电话时的按键。写入按「耳别+按键组+动作」精确定位，" +
                        "整表回写后读回校验。功能 25 未在公开协议中定义，故不提供修改。",
                    fontSize = 12.sp,
                    color = P.textTertiary,
                )
            }
        }
    }

    private fun funcName(btn: Int, fn: Int): String {
        val list = if (btn == 0x06) FUNCTIONS_ON_CALL else FUNCTIONS
        return list.firstOrNull { it.first == fn }?.second ?: "未识别($fn)"
    }

    private fun parseGestures(raw: String): List<IntArray> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 4) null else p.map { it.trim().toIntOrNull() ?: 0 }.toIntArray()
        }
    }

    // ── 页面 6：关于耳机 ────────────────────────────────────

    @Composable
    private fun AboutPage(
        profile: PodProfile,
        mac: String,
        firmware: String,
        caps: Set<Int>,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("设备")
                Card20(padding = 0.dp) {
                    Column {
                        InfoRow("型号", profile.displayName)
                        Line()
                        InfoRow("蓝牙地址", mac.ifBlank { "--" })
                        Line()
                        InfoRow("固件版本", firmware)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("协议")
                Card20(padding = 0.dp) {
                    Column {
                        InfoRow("协议", "欢律（HeyMelody）")
                        Line()
                        InfoRow("固件支持命令", "${caps.size} 项")
                        Line()
                        InfoRow("连接方式", "SPP · 0000079A / 00001107")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("模块")
                // v2.10.10：旧首页删除后，模块识别自检从 HomePage 迁到这里（关于页），入口不丢。
                // 原「模块设置」NavRow 跳 MainActivity——MainActivity 已改成回跳板、会绕回本页，故一并去掉。
                val diagReport = remember { runCatching { ModuleDiagnostics.collect(this@EarphoneDetailActivity) }.getOrNull() }
                Card20 {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (diagReport == null) {
                            Text("模块识别自检读取失败", fontSize = 14.sp, color = P.danger)
                        } else {
                            Text(
                                if (diagReport.recognized) "✓ LSPosed 可识别" else "✗ LSPosed 不可识别",
                                fontSize = 14.sp,
                                color = if (diagReport.recognized) P.success else P.danger,
                            )
                            diagReport.checks().forEach { check ->
                                val mark = if (check.ok == true) "✔" else if (check.ok == false) "✘" else "•"
                                val c = if (check.ok == true) P.success
                                        else if (check.ok == false) P.danger
                                        else P.textSecondary
                                Text(
                                    "$mark ${check.label}${check.detail?.let { " — $it" } ?: ""}",
                                    fontSize = 12.sp, color = c,
                                )
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("开源许可")
                Card20 {
                    Text(
                        "MagicPods 基于 MagicOriG 修改，欢律协议实现参考 QuickBuds（GPL-3.0）。",
                        fontSize = 12.sp, color = P.textSecondary,
                    )
                }
            }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        // MIUIX 只读行：左标签 + 右侧值（无箭头、不可点）
        BasicComponent(
            title = label,
            rightActions = {
                Text(value, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            },
        )
    }

    // ── 页面 7：电量历史 ────────────────────────────────────

    @Composable
    private fun BatteryHistoryPage(history: String) {
        val rows = history.split(';').filter { it.isNotBlank() }.reversed()
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (rows.isEmpty()) {
                Text("暂无电量记录（连接耳机后开始记录）", fontSize = 14.sp, color = P.textSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("最近 ${rows.size} 条")
                    Card20(padding = 0.dp) {
                        Column {
                            rows.forEachIndexed { i, row ->
                                val p = row.split(',')
                                if (p.size == 4) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(p[0], fontSize = 13.sp, color = P.textSecondary,
                                            modifier = Modifier.width(56.dp))
                                        Text("左 ${p[1]}%   右 ${p[2]}%   仓 ${p[3]}%",
                                            fontSize = 14.sp, color = P.textPrimary)
                                    }
                                    if (i != rows.lastIndex) Line()
                                }
                            }
                        }
                    }
                }
                Text("最多保留 40 条，同一分钟内相同电量不重复记录。",
                    fontSize = 12.sp, color = P.textTertiary)
            }
        }
    }

    // ── 页面 5：均衡预设编辑 ────────────────────────────────

    @Composable
    private fun EqEditPage(
        name: String,
        freqs: List<Int>,
        gains: List<Int>,
        isNew: Boolean,
        onGain: (Int, Int) -> Unit,
        onSave: () -> Unit,
        onDelete: () -> Unit,
        onRename: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Card20 {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = P.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isNew) "保存后会在耳机里创建这个预设" else "调整各频段增益后点保存",
                    fontSize = 12.sp, color = P.textSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("频段增益（-6 ~ +6 dB）")
                Card20(padding = 0.dp) {
                    Column {
                        freqs.forEachIndexed { i, f ->
                            val g = gains.getOrElse(i) { 0 }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (f >= 1000) "${f / 1000}kHz" else "${f}Hz",
                                    fontSize = 13.sp, color = P.textSecondary,
                                    modifier = Modifier.width(56.dp),
                                )
                                Slider(
                                    value = g.toFloat(),
                                    onValueChange = { onGain(i, it.toInt().coerceIn(-6, 6)) },
                                    valueRange = -6f..6f,
                                    steps = 11,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "%+d".format(g),
                                    fontSize = 13.sp, color = P.textPrimary,
                                    modifier = Modifier.width(40.dp),
                                )
                            }
                            if (i != freqs.lastIndex) Line()
                        }
                    }
                }
            }

            Card20(padding = 0.dp) {
                NavRow("重命名", name) { onRename() }
                Line()
                NavRow("保存", "") { onSave() }
                if (!isNew) {
                    Line()
                    NavRow("删除这个预设", "删除后不可恢复") { onDelete() }
                }
            }
        }
    }

    // ── 页面 4：设备双连 ────────────────────────────────────

    @Composable
    private fun DevicesPage(
        devRaw: String,
        connected: Boolean,
        features: Map<Int, Int>,
        onToggleDual: () -> Unit,
        onAddDevice: () -> Unit,
        onRefresh: () -> Unit,
    ) {
        val devs = parseDevices(devRaw)
        val dualOn = features[0x11] == 1
        // 本机蓝牙名（用于把手机那行标成「本机」）
        // 普通 App 往往拿不到本机蓝牙名（需要 BLUETOOTH_CONNECT/系统权限），多路兜底
        val localBtName = runCatching {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name
        }.getOrNull() ?: runCatching {
            android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
        }.getOrNull()

        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Card20(padding = 0.dp) {
                SwitchRow(
                    title = "设备双连",
                    subtitle = "开启后可与两台设备同时连接，实现便捷切换",
                    checked = dualOn,
                    enabled = connected,
                    onClick = { onToggleDual() },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("已连接设备")
                Card20(padding = 0.dp) {
                    Column {
                        if (devs.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("正在读取设备列表…", fontSize = 15.sp, color = P.textSecondary)
                            }
                        } else {
                            devs.forEachIndexed { i, d ->
                                val isLocal = !localBtName.isNullOrBlank() && d.second == localBtName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            d.second.ifBlank { d.first },
                                            fontSize = 15.sp,
                                            color = if (isLocal) P.accent else P.textPrimary,
                                        )
                                        Text(
                                            when {
                                                d.third && isLocal -> "已连接（本机）"
                                                d.third -> "已连接"
                                                else -> "未连接"
                                            },
                                            fontSize = 12.sp,
                                            color = if (d.third) P.success else P.textSecondary,
                                        )
                                    }
                                }
                                if (i != devs.lastIndex || true) Line()
                            }
                        }
                        NavRow("添加新设备", "") { onAddDevice() }
                        Line()
                        NavRow("刷新列表", "") { onRefresh() }
                    }
                }
            }
            Text(
                "列表来自耳机自身（0x8112）；「设备双连」开关走 0x0403 功能位 0x11。",
                fontSize = 12.sp,
                color = P.textTertiary,
            )
        }
    }

    private data class EqCustom(
        val id: Int,
        val name: String,
        val selected: Boolean,
        val freqs: List<Int> = listOf(62, 250, 1000, 4000, 8000, 16000),
        val gains: List<Int> = listOf(0, 0, 0, 0, 0, 0),
    )

    private fun parseCustomEq(raw: String): List<EqCustom> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val p = row.split('|')
            if (p.size < 4) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            val freqs = ArrayList<Int>()
            val gains = ArrayList<Int>()
            p[3].split(',').forEach { band ->
                val kv = band.split(':')
                if (kv.size == 2) {
                    kv[0].toIntOrNull()?.let { freqs.add(it) }
                    kv[1].toIntOrNull()?.let { gains.add(it) }
                }
            }
            EqCustom(id, p[1], p[2] == "1", freqs, gains)
        }
    }

    private fun parseDevices(raw: String): List<Triple<String, String, Boolean>> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val p = row.split('|')
            if (p.size != 3) null else Triple(p[0], p[1], p[2] == "1")
        }
    }

    // ── 页面 2：耳机设置 ────────────────────────────────────

    @Composable
    private fun SettingsPage(
        onNavigate: (Int) -> Unit,
        connected: Boolean,
        features: Map<Int, Int>,
        firmware: String,
        alertVol: Int,
        ringing: Boolean,
        onFeature: (Int, Int) -> Unit,
        onFind: () -> Unit,
        onVolume: () -> Unit,
        onOpenBt: () -> Unit,
        fitRaw: String,
        fitSupported: Boolean,
        pnoise: String,
        onFit: () -> Unit,
    ) {
        val wear = features[0x04]
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("功能")
                Card20(padding = 0.dp) {
                    Column {
                        SwitchRow("佩戴检测", "摘下耳机时自动暂停", wear == 1, connected && wear != null) {
                            if (wear != null) onFeature(0x04, wear)
                        }
                        Line()
                        NavRow("耳机手势", "设置点按和滑动") { onNavigate(3) }
                        Line()
                        NavRow("设备双连", "开关与已连接设备") { onNavigate(4) }
                        Line()
                        NavRow(
                            "个性化降噪",
                            when (pnoise) {
                                "1" -> "已有个性化降噪结果"
                                "0" -> "暂无测试结果（需在欢律 App 完成测试）"
                                else -> "正在检测…"
                            },
                        ) { }
                        Line()
                        NavRow(
                            "贴合度检测",
                            when {
                                !fitSupported -> "本固件不支持该功能"
                                fitRaw.isEmpty() -> "检测左右耳佩戴贴合度"
                                else -> fitRaw
                            },
                        ) { if (fitSupported) onFit() }
                        Line()
                        NavRow(
                            "查找耳机",
                            if (ringing) "正在响铃，点击停止" else "让两只耳机响铃",
                        ) { onFind() }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("声音")
                Card20(padding = 0.dp) {
                    Column {
                        NavRow("提示音音量", "$alertVol / 10") { onVolume() }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("关于耳机")
                Card20(padding = 0.dp) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("固件版本", fontSize = 15.sp, color = P.textPrimary)
                            Text(firmware, fontSize = 14.sp, color = P.textSecondary)
                        }
                        Line()
                        NavRow("蓝牙连接设置", "") { onOpenBt() }
                    }
                }
            }
        }
    }

    // ── 组件 ───────────────────────────────────────────────

    @Composable
    private fun SectionTitle(text: String) {
        // MIUIX 分组标题（原自绘 Text 换 SmallTitle，调用点不变）
        SmallTitle(text = text, insideMargin = PaddingValues(start = 4.dp))
    }

    @Composable
    private fun Card20(padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable () -> Unit) {
        // MIUIX Card 容器（色/圆角沿用旧自绘卡取值，内部行组件已换 Miuix）
        Card(
            modifier = Modifier.fillMaxWidth(),
            color = P.card,
            cornerRadius = 20.dp,
            insideMargin = PaddingValues(padding),
        ) {
            Column { content() }
        }
    }

    @Composable
    private fun Line() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
                .height(1.dp)
                .background(P.line),
        )
    }

    @Composable
    private fun BatteryRing(label: String, level: Int) {
        val pct = level.coerceIn(0, 100)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(72.dp)) {
                    val stroke = 6.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = P.cardAlt, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (level >= 0) {
                        drawArc(
                            color = if (pct <= 20) P.danger else P.success,
                            startAngle = -90f, sweepAngle = 360f * pct / 100f, useCenter = false,
                            topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    if (level >= 0) "$pct%" else "--",
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.textPrimary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 12.sp, color = P.textSecondary)
        }
    }

    @Composable
    private fun ModePill(
        label: String,
        active: Boolean,
        enabled: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                .height(56.dp)
                .background(
                    when {
                        !enabled -> P.cardAlt.copy(alpha = 0.4f)
                        active -> P.accent
                        else -> P.cardAlt
                    },
                    RoundedCornerShape(14.dp),
                )
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    !enabled -> P.textTertiary
                    active -> Color.White
                    else -> P.textPrimary
                },
            )
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        // MIUIX 开关行（整行点按与开关拨动都走同一个 onClick，行为与旧 SwitchRow 一致）
        SuperSwitch(
            title = title,
            summary = subtitle.takeIf { it.isNotEmpty() },
            checked = checked,
            onCheckedChange = { onClick() },
            enabled = enabled,
        )
    }

    @Composable
    private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
        // MIUIX 导航行（标题 + 摘要 + 箭头）
        SuperArrow(
            title = title,
            summary = subtitle.takeIf { it.isNotEmpty() },
            onClick = onClick,
        )
    }

    // ── 数据 ───────────────────────────────────────────────

    private fun eqName(id: Int, presets: List<EqPreset>): String =
        presets.firstOrNull { it.slot == id }?.name ?: "未设置"

    /**
     * 上报位掩码 → 档位标识（0 关闭 / 2 通透 / 5 降噪 / 11 自适应）。
     * 注意：**SET 表与上报表不是同一套**——关闭上报 bit3、通透上报 bit8，
     * 直接拿尾零位比对会导致「切换成功但按钮不变亮」。
     * Air4 Pro 没有自适应按钮，bit 11 落到「降噪」。
     */
    private fun ancBitOf(raw: Int, adaptive: Boolean): Int {
        if (raw <= 0) return 0
        return when (Integer.numberOfTrailingZeros(raw)) {
            0, 3 -> 0
            2, 8 -> 2
            11 -> if (adaptive) 11 else 5
            else -> 5
        }
    }

    private fun parseFeatures(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',').mapNotNull { part ->
            val k = part.substringBefore('=').trim().toIntOrNull() ?: return@mapNotNull null
            val v = part.substringAfter('=', "").trim().toIntOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()
    }

    private fun parseBattery(raw: String): IntArray {
        val out = intArrayOf(-1, -1, -1)
        if (raw.isBlank()) return out
        raw.split(',').forEach { part ->
            val p = part.trim()
            if (p.length < 2) return@forEach
            val v = p.substring(1).toIntOrNull() ?: return@forEach
            when (p.first()) {
                'L' -> out[0] = v
                'R' -> out[1] = v
                'C' -> out[2] = v
            }
        }
        return out
    }
}
