package io.github.yuxiximyxx.magicfree4.hook

import android.content.Intent
import io.github.yuxiximyxx.magicfree4.pods.hem.PodProfiles
import io.github.yuxiximyxx.magicfree4.utils.DeviceIdentity
import io.github.yuxiximyxx.magicfree4.utils.MagicOriGAction
import io.github.yuxiximyxx.magicfree4.utils.PodIdentity

/**
 * ControlCenterNoiseHook — 作用域 com.hihonor.controlcenter（设备中心/控制中心降噪卡）。
 *
 * 逆向结论（discovery/cc/dn/classes.dex）：
 * - DeviceServiceCardDataMgr.createEarphoneNoiseSection(LinkDevice) 无条件建卡（唯一退出=LinkDevice null）
 *   数据来自 EarphoneNoiseData.fromBatteryData(BatteryData)
 *   返回 null → supportedModes=空 + currentMode=UNKNOWN → 按钮全灰（我们的耳机现状）
 * - 点击 EarphoneNoiseSectionController.onNoiseModeClick(NoiseMode)：
 *     4个早退（section禁用/isSetting/佩戴禁用/已是当前模式）
 *     → ncValue = EarphoneNoiseData.toNcModeValue(mode)
 *     → PropertyUtils.setNoiseCtrlMode(deviceId, ncValue, cb)   ← 下发接口
 *
 * ncValue 语义（由 toNcModeValue 推定）：1=降噪 2=透传(AWARENESS) 0=关闭 3=自适应
 *
 * 本 hook 做两件事：
 * 1. fromBatteryData 返回 null 时注入（支持 噪声/感知/关闭 三模式 + 按 last_anc 恢复当前档）
 *    —— 原返回非 null 时用原值，不影响荣耀自家耳机
 * 2. setNoiseCtrlMode 拦截 → 映射为本模块 status → 广播 ACTION_ANC_SELECT
 *    → 复用已打通的 bluetooth 进程 RfcommController → SPP 0x4E 链路
 *
 * ★ 事实纠正（2026-11-10 00:50 真机日志实锤，此前认反过，勿再混淆）：
 *   - 本机 Free4 的 MAC 是 88:92:CC:E5:00:8D（prodId 00VVD9、capsule 键 "Third"）
 *   - C8:68:DE:62:45:53 是 HUAWEI FreeBuds 5（capsule 键 VRDK），不是 Free4
 *   - 小乐截图报障的那张卡是控制中心服务卡——由本进程（com.hihonor.controlcenter）
 *     按本 hook 注入的 DeviceInfo 渲染，不是 AAM 的 EarphoneDetailsActivity；
 *     图标与电量问题都要在本进程查（v2.10.6 的资源潜行正为此装在这里）。
 */
object ControlCenterNoiseHook : HookContext() {
    private const val TAG = "MagicOriG-CtrlCenter"
    @Volatile private var lastClickStackAt = 0L
    @Volatile private var lastAncQueryAt = 0L
    private const val MODE_CLS = "com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseData\$NoiseMode"
    private const val DATA_CLS = "com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseData"

    override fun onHook() {
        // ── 0) v2.10.6 侦察：控制中心资源潜行 + BatteryData 方法面转储 ──
        //    截图那张卡是本进程渲染的服务卡，图标取的 drawable 与仓电量
        //    接口都要在这里抓（见类头事实纠正）。
        runCatching { installCcResourceSnoop() }.onFailure {
            Log.e(TAG, "install 控制中心资源潜行 failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching { dumpBatteryDataSurface() }.onFailure {
            Log.w(TAG, "BatteryData 方法面转储外层失败: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 0b) v2.10.7 侦察：卡头图标定名（setImageDrawable 成品层 + 卡视图树转储）──
        //    v2.10.8 接续：转储后在控制中心进程做卡头修复（绑定确认是本机
        //    Free4 才动：图标换入耳式 + 隐藏卡头单值电量），见 applyCardHeaderFixes。
        runCatching { installCardIconSnoop() }.onFailure {
            Log.e(TAG, "install 卡头图标定名 failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 1) 按钮点亮：fromBatteryData 为 null 时注入数据 ──
        runCatching {
            val dataCls = findClass(DATA_CLS)
            val batCls = findClass("com.hihonor.controlviewnew.info.BatteryData")
            val fb = dataCls.getDeclaredMethod("fromBatteryData", batCls).apply { isAccessible = true }
            module.hook(fb).intercept { chain ->
                val orig = chain.proceed()
                if (orig != null) return@intercept orig   // 有真实数据 → 不干预
                // 构造注入对象
                val data = dataCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                // ★ 必须用 findClass（宿主 ClassLoader）：Class.forName 走模块 loader 会 CNFE
                //   （实测: ClassNotFoundException EarphoneNoiseData$NoiseMode，DexPathList 含模块 apk）
                val modeCls = findClass(MODE_CLS)
                val consts = modeCls.enumConstants
                if (consts == null) {
                    Log.w(TAG, "NoiseMode 非 enum？跳过注入")
                    return@intercept data
                }
                // ★ 当前档位三级优先（让 UI 显示耳机真实状态，而不是固定默认值 ——
                //   否则用户会误以为"一打开就被自动切到降噪"）：
                //   1) magicorig_current_anc —— bluetooth 进程下发成功后记录的真实模式
                //   2) magicorig_last_anc     —— 用户在 App 里设的默认档位
                //   3) 兜底 NOISE_CANCELLATION
                val app0 = currentApp()
                // FIX-010：荣耀的 NoiseMode 枚举里本来就有 ADAPTIVE（ordinal 3 → ncValue 3），
                // 上游只注入了三档，Free4 补上第四档「自适应」。
                // Air4 Pro 没有 slot 11，面板不放这一档（点了也会被映射回中档，这里直接不显示）。
                val profile = PodIdentity.current(app0)
                val wanted = if (profile.supportsAdaptiveAnc) {
                    setOf("NOISE_CANCELLATION", "AWARENESS", "OFF", "ADAPTIVE")
                } else {
                    setOf("NOISE_CANCELLATION", "AWARENESS", "OFF")
                }
                val modes = java.util.ArrayList<Any>()
                for (c in consts) {
                    if (c.toString() in wanted) modes.add(c)
                }
                dataCls.getDeclaredMethod("setSupportedModes", List::class.java)
                    .apply { isAccessible = true }.invoke(data, modes)
                fun g(key: String): String? = runCatching {
                    app0?.let { android.provider.Settings.Global.getString(it.contentResolver, key) }
                }.getOrNull()
                fun map(status: String?): String = when (status) {
                    "1" -> "OFF"
                    "2" -> "AWARENESS"
                    "7" -> if (profile.supportsAdaptiveAnc) "ADAPTIVE" else "NOISE_CANCELLATION"
                    "3", "4", "5" -> "NOISE_CANCELLATION"
                    else -> ""
                }
                val cur = map(g("magicorig_current_anc")).ifEmpty {
                    map(g("magicorig_last_anc")).ifEmpty { "NOISE_CANCELLATION" }
                }
                val curMode = consts.firstOrNull { it.toString() == cur }
                if (curMode != null) {
                    dataCls.getDeclaredMethod("setCurrentMode", modeCls)
                        .apply { isAccessible = true }.invoke(data, curMode)
                }
                Log.i(TAG, "fromBatteryData 注入成功: modes=$modes current=$cur (orig=null)")
                data
            }
            Log.i(TAG, "installed: EarphoneNoiseData.fromBatteryData [按钮点亮]")
        }.onFailure {
            Log.w(TAG, "install fromBatteryData failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 2) 下发桥：setNoiseCtrlMode → 广播 → 已打通 SPP 链路 ──
        runCatching {
            val pu = findClass("com.hihonor.controlviewnew.utils.PropertyUtils")
            val m = pu.declaredMethods.first { it.name == "setNoiseCtrlMode" }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val deviceId = chain.args.getOrNull(0)
                val nc = (chain.args.getOrNull(1) as? Int) ?: -1
                // ncValue → 模块 status(非空 Int)：降噪=用户默认档位(5/4/3)  透传=2  关闭=1  自适应忽略
                // ★ 必须是 Int：曾用 String 导致 bluetooth 端 getIntExtra("status",0) 读不到
                //   （类型不符返回默认 0，0 不在映射表 → 不发 SPP 帧 → 点击无效）
                val adaptive = PodIdentity.current(currentApp()).supportsAdaptiveAnc
                val status: Int = when (nc) {
                    1 -> (readLastAnc() ?: "5").toIntOrNull() ?: 5
                    2 -> 2
                    0 -> 1
                    // Free4：ADAPTIVE → 扩展状态 7（欢律 bit 11）
                    // Air4 Pro 没有这一档，落到中档降噪（status 5 / slot 5）
                    3 -> if (adaptive) 7 else 5
                    else -> -1
                }
                Log.w(TAG, "★ setNoiseCtrlMode(deviceId=$deviceId, nc=$nc) → status=$status")
                // 诊断"进设备中心自动切降噪"：抓调用栈（10s 限频）
                val nowS = System.currentTimeMillis()
                if (nowS - lastClickStackAt > 10000) {
                    lastClickStackAt = nowS
                    val st = Thread.currentThread().stackTrace
                    val frames = st.drop(3).take(10)
                        .filterNot { it.className.startsWith("io.github.yuxiximyxx.magicfree4") || it.className.contains("xposed") || it.className.contains("lsposed") || it.className.contains("LSPosed") }
                        .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
                    Log.w(TAG, "  △ setNoiseCtrlMode 调用栈:\n    $frames")
                }
                var handled = false
                if (status in 0..9) {
                    runCatching {
                        val app = Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                        app?.sendBroadcast(
                            Intent("io.github.yuxiximyxx.magicfree4.ACTION_ANC_SELECT").putExtra("status", status)
                        )
                        Log.w(TAG, "→→ 已广播 ANC status=$status (设备中心)")
                    }.onFailure { Log.w(TAG, "广播失败: ${it.javaClass.simpleName}") }
                    // ★ 荣耀 MBB 对第三方设备的 setNoiseCtrlMode 永不回调（onResult/onError 都不来）
                    //   → onNoiseModeClick 的 isSetting 永真 → 点击一次后所有模式被早退（卡死）。
                    //   这里直接模拟成功回调（$1.onResult 的参数不参与逻辑，只 post V 复位+高亮），
                    //   并跳过原 MBB 调用。
                    val cb = chain.args.getOrNull(2)
                    if (cb != null) {
                        runCatching {
                            cb.javaClass.getMethod("onResult", String::class.java).invoke(cb, "")
                            handled = true
                            Log.w(TAG, "→→ 已模拟 onResult 回调（跳过 MBB 原调用）")
                        }.onFailure { Log.w(TAG, "模拟回调失败: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                } else {
            Log.w(TAG, "nc=$nc 不支持，跳过广播")
                }
                if (handled) {
                    return@intercept null   // setNoiseCtrlMode 返回 void
                }
                // 回退路径：仍走原调用（依赖其回调）
                chain.proceed()
            }
            Log.i(TAG, "installed: PropertyUtils.setNoiseCtrlMode [设备中心下发桥]")
        }.onFailure {
            Log.w(TAG, "install setNoiseCtrlMode failed (可能本进程无此类): ${it.javaClass.simpleName}")
        }

        // ── 3) 观察点击（4个早退排障用） ──
        runCatching {
            val ctlCls = findClass("com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseSectionController")
            val m = ctlCls.getDeclaredMethods().first {
                it.name == "onNoiseModeClick" && it.parameterCount == 1
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                Log.w(TAG, "onNoiseModeClick mode=${chain.args.getOrNull(0)}")
                // 抓调用栈定位"打开页面就自动点击"的触发者（限频：每 5 秒最多 1 条）
                val now = System.currentTimeMillis()
                if (now - lastClickStackAt > 5000) {
                    lastClickStackAt = now
                    val st = Thread.currentThread().stackTrace
                    val frames = st.drop(3).take(8)
                        .filterNot { it.className.startsWith("io.github.yuxiximyxx.magicfree4") || it.className.startsWith("java.lang.reflect") || it.className == "de.robv.android.xposed" || it.className.contains("xposed") || it.className.contains("LSPosed") || it.className.contains("lsposed") }
                        .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
                    Log.w(TAG, "  △ 调用栈:\n    $frames")
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: onNoiseModeClick [观察]")
        }.onFailure {
            Log.w(TAG, "install onNoiseModeClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 4) 设备列表注入：耳机不在 devicemanager.getDevices() 时构造 LinkDevice JSON ──
        // getDeviceList() 唯一来源是 DeviceManager.getDevices()（nearby 提供），
        // 我们的耳机不在其中 → 设备中心不显示（用户反馈"没添加进去"的根因）。
        // LinkDevice 序列化字段（Gson）：deviceId/deviceName/hnDeviceType/deviceNodeId/
        //   macAddress/btMacAddress/prodId/isSelfDevice/hnOnLineTypeList
        // 类型枚举：Third_EarPhone（第三方耳机）
        // 纯字符串操作，不构造 AIDL 对象；已含本机则跳过，避免重复。
        val provCls = runCatching {
            findClass("com.hihonor.controlcenter.superdevice.ControlCenterProvider")
        }.getOrNull()
        if (provCls == null) {
            // 该 hook 只对 controlcenter 进程有意义；其他进程没有这个类，静默跳过（原先刷 3 次 W 噪音）
            Log.d(TAG, "getDeviceList: 宿主无 ControlCenterProvider，跳过")
        } else runCatching {
            val m = provCls.declaredMethods.first {
                it.name == "getDeviceList" && it.parameterCount == 0
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    if (result == null) return@runCatching
                    val ext = result.javaClass.getMethod("getExt").invoke(result) as? String
                        ?: return@runCatching
                    val name = ourName()
                    if (ext.contains(ourMac(), ignoreCase = true)) {
                        // 换了一副耳机时，列表里还是上一台的 deviceName。MAC 没变就地改名，避免一直跳过注入。
                        var updated = ext
                        for (known in PodProfiles.ALL) {
                            if (known.displayName != name) {
                                updated = updated.replace(known.displayName, name)
                            }
                        }
                        if (updated != ext) {
                            result.javaClass.getMethod("setExt", String::class.java)
                                .invoke(result, updated)
                            Log.i(TAG, "设备列表机型名已更新为 $name")
                        } else {
                            Log.i(TAG, "设备列表已含 $name，跳过注入")
                        }
                        return@runCatching
                    }
                    if (ext.contains(name)) {
                        Log.i(TAG, "设备列表已含 $name，跳过注入")
                        return@runCatching
                    }
                    val trimmed = ext.trim()
                    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                        Log.w(TAG, "ext 非数组，跳过: ${trimmed.take(80)}")
                        return@runCatching
                    }
                    // FIX-003 任务1：把上游返回的 ext JSON 全量打出来（与本机真实设备 diff 用）
                    if (isVerboseDump()) Log.i(TAG, "DUMP ext=${ext.take(4000)}")
                    // hnOnLineTypeList 给空数组（Gson 空数组不校验元素类型，安全）
                    val mac = ourMac()
                    val prod = ourProd()
                    val our = "{\"deviceId\":\"$mac\"," +
                            "\"deviceName\":\"${ourName()}\"," +
                            "\"hnDeviceType\":\"Third_EarPhone\"," +
                            "\"deviceNodeId\":\"$mac\"," +
                            "\"macAddress\":\"$mac\"," +
                            "\"prodId\":\"$prod\"," +
                            "\"isSelfDevice\":false," +
                            "\"hnOnLineTypeList\":[]}"
                    val merged = if (trimmed.length <= 2) "[$our]"
                                 else trimmed.dropLast(1) + ",$our]"
                    result.javaClass.getMethod("setExt", String::class.java)
                        .invoke(result, merged)
                    Log.w(TAG, "★ 设备列表已注入 ${ourName()} (Third_EarPhone)")
                }.onFailure {
                    Log.w(TAG, "getDeviceList 注入失败: ${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }
            Log.i(TAG, "installed: ControlCenterProvider.getDeviceList [设备注入]")
        }.onFailure {
            Log.w(TAG, "install getDeviceList failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 5) 球列表注入：设备中心（NewControlHomeActivity/控制中心）用的是
        //    DeviceBallManager 的球体系，不走 getDeviceList（实测 hook 装上但零触发）。
        //    在 getAllDeviceInfo/getDeviceInfoList 返回前 append 我们的 DeviceInfo，
        //    batteryData 保持 null → fromBatteryData 注入 hook 兜底点亮按钮。
        runCatching {
            val mgrCls = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
            val infoCls = findClass("com.hihonor.controlviewnew.info.DeviceInfo")
            val bdCls = findClass("com.hihonor.controlviewnew.info.BatteryData")   // 双耳电量
            val typeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
            val thirdEar = typeCls.enumConstants?.firstOrNull { it.toString() == "Third_EarPhone" }
                ?: typeCls.enumConstants?.firstOrNull()
            val ctor = infoCls.declaredConstructors.firstOrNull {
                it.parameterCount == 7 && it.parameterTypes[0] == String::class.java
            }
            if (thirdEar == null || ctor == null) throw IllegalStateException("DeviceInfo 构造/类型不可用")
            ctor.isAccessible = true

            fun buildOur(): Any? = runCatching {
                // ★ FIX-003 根因：7 参构造签名是
                //   (id, name, type, shapeFeature, isSelf, isOnline, isShow)
                //   上游把 isSelf 传成了 true → SortUtils.DeviceSortUtils() 过滤 !isSelf()，
                //   我们的条目进不了 gridIndexMap → createDeviceBall 第一道门直接 return null
                //   （真机日志 14 次 NULL 的唯一原因）。这里必须传 false。
                // 在线态跟随真实连接状态（离线时球变灰，不是消失）
                val onlineNow = isBtConnectedNow()
                // 顺手把桥值校准，避免「蓝牙已断但 magicorig_connected 还是 1」导致卡片一直亮
                runCatching {
                    currentApp()?.let {
                        android.provider.Settings.Global.putString(
                            it.contentResolver, "magicorig_connected", if (onlineNow) "1" else "0"
                        )
                    }
                }
                diag("online", "live=$onlineNow bridge=${isOurConnected()}")
                val info = ctor.newInstance(ourMac(), ourName(), thirdEar, 0, false, onlineNow, true)
                // 7参构造不含 prodId/btMacAddress —— 球渲染按 prodId 查图标资源，
                // null 会静默跳过渲染（列表有数据、UI 不显示的根因）
                runCatching { infoCls.getMethod("setProdId", String::class.java).invoke(info, ourProd()) }
                runCatching { infoCls.getMethod("setBtMacAddress", String::class.java).invoke(info, ourMac()) }
                runCatching { infoCls.getMethod("setName", String::class.java).invoke(info, ourName()) }
                runCatching { infoCls.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(info, 1) }
                // createDeviceBall 第二道门是 deviceInfo.isShow()，显式置真，不依赖构造参数语义
                runCatching {
                    infoCls.getMethod("setIsShow", Boolean::class.javaPrimitiveType).invoke(info, true)
                }
                // 双耳电量注入（列表构建路径）
                feedDualBattery(info)
                info
            }.getOrNull()

            var injectedCount = 0
            // getAllDeviceInfo/getDeviceInfoList=全量，getShowRemoteDeviceInfoList=UI 展示过滤出口
            for (mName in listOf("getAllDeviceInfo", "getDeviceInfoList", "getShowRemoteDeviceInfoList")) {
                val m = mgrCls.declaredMethods.firstOrNull { it.name == mName } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val result = chain.proceed() as? java.util.List<*>
                    // FIX-003 任务1：dump 列表里每个 DeviceInfo 的全字段（含本机真实设备），用于 diff
                    result?.let { dumpDeviceInfoList(mName, it) }
                    if (result != null) {
                        val exists = result.any {
                            runCatching { isOurMac(it?.javaClass?.getMethod("getId")?.invoke(it)?.toString()) }.getOrDefault(false)
                        }
                        // ★ FIX-005：曾经「仅在线时注入」→ 断开后球直接消失（用户反馈）。
                        //   现在改为**只要知道 MAC 就注入**，在线/离线由 DeviceInfo.isOnline 表达，
                        //   这样断开后球会变灰留存，而不是凭空消失。
                        if (!exists) {
                            buildOur()?.let { info ->
                                // List<*> 为只读视图，反射调 add；视图可能不可 add（unmodifiable）→ 全程保护
                                runCatching {
                                    result.javaClass.getMethod("add", Any::class.java)
                                        .invoke(result, info)
                                    injectedCount++
                                    Log.w(TAG, "★ 球列表已注入 ${ourName()} → $mName (总数=${result.size})")
                                    diag("list", "$mName size=${result.size} injected=$injectedCount")
                                    dumpFields("injected[$mName]", info)
                                }.onFailure {
                                    Log.w(TAG, "$mName add 失败(视图只读?): ${it.javaClass.simpleName}")
                                }
                            }
                        }
                    }
                    result
                }
                Log.i(TAG, "installed: DeviceBallManager.$mName [球列表注入]")
            }
            // 空态由数量方法决定（"未发现其他设备"）：原值0且我们已注入球 → 至少1
            mgrCls.declaredMethods.firstOrNull { it.name == "getShowRemoteDeviceNum" }?.let { m ->
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val n = chain.proceed() as? Int ?: 0
                    val fixed = if (n <= 0 && isOurConnected()) 1 else n
                    if (fixed != n) Log.w(TAG, "★ getShowRemoteDeviceNum $n → $fixed")
                    fixed
                }
                Log.i(TAG, "installed: DeviceBallManager.getShowRemoteDeviceNum [数量修正]")
            }
            // ★ 根上修：DeviceBallManager.deviceInfoMap 无我们的设备 → getDeviceInfoById 返 null
            //   → updateDeviceOnlineStatus 空转 / createEarphoneNoiseSection 拿不到 batteryData /
            //   createDeviceServiceCard 电量查询断链。统一在查询出口兜底（缓存单例）。
            var ourInfoCache: Any? = null
            mgrCls.declaredMethods.firstOrNull { it.name == "getDeviceInfoById" }?.let { m ->
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val id = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    if (isOurMac(id?.toString()) && ret == null) {
                        val cached = ourInfoCache ?: buildOur()?.also { ourInfoCache = it }
                        if (cached != null) {
                            Log.w(TAG, "★ getDeviceInfoById(${ourMac()}) null → 兜底 DeviceInfo")
                        }
                        cached
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: DeviceBallManager.getDeviceInfoById [map 兜底]")
            }

            // ── 决定性观察：球创建到哪一步（id / 是否返回 null） ──
            runCatching {
                val lmCls = findClass("com.hihonor.controlviewnew.widget.DeviceBallLayoutManager")
                val cmb = lmCls.declaredMethods.firstOrNull {
                    it.name == "createDeviceBall" && it.parameterCount >= 1
                }
                if (cmb != null) {
                    cmb.isAccessible = true
                    module.hook(cmb).intercept { chain ->
                        val info = chain.args.getOrNull(0)
                        val id = runCatching { info?.javaClass?.getMethod("getId")?.invoke(info) }.getOrNull()
                        // ★ FIX-003 双保险：本机 createDeviceBall 的第一道门是
                        //   gridIndexMap.containsKey(id)；该表由 relayoutRemainingRemoteDeviceBalls()
                        //   用 SortUtils.DeviceSortUtils(getShowRemoteDeviceInfoList()) 重建，
                        //   任何时序/过滤差异都会让我们缺席。这里建球前直接补槽位。
                        if (isOurMac(id?.toString()) && chain.thisObject != null) {
                            runCatching {
                                val f = chain.thisObject!!.javaClass
                                    .getDeclaredField("gridIndexMap").apply { isAccessible = true }
                                @Suppress("UNCHECKED_CAST")
                                val map = f.get(chain.thisObject) as? MutableMap<Any?, Any?>
                                if (map != null && !map.containsKey(id)) {
                                    var idx = runCatching {
                                        info?.javaClass?.getMethod("getIndex")?.invoke(info) as? Int ?: -1
                                    }.getOrDefault(-1)
                                    if (idx < 0) idx = map.size
                                    map[id] = idx
                                    Log.w(TAG, "★ gridIndexMap 补槽位 id=$id → index=$idx (size=${map.size})")
                                }
                            }.onFailure { Log.w(TAG, "补 gridIndexMap 失败: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        val ret = chain.proceed()
                        // 在线态分流（决定电量/降噪 section 走 online 路径）在
                        // BasicDeviceBallView.isOnline()，由 devicemanager 推送驱动，
                        // 第三方耳机无此推送 → 恒 false → 走离线卡（无电量无降噪）。
                        // 球创建成功后直接把 adapter 置在线（setIsOnline 会同步到 ballView）。
                        if (isOurMac(id?.toString()) && ret != null) {
                            runCatching {
                                ret.javaClass.getMethod("setIsOnline", Boolean::class.javaPrimitiveType)
                                    .invoke(ret, isBtConnectedNow())
                                Log.w(TAG, "★ setIsOnline(${isBtConnectedNow()}) on adapter (${ourName()})")
                            }.onFailure { Log.w(TAG, "setIsOnline 失败: ${it.javaClass.simpleName}: ${it.message}") }
                            // 缓存 adapter：响铃高亮/熄灭要用它调 notifyStatusChangeWithSpecialDev
                            ourAdapterRef = ret
                            // ★ 主动驱动在线刷新：updateDeviceOnlineStatus 是 DeviceBallManager
                            //   的公开状态入口（devicemanager 推送也走它），会同步 ballView.isOnline
                            runCatching {
                                val mCls = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                                val inst = mCls.getMethod("getInstance").invoke(null)
                                mCls.getMethod(
                                    "updateDeviceOnlineStatus",
                                    String::class.java,
                                    Boolean::class.javaPrimitiveType
                                ).invoke(inst, ourMac(), isBtConnectedNow())
                                Log.w(TAG, "★ updateDeviceOnlineStatus(${ourMac()}, ${isBtConnectedNow()})")
                            }.onFailure { Log.w(TAG, "updateDeviceOnlineStatus 失败: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        if (isOurMac(id?.toString()) || ret == null) {
                            Log.w(TAG, "▶ createDeviceBall(id=$id) → ${if (ret == null) "NULL(失败)" else "OK"} prodId=${ourProd()}")
                            // FIX-003：球建不出来时，把传进来的 DeviceInfo 全字段打出来（供与本机真实设备 diff）
                            if (isOurMac(id?.toString())) {
                                diag("ball", "id=$id ok=${ret != null} prod=${ourProd()}")
                            }
                            if (isOurMac(id?.toString()) && (ret == null || isVerboseDump())) {
                                dumpFields("createDeviceBall.args[0]", info)
                                runCatching { ret?.let { dumpFields("createDeviceBall.ret", it) } }
                            }
                        }
                        ret
                    }
                    Log.i(TAG, "installed: DeviceBallLayoutManager.createDeviceBall [球创建观察+在线态]")
                } else {
                    Log.w(TAG, "createDeviceBall 未找到")
                }
            }.onFailure { Log.w(TAG, "createDeviceBall 观察失败: ${it.javaClass.simpleName}") }

            // ── 6) 布局修正（关键门③）：
            //    createSections 里 {电量,降噪} section 的前置条件是
            //    DeviceLayoutConfig.hasSection(...)，即 selectLayout(type).layoutMask 含位。
            //    REMOTE_EARPHONE 的 mask 明确含 EARPHONE_BATTERY+EARPHONE_NOISE（clinit or 运算），
            //    但 selectLayout 的 switch 可能把 Third_EarPhone 落到 default → REMOTE_PHONE
            //    （与"卡片只有响铃/连接、无电量无降噪"实拍吻合）→ 强制归位。
            runCatching {
                val cfgCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceLayoutConfig")
                val sel = cfgCls.declaredMethods.first {
                    it.name == "selectLayout" && it.parameterCount >= 1
                }.apply { isAccessible = true }
                val remoteEar = cfgCls.enumConstants?.firstOrNull { it.toString() == "REMOTE_EARPHONE" }
                    ?: throw IllegalStateException("REMOTE_EARPHONE 枚举不存在")
                module.hook(sel).intercept { chain ->
                    val typeName = chain.args.getOrNull(0)?.toString()
                    val ret = chain.proceed()
                    if (typeName == "Third_EarPhone" && ret !== remoteEar) {
                        Log.w(TAG, "★ selectLayout(Third_EarPhone) $ret → REMOTE_EARPHONE")
                        remoteEar
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: DeviceLayoutConfig.selectLayout [布局修正→REMOTE_EARPHONE]")
            }.onFailure {
                Log.w(TAG, "selectLayout hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 7) 决定性观察：降噪 section 创建（区分"门③拒绝"vs"创建返回null"） ──
            runCatching {
                val mgr2 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val mNoise = mgr2.declaredMethods.filter { it.name == "createEarphoneNoiseSection" }
                val mBat = mgr2.declaredMethods.filter { it.name == "createEarphoneBatterySection" }
                for (m in mNoise + mBat) {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val dev = chain.args.getOrNull(0)
                        val devId = runCatching { dev?.javaClass?.getMethod("getDeviceId")?.invoke(dev) }.getOrNull()
                        val ret = chain.proceed()
                        Log.w(TAG, "▶ ${m.name}(deviceId=$devId) → ${if (ret == null) "null" else "SEC(" + ret.hashCode() + ")"}")
                        if (devId == null || isOurMac(devId.toString())) {
                            diag("section", "${m.name} dev=$devId ret=${ret != null}")
                        }
                        ret
                    }
                }
                Log.i(TAG, "installed: createEarphoneNoise/BatterySection [section 创建观察]")
            }.onFailure {
                Log.w(TAG, "section 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 7b) createSections 观察：config 布局 + 返回装配数（定位 sections=null 环节） ──
            runCatching {
                val mgr4 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val cs = mgr4.declaredMethods.first {
                    it.name == "createSections" && it.parameterCount >= 3
                }.apply { isAccessible = true }
                module.hook(cs).intercept { chain ->
                    val config = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    val size = (ret as? List<*>)?.size
                    Log.w(TAG, "▶ createSections(config=$config) → size=$size")
                    ret
                }
                Log.i(TAG, "installed: DeviceServiceCardDataMgr.createSections [装配观察]")
            }.onFailure {
                Log.w(TAG, "createSections 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 9) ★ 最后一环：genDeviceServiceInfo 开头 c2.s.o(deviceId) 查 LinkDevice，
            //    查不到 → log("linkDevice is null") → return null → createSections 永不执行
            //    （sections=null 根因）。在 c2/s 注册表查询出口兜底注入 LinkDevice。
            runCatching {
                val sCls = findClass("c2.s")
                val o = sCls.declaredMethods.first {
                    it.name == "o" && it.parameterCount == 1 &&
                            it.returnType.name.endsWith("LinkDevice")
                }.apply { isAccessible = true }
                val ldCls = findClass("com.hihonor.controlcenter_aar.bean.LinkDevice")
                val typeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
                val third = typeCls.enumConstants?.firstOrNull { it.toString() == "Third_EarPhone" }
                    ?: throw IllegalStateException("Third_EarPhone 枚举不存在")
                val ctor = ldCls.declaredConstructors.firstOrNull {
                    it.parameterCount == 7 && it.parameterTypes[0] == String::class.java
                }?.apply { isAccessible = true }
                    ?: throw IllegalStateException("LinkDevice 7参构造不存在")
                var ldCache: Any? = null
                module.hook(o).intercept { chain ->
                    val id = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    if (isOurMac(id?.toString()) && ret == null) {
                        val ld = ldCache ?: runCatching {
                            // (udid, name, type, nodeId, isLocal, onlineList, prodId)
                            ctor.newInstance(
                                ourMac(), ourName(), third, ourMac(),
                                false, java.util.ArrayList<Any>(), OUR_PROD
                            )
                        }.getOrNull()?.also { ldCache = it }
                        if (ld != null) Log.w(TAG, "★ c2.s.o(${ourMac()}) null → 注入 LinkDevice")
                        ld
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: c2.s.o [LinkDevice 注册表兜底]")
            }.onFailure {
                // FIX-003 任务3.1：本机构建里 c2.s 已换名，这条 hook 只是「注册表兜底」，
                // 而 sections=5 的链路在本机已实证能走通 → 降为 debug 日志，不再刷噪音。
                Log.d(TAG, "c2.s.o hook 跳过（本机构建类名不同）: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11a) ★ FIX-005：连接动作文案 —— 本机用 BLE 连接态判断，经典蓝牙恒 false ──
            //   DeviceServiceCardDataMgr:335 用
            //     AudioUtils.getInstance().isDeviceBleConnected(btMac) ? 断开连接 : 去连接
            //   Free4 走经典蓝牙，BLE 查询永远 false → 连着也显示「去连接」。
            //   这里对该方法做匹配替换：是我们且已连接 → true（显示「断开连接」）。
            runCatching {
                val audioCls = findClass("com.hihonor.controlviewnew.utils.AudioUtils")
                val bleConnected = audioCls.declaredMethods.first {
                    it.name == "isDeviceBleConnected" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(bleConnected).intercept { chain ->
                    val mac = chain.args.getOrNull(0)?.toString()
                    if (isOurMac(mac) && isOurConnected()) {
                        diag("blehook", "isDeviceBleConnected($mac) → true（经典蓝牙伪装）")
                        true
                    } else chain.proceed()
                }
                Log.i(TAG, "installed: AudioUtils.isDeviceBleConnected [去连接/断开连接]")
                diag("hook_ble", "installed")
            }.onFailure {
                Log.w(TAG, "isDeviceBleConnected hook 失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("hook_ble", "fail: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11c) ★ FIX-005：让「响铃查找」可点 ──
            //   DeviceServiceCardAdapter.updateEarphoneUIByOnlineType(boolean) 会按在线态
            //   把 BID_FIND_IOT_DEVICE 置为 DISABLED 且 setClickable(false)。
            //   耳机是通过 SPP 在线的，这套在线态判断链在本机恒 false，故强制放行。
            runCatching {
                // BasicDeviceBallView.updateEarphoneUIByOnlineType(z) 一处驱动两件事：
                //   adapter.updateEarphoneUIByOnlineType(z)  → 响铃查找 NORMAL/DISABLED
                //   adapter.updateConnectAction(container, z) → 「断开连接 / 去连接」文案
                // 本机这套在线判断恒 false（耳机是 SPP 在线、蓝牙栈视角不同），故用真实状态替换入参。
                val ballCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val upd = ballCls.declaredMethods.first {
                    it.name == "updateEarphoneUIByOnlineType" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(upd).intercept { chain ->
                    val real = isOurConnected()
                    chain.args[0] = real
                    diag("finddevice", "updateEarphoneUIByOnlineType($real)")
                    chain.proceed()
                }
                Log.i(TAG, "installed: BasicDeviceBallView.updateEarphoneUIByOnlineType [响铃查找+连接文案]")
                diag("hook_finddevice", "installed")
            }.onFailure {
                Log.w(TAG, "响铃查找 hook 失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("hook_finddevice", "fail: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11d) ★ FIX-005：「更多设置」点了没反应 —— 自己拉起蓝牙详情页 ──
            //   jumpToMoreSettings: DeviceBallManager.getDeviceInfoById(id) 为 null 时只打日志不 return，
            //   紧接着就调 deviceInfoById.getDeviceType() → NPE，点击被吞掉。
            //   直接拦截：是我们的设备就发 Honor 蓝牙详情页 intent。
            runCatching {
                val mgrCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val jump = mgrCls.declaredMethods.first {
                    it.name == "jumpToMoreSettings" && it.parameterCount == 2 &&
                        it.parameterTypes[1] == String::class.java
                }.apply { isAccessible = true }
                module.hook(jump).intercept { chain ->
                    val ctx = chain.args.getOrNull(0) as? android.content.Context
                    val id = chain.args.getOrNull(1)?.toString()
                    if (ctx != null && isOurMac(id)) {
                        runCatching {
                            // 优先打开 QuickBuds（用户偏好：更多设置 = 完整的耳机控制界面）；
                            // 未安装时回落到 Honor 蓝牙详情页。
                            // 打开本模块自带的耳机详情页：不依赖 QuickBuds / 系统页面，
                            // 数据走同一个 Settings.Global 桥 + 同一条 ACTION_ANC_SELECT 下发通道。
                            // 注意：ctx 是 controlcenter 的 Context，setClassName(ctx, ...) 会拼成
                            //   com.hihonor.controlcenter/io.github... → ActivityNotFound。
                            //   必须显式给出包名。
                            // 优先 QuickBuds（功能最全）。注意：controlcenter 受包可见性限制，
                            // getLaunchIntentForPackage 会返回 null，所以这里用显式组件 + 失败回落。
                            val qbIntent = android.content.Intent().setClassName(
                                "com.spizganed.quickbuds",
                                "com.spizganed.quickbuds.ui.MainActivity"
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            val startedQb = runCatching { ctx.startActivity(qbIntent); true }.getOrDefault(false)
                            if (!startedQb) {
                                ctx.startActivity(
                                    android.content.Intent()
                                        .setClassName(
                                            "io.github.yuxiximyxx.magicfree4",
                                            "io.github.yuxiximyxx.magicfree4.EarphoneDetailActivity"
                                        )
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            diag("more", (if (startedQb) "已跳转 QuickBuds" else "已跳转模块详情页") + " id=$id")
                        }.onFailure { diag("more", "跳转失败: ${it.javaClass.simpleName}: ${it.message}") }
                        null
                    } else chain.proceed()
                }
                Log.i(TAG, "installed: jumpToMoreSettings [更多设置兜底]")
            }.onFailure {
                Log.w(TAG, "更多设置 hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11g) ★ FIX-008：在 DeviceServiceCardDataMgr 层接管动作 ──
            //   AudioUtils 那一层 hook 实测不生效（点击根本没走到），而本类的其它 hook
            //   （genDeviceServiceInfo / jumpToMoreSettings）都稳定生效 → 就在这层接管。
            runCatching {
                val dmgr = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")

                // 断开
                dmgr.declaredMethods.firstOrNull {
                    it.name == "toDisConnectDev" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }?.let { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val id = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(id)) {
                            btA2dpAction(id!!, connect = false)
                            diag("act", "disconnect id=$id")
                            "disconnect_device"
                        } else chain.proceed()
                    }
                    Log.i(TAG, "installed: toDisConnectDev [断开]")
                }

                // 重连（离线卡的「去连接」）
                dmgr.declaredMethods.firstOrNull {
                    it.name == "toConnectOfflineDev" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }?.let { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val id = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(id)) {
                            btA2dpAction(id!!, connect = true)
                            diag("act", "connect id=$id")
                            "to_connect_device"
                        } else chain.proceed()
                    }
                    Log.i(TAG, "installed: toConnectOfflineDev [连接]")
                }

                // 响铃查找
                dmgr.declaredMethods.firstOrNull {
                    it.name == "findDevice" && it.parameterCount == 3 &&
                        it.parameterTypes[1] == String::class.java
                }?.let { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val id = chain.args.getOrNull(1)?.toString()
                        if (isOurMac(id)) {
                            // ★ FIX-010：做成开关 —— 第一次点开始响并高亮，再点停止并熄灭
                            ringOn = !ringOn
                            requestFindBuds(ringOn)
                            highlightFind(id, chain.thisObject, dmgr, ringOn)
                            diag("act", "find id=$id on=$ringOn")
                            ""
                        } else chain.proceed()
                    }
                    Log.i(TAG, "installed: findDevice [响铃开关]")
                }

                // 延迟写一条安装回执：hook 安装发生在 Application 之前，此刻 diag 写不出去
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    diag("hook_mgr", "installed（disconnect/connect/find 已接管）")
                }, 10_000L)
            }.onFailure {
                Log.w(TAG, "manager 动作接管失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11f) ★ FIX-007：接管三个真实动作 ──
            //   响铃查找：荣耀是「把手机音频路由到耳机」，虚拟设备没路由 → 改为发欢律 0x0400
            //   断开/重连：自己驱动 A2DP+HFP，绕开对本机设备表/回调链的依赖
            runCatching {
                val audioCls2 = findClass("com.hihonor.controlviewnew.utils.AudioUtils")

                val startFind = audioCls2.declaredMethods.firstOrNull {
                    it.name == "startFindDevice" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (startFind != null) {
                    startFind.isAccessible = true
                    module.hook(startFind).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { requestFindBuds(true); true } else chain.proceed()
                    }
                }

                val stopFind = audioCls2.declaredMethods.firstOrNull {
                    it.name == "stopFindDevice" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (stopFind != null) {
                    stopFind.isAccessible = true
                    module.hook(stopFind).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { requestFindBuds(false); null } else chain.proceed()
                    }
                }

                val disc = audioCls2.declaredMethods.firstOrNull {
                    it.name == "disconnect" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (disc != null) {
                    disc.isAccessible = true
                    module.hook(disc).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { btA2dpAction(mac!!, connect = false); null } else chain.proceed()
                    }
                }

                val recon = audioCls2.declaredMethods.firstOrNull {
                    it.name == "reconnect" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (recon != null) {
                    recon.isAccessible = true
                    module.hook(recon).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { btA2dpAction(mac!!, connect = true); null } else chain.proceed()
                    }
                }
                Log.i(TAG, "installed: AudioUtils start/stopFindDevice + disconnect/reconnect [实动作接管]")
            }.onFailure {
                Log.w(TAG, "AudioUtils 动作接管失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11e) ★ FIX-005：「透传」→「通透」──
            //   文案来自 R.string.earphone_noise_control_awareness，在 initModeButton 里 setText(int)。
            //   initModeButton(View, int, int) 执行后，把按钮文字改掉即可。
            runCatching {
                val ctlCls2 = findClass("com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseSectionController")
                val initBtn = ctlCls2.declaredMethods.first {
                    it.name == "initModeButton" && it.parameterCount == 3
                }.apply { isAccessible = true }
                module.hook(initBtn).intercept { chain ->
                    val ret = chain.proceed()
                    runCatching {
                        val v = chain.args.getOrNull(0) as? android.view.View
                        val tv = v?.findViewById(
                            v.resources.getIdentifier("noise_mode_text", "id", "com.hihonor.controlcenter")
                        ) as? android.widget.TextView
                        if (tv != null && tv.text?.toString() == "透传") {
                            tv.text = "通透"
                            diag("label", "透传 → 通透")
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: initModeButton [透传→通透]")
            }.onFailure {
                Log.w(TAG, "透传改名 hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11b) ★ FIX-004：本机构建的真实注册表链 ──
            //   反编译实证：genDeviceServiceInfo 第一行是
            //     LinkDevice t8 = b2.v.s().t(deviceId);
            //     if (t8 == null) { log("linkDevice is null"); return null; }
            //   而 b2.v.s().t(id) 的实现就是
            //     DeviceProductProfile.getInstance().getDeviceByUdid(id)
            //   后者只是一次 mOnLineLinkDevicesMap.get(id)。
            //   → LinkDevice 不在表里，服务卡就永远返回 null（「暂无服务可流转」的真因）。
            //   这里把伪造的 LinkDevice 在查询出口塞进它的在线表。
            runCatching {
                val dppCls = findClass("com.hihonor.controlcenter.superdevice.deviceproduct.DeviceProductProfile")
                val getById = dppCls.declaredMethods.first {
                    it.name == "getDeviceByUdid" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }.apply { isAccessible = true }
                val mapField = dppCls.getDeclaredField("mOnLineLinkDevicesMap").apply { isAccessible = true }

                val ldCls = findClass("com.hihonor.controlcenter_aar.bean.LinkDevice")
                val prodTypeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
                val thirdEarLd = prodTypeCls.enumConstants?.firstOrNull { it.toString() == "Third_EarPhone" }
                    ?: prodTypeCls.enumConstants?.firstOrNull()
                // LinkDevice(String deviceId, String deviceName, HnDeviceProductType type,
                //            String deviceNodeId, boolean isSelfDevice, List<HnOnlineType>, String prodId)
                val ldCtor = ldCls.declaredConstructors.firstOrNull {
                    it.parameterCount == 7 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[2].isEnum &&
                        it.parameterTypes[4] == Boolean::class.javaPrimitiveType
                }?.apply { isAccessible = true }
                if (thirdEarLd == null || ldCtor == null) throw IllegalStateException("LinkDevice 构造签名不匹配")

                fun buildLinkDevice(mac: String): Any? = runCatching {
                    ldCtor.newInstance(mac, ourName(), thirdEarLd, mac, false, java.util.ArrayList<Any>(), ourProd())
                }.getOrNull()

                module.hook(getById).intercept { chain ->
                    val id = chain.args.getOrNull(0)?.toString()
                    val ret = chain.proceed()
                    if (ret == null && isOurMac(id)) {
                        val mac = id ?: return@intercept ret
                        val built = buildLinkDevice(mac)
                        if (built == null) {
                            diag("linkdevice", "构造失败 id=$mac")
                            ret
                        } else {
                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                val map = mapField.get(chain.thisObject) as? MutableMap<Any?, Any?>
                                val before = map?.size ?: -1
                                map?.put(mac, built)
                                diag("linkdevice", "已注入 id=$mac mapSize=$before→${map?.size}")
                            }.onFailure { diag("linkdevice", "塞表失败: ${it.javaClass.simpleName}") }
                            Log.w(TAG, "★ LinkDevice 已注入 DeviceProductProfile id=$mac")
                            built
                        }
                    } else ret
                }
                Log.i(TAG, "installed: DeviceProductProfile.getDeviceByUdid [LinkDevice 注册表]")
            }.onFailure {
                Log.w(TAG, "LinkDevice 注入安装失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("linkdevice", "安装失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 9b) 任务：双耳电量 —— getDeviceInfoById 出口补喂 ──
            //    球列表常被持久化缓存（buildOur 只在首建跑一次），而电量 section 的数据源
            //    正是 DeviceBallManager.getDeviceInfoById(devId).getBatteryData() ——
            //    在此出口对我们的设备补喂真实 L/R，每次取数都拿到新鲜电量。
            runCatching {
                val m = mgrCls.declaredMethods.first {
                    it.name == "getDeviceInfoById" && it.parameterCount == 1
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    if (isOurMac(chain.args.getOrNull(0)?.toString()) && ret != null) {
                        feedDualBattery(ret)
                    }
                    ret
                }
                Log.i(TAG, "installed: getDeviceInfoById [双耳电量出口补喂]")
            }.onFailure {
                Log.w(TAG, "getDeviceInfoById hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 10) ★ 在线态断根：createDeviceServiceCard 每次开卡都读 ballView.isOnline()
            //    决定走在线卡（gen 5-section 含噪声控制）还是离线卡（只有响铃/连接）。
            //    我们在球创建时设的 true 会被荣耀属性监听（onPropertyInfoChange）重置回 false
            //    → 卡片时而在线时而离线。直接在读取口对我们的球恒返回 true。
            runCatching {
                val bvCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val m = bvCls.getDeclaredMethods().first {
                    it.name == "isOnline" && it.parameterCount == 0 &&
                            it.returnType == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed() as? Boolean ?: false
                    if (!ret) {
                        val receiver = chain.thisObject
                        val id = runCatching {
                            receiver?.javaClass?.getMethod("getDeviceId")?.invoke(receiver)
                        }.getOrNull()
                        if (isOurMac(id?.toString())) {
                            Log.w(TAG, "★ ballView.isOnline() false → true (${ourName()})")
                            return@intercept true
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.isOnline [在线态断根]")
            }.onFailure {
                Log.w(TAG, "isOnline hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11) 任务2：卡片电量 —— getBatteryLevel 对我们的球返回跨进程真实电量 ──
            //    （默认数据链 queryBatteryInfo 对第三方设备无回调 → 卡片无电量）
            runCatching {
                val bvCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val m = bvCls.getDeclaredMethods().first {
                    it.name == "getBatteryLevel" && it.parameterCount == 0 &&
                            it.returnType == Int::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed() as? Int ?: -1
                    val receiver = chain.thisObject
                    val id = runCatching {
                        receiver?.javaClass?.getMethod("getDeviceId")?.invoke(receiver)
                    }.getOrNull()
                    if (isOurMac(id?.toString())) {
                        val bat = batteryLevelForCard()
                        if (bat != null && bat != ret) {
                            Log.w(TAG, "★ ballView.getBatteryLevel $ret → $bat (${ourName()})")
                            return@intercept bat
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.getBatteryLevel [电量显示]")
            }.onFailure {
                Log.w(TAG, "getBatteryLevel hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 8) 全景观察：genDeviceServiceInfo 返回的 sections 构成（一次看清全卡） ──
            runCatching {
                val mgr3 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val gen = mgr3.declaredMethods.first {
                    it.name == "genDeviceServiceInfo" && it.parameterCount >= 2
                }.apply { isAccessible = true }
                module.hook(gen).intercept { chain ->
                    val ret = chain.proceed()
                    runCatching {
                        val secs = ret?.javaClass?.getMethod("getServiceSections")?.invoke(ret) as? List<*>
                        val types = secs?.joinToString(",") {
                            it?.javaClass?.simpleName?.take(28) ?: "null"
                        } ?: "?"
                        val devId = chain.args.getOrNull(0)
                        Log.w(TAG, "▶ genDeviceServiceInfo(dev=$devId) sections=${secs?.size} [$types]")
                        diag("sections", "dev=$devId n=${secs?.size} types=$types")
                        // 排障：把每个 section 的字段打出来，用来决定「离线时该删哪几段」
                        if (isOurMac(devId?.toString())) {
                            runCatching {
                                val sb = StringBuilder()
                                secs?.forEachIndexed { i, sec ->
                                    sb.append("#").append(i).append(' ')
                                    sb.append(sec?.javaClass?.simpleName ?: "null")
                                    sec?.javaClass?.declaredFields?.forEach { f ->
                                        runCatching {
                                            f.isAccessible = true
                                            val v = f.get(sec)?.toString()?.take(40)
                                            sb.append(' ').append(f.name).append('=').append(v)
                                        }
                                    }
                                    sb.append(" || ")
                                }
                                diag("sec_detail", sb.toString().take(560))
                            }.onFailure { diag("sec_detail", "fail: ${it.message}") }
                        }
                        // ★ 任务2：上游打桩 —— 不依赖 createDeviceServiceCard 的电量段分支
                        //   （该段受 isOnline/info查询等多重条件控制，实测从未走到 007d）。
                        //   gen 是在线卡数据的必经点，直接把跨进程电量写进 info。
                        if (isOurMac(devId?.toString()) && ret != null) {
                            // ★ 断连时在**数据层**直接砍掉电量段与噪声段。
                            //   只在视图层隐藏的话，卡片仍会先按完整高度展开一次再缩回去（实测有抖动），
                            //   从 sections 里删掉，卡片一开始就是矮的，根本不会出现「先大后小」。
                            trimOfflineSections(ret, isOurConnected())
                            // ★ FIX-006：数据级修正（连接文案 + 响铃查找可用）
                            postFixSections(ret, isOurConnected())
                            val bat = batteryLevelForCard()
                            if (bat != null) {
                                runCatching {
                                    ret.javaClass.getMethod("setBatteryLevel", Int::class.javaPrimitiveType)
                                        .apply { isAccessible = true }.invoke(ret, bat)
                                    Log.w(TAG, "★★ genDeviceServiceInfo.setBatteryLevel($bat) (${ourName()})")
                                }.onFailure { Log.w(TAG, "setBatteryLevel 失败: ${it.javaClass.simpleName}") }
                            }
                        }
                    }.onFailure { Log.w(TAG, "gen 读取失败: ${it.javaClass.simpleName}") }
                    ret
                }
                Log.i(TAG, "installed: genDeviceServiceInfo [全景观察]")
            }.onFailure {
                Log.w(TAG, "gen 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }.onFailure {
            Log.w(TAG, "install DeviceBallManager 注入失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 12) 任务2诊断：createDeviceServiceCard 的电量段（007d getBatteryLevel）
        //    实测零触发 —— 打出 ballView 的真实 deviceId（断根/电量 hook 都按
        //    ourMac() 匹配，若 id 不匹配则两个 hook 全部空转）与 isOnline 实况。
        // ★ FIX-002：这个观察钩子预期在 controlcenter 进程运行；
        //   SystemUI 进程里没有 DeviceServiceCardDataMgr，findClass 会抛 CNFE 并打成 W 日志（噪音）。
        //   先把类查出来，查不到就静默跳过，不再制造误导性的告警。
        val cardMgrCls = runCatching {
            findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
        }.getOrNull()
        if (cardMgrCls == null) {
            Log.d(TAG, "createDeviceServiceCard: 宿主无此类（非 controlcenter 进程），跳过观察")
        } else runCatching {
            val mgr = cardMgrCls
            val m = mgr.declaredMethods.first {
                it.name == "createDeviceServiceCard" && it.parameterCount == 5
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val ball = chain.args.getOrNull(1)
                val id = runCatching { ball?.javaClass?.getMethod("getDeviceId")?.invoke(ball) }.getOrNull()
                val online = runCatching { ball?.javaClass?.getMethod("isOnline")?.invoke(ball) }.getOrNull()
                val bid = runCatching { ball?.javaClass?.getMethod("getId")?.invoke(ball) }.getOrNull()
                Log.w(TAG, "▶ createDeviceCard ball.id=$id getId=$bid isOnline=$online (匹配OUR=${isOurMac(id?.toString())} mac=${ourMac()})")
                // ★ bug修复：进入设备中心（开卡）时向 bluetooth 发**只读查询**（ACTION_REFRESH_STATUS
                //   → queryStatus() → QUERY_ANC 查询帧，不是调模式包）→ 耳机回当前模式 →
                //   changeUIAncStatus 写 magicorig_current_anc → 卡片显示**耳机本体真实模式**。
                if (isOurMac(id?.toString())) {
                    val nowQ = System.currentTimeMillis()
                    if (nowQ - lastAncQueryAt > 5000) {
                        lastAncQueryAt = nowQ
                        runCatching {
                            val app = Class.forName("android.app.ActivityThread")
                                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                            app?.sendBroadcast(Intent("io.github.yuxiximyxx.magicfree4.ACTION_REFRESH_STATUS"))
                            Log.w(TAG, "→→ 已发只读查询 ACTION_REFRESH_STATUS（不发调模式包）")
                        }.onFailure { Log.w(TAG, "查询广播失败: ${it.javaClass.simpleName}") }
                    }
                }
                val cardRet = chain.proceed()
                if (isOurMac(id?.toString())) {
                    diag("card", "id=$id online=$online ok=${cardRet != null} retCls=${cardRet?.javaClass?.simpleName}")
                    val cardView = cardRet as? android.view.View
                    // 只处理我们自己的卡片（场上还有 WH-1000XM4 / 华为的卡片）
                    // 注意：不要在这里用「卡片里有没有我们的设备名」做判定 ——
                    // createDeviceServiceCard 返回那一刻标题还没绑定文字，判定必然失败（实测就是这么漏的）。
                    // 外层已经用 isOurMac(id) 确认过是我们的设备，这里直接处理即可。
                    if (cardView != null) {
                        diag("card_view", "cls=${cardView.javaClass.simpleName}")
                        val realOnline = isOurConnected()
                        // 同步先收一次（能收掉就少闪一下），但**不要动 alpha**：
                        // 实测把 alpha 置 0 再恢复会和卡片自身的入场动画打架，整张卡片一直是半透明的。
                        runCatching { applyOfflineVisibility(cardView, realOnline) }
                        // ★ 重试几次：卡片建好那一刻，电量段/噪声段往往还没 inflate 进来，
                        //   同步那一次找不到它们（实测就是这样，卡片一点没变）。
                        listOf(0L, 120L, 300L, 700L, 1400L).forEach { d ->
                            cardView.postDelayed({
                                runCatching { applyOfflineVisibility(cardView, realOnline) }
                            }, d)
                        }
                    }
                }
                cardRet
            }
            Log.i(TAG, "installed: createDeviceServiceCard [电量段诊断]")
        }.onFailure {
            Log.w(TAG, "createDeviceCard 观察失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 13) 任务2 最后保险：DeviceServiceInfo.setBatteryLevel 兜底 ──
        //    电量段若执行且 ballView.getBatteryLevel 断根匹配失败会写入 -1/0，
        //    把 gen 阶段打桩的真实电量覆盖掉 → hasBatteryInfo()=false → 不显示。
        //    这里对 <=0 的写入统一替换为跨进程真实电量（本机正常值 >0 不干预）。
        runCatching {
            val infoCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceInfo")
            val m = infoCls.getDeclaredMethods().first {
                it.name == "setBatteryLevel" && it.parameterCount == 1
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                // ★ 只干预我们设备的 info —— 否则荣耀自家耳机低电(0)时会被
                //   替换成我们的电量（串台显示）
                val did = runCatching {
                    chain.thisObject?.javaClass?.getMethod("getDeviceId")?.invoke(chain.thisObject)
                }.getOrNull()
                if (!isOurMac(did?.toString())) return@intercept chain.proceed()
                val v = chain.args.getOrNull(0) as? Int ?: -1
                val bat = batteryLevelForCard()
                if (v <= 0 && bat != null && bat > 0) {
                    Log.w(TAG, "★★ setBatteryLevel($v) → $bat 兜底 (${ourName()})")
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = bat
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: DeviceServiceInfo.setBatteryLevel [电量兜底]")
        }.onFailure {
            Log.w(TAG, "setBatteryLevel 兜底失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 上游作者本人那副耳机的 MAC，仅作「从未连接过、读不到真实值」时的兜底。 */
    private const val OUR_MAC = "18:5C:A1:52:10:36"
    /** 当前机型的显示名。未识别时是 Free4，和改机型之前写死的字符串相同。 */
    private fun ourName(): String = PodIdentity.current(currentApp()).displayName
    private const val OUR_PROD = "00VVD9"

    // ── 真实设备 MAC（跨进程桥）──────────────────────────────
    // 上游把作者自己那副耳机的 MAC 写死在整条注入链里（26 处引用）。
    // 换一副耳机后，设备中心被注入的其实是一台不存在的「幽灵设备」：
    // 布局给它留了位（用户看到空位），但球渲染不出来，真实耳机也从没被认领。
    // 修复：蓝牙进程连接时把真实地址写进 Settings.Global，本进程按需读取。

    private const val MAC_KEY = "magicorig_mac"

    @Volatile private var cachedMac: String? = null
    @Volatile private var cachedMacAt = 0L
    @Volatile private var warnedNoMac = false

    private fun currentApp(): android.content.Context? = DeviceIdentity.currentApp()

    /** 真实耳机 MAC：读到就用；从未连过（值为空）才回落常量并告警一次。 */
    private fun ourMac(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedMac?.let { if (now - cachedMacAt < 5_000L) return it }
        val stored = runCatching {
            currentApp()?.let {
                android.provider.Settings.Global.getString(it.contentResolver, MAC_KEY)
            }
        }.getOrNull()?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            cachedMac = stored
            cachedMacAt = now
            return stored
        }
        if (!warnedNoMac) {
            warnedNoMac = true
            Log.w(TAG, "$MAC_KEY 为空（本机从未连过耳机），暂用上游常量 $OUR_MAC；连接一次后自动纠正")
        }
        return OUR_MAC
    }

    /**
     * 直接驱动 A2DP / HFP 的连接与断开（FIX-007）。
     *
     * 为什么自己来：荣耀的「断开连接」最终调 AudioUtils.disconnect()，它依赖
     * getProfileProxy 的回调链；而「响铃查找」更狠 —— 它是把手机音频**路由**到耳机上播
     * （MediaRouter2），虚拟设备没有路由，所以点了永远没声音。
     */
    private fun btA2dpAction(mac: String, connect: Boolean) {
        runCatching {
            val ctx = currentApp() ?: return@runCatching
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return@runCatching
            val dev = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return@runCatching
            val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile?) {
                    runCatching {
                        // connect/disconnect 是隐藏 API，编译期不可见 —— 反射调用
                        val m = proxy?.javaClass?.getMethod(
                            if (connect) "connect" else "disconnect",
                            android.bluetooth.BluetoothDevice::class.java
                        )
                        val ret = m?.invoke(proxy, dev)
                        diag("bt_action", "profile=$profile ${if (connect) "connect" else "disconnect"} → $ret")
                    }.onFailure {
                        diag("bt_action", "call fail: ${it.javaClass.simpleName}: ${it.message}")
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            adapter.getProfileProxy(ctx, listener, android.bluetooth.BluetoothProfile.A2DP)
            adapter.getProfileProxy(ctx, listener, android.bluetooth.BluetoothProfile.HEADSET)
            diag("bt_action", "mac=$mac connect=$connect")
            Log.i(TAG, "btA2dpAction mac=$mac connect=$connect")
        }.onFailure { diag("bt_action", "fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 我们的 adapter（球创建时缓存），响铃高亮要用。 */
    @Volatile private var ourAdapterRef: Any? = null

    /** 响铃开关状态。 */
    @Volatile private var ringOn = false

    /**
     * 驱动「响铃查找」按钮的高亮/熄灭。
     * 走 DeviceServiceCardDataMgr.notifyStatusChangeWithSpecialDev(adapter, businessId, isSrc, highlight, tag)。
     */
    private fun highlightFind(deviceId: String?, mgrInstance: Any?, mgrCls: Class<*>?, on: Boolean) {
        if (deviceId == null) return
        runCatching {
            // 球适配器 → 球视图 BasicDeviceBallView → 它自己的 notifyStatusChangeWithSpecialDev
            // （4 参重载内部会解析 DeviceServiceCardAdapter，避免我们传错类型）
            val ballAdapter = ourAdapterRef ?: runCatching {
                val bm = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                val bmi = bm.getMethod("getInstance").invoke(null)
                val info = bm.getMethod("getDeviceInfoById", String::class.java).invoke(bmi, deviceId)
                info?.javaClass?.getMethod("getBasicDeviceBallViewAdapter")?.invoke(info)
            }.getOrNull() ?: return@runCatching

            val ballView = ballAdapter.javaClass
                .getMethod("getBasicDeviceBallView")
                .invoke(ballAdapter) ?: return@runCatching

            val localId = runCatching {
                val bm = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                val bmi = bm.getMethod("getInstance").invoke(null)
                val li = bm.getMethod("getLocalDeviceInfo").invoke(bmi)
                li?.javaClass?.getMethod("getId")?.invoke(li) as? String
            }.getOrNull().orEmpty()

            val m = ballView.javaClass.getMethod(
                "notifyStatusChangeWithSpecialDev",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
            m.invoke(ballView, "DccFindIotDevice", true, on, localId)
            diag("ring", "highlight=$on localId=$localId view=${ballView.javaClass.simpleName}")
        }.onFailure { diag("ring", "highlight fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 让耳机自己响（欢律 0x0400），走蓝牙进程下发。 */
    private fun requestFindBuds(on: Boolean) {
        runCatching {
            val sent = runCatching {
                currentApp()?.sendBroadcast(
                    Intent(MagicOriGAction.ACTION_FIND_BUDS)
                        .putExtra("on", on)
                        .setPackage("com.android.bluetooth")
                )
                true
            }.getOrDefault(false)
            diag("find", "已请求响铃 on=$on sent=$sent")
        }.onFailure { diag("find", "失败: ${it.javaClass.simpleName}") }
    }

    /**
     * 数据级修正服务卡 sections（FIX-006）：
     *  - QuickActionData.serviceId == to_connect_device / disconnect_device → 按真实连接态互换（含文案）
     *  - ServiceCardInfo.bsId 含 Find（响铃查找）→ ShowStatus 置 NORMAL
     * 只改数据、不碰 View，也不依赖上游「在线态通知」方法是否被调用。
     */
    /**
     * 断连时把「电量段」「噪声控制段」从 sections 里删掉。
     *
     * 字段来自真机 dump：`ServiceSection.sectionType` ∈
     *   EARPHONE_BATTERY / EARPHONE_NOISE / IOT_DEV_NORMAL_SERVICE(响铃查找) …
     * 只删前两个；响铃查找保留（原生会自己置灰）。
     */
    private fun trimOfflineSections(info: Any, connected: Boolean) {
        if (connected) return
        runCatching {
            val secs = info.javaClass.getMethod("getServiceSections").invoke(info) as? MutableList<*>
                ?: return
            val first = secs.firstOrNull() ?: return
            val typeField = first.javaClass.getDeclaredField("sectionType").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val mutable = secs as MutableList<Any?>
            val before = mutable.size
            mutable.removeAll { sec ->
                val t = runCatching { typeField.get(sec)?.toString() }.getOrNull()
                t == "EARPHONE_BATTERY" || t == "EARPHONE_NOISE"
            }
            if (mutable.size != before) {
                diag("trim", "离线：sections $before → ${mutable.size}")
                Log.w(TAG, "★ 离线裁剪 sections $before → ${mutable.size} (${ourName()})")
            }
        }.onFailure { Log.w(TAG, "trimOfflineSections 失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun postFixSections(info: Any, connected: Boolean) {
        runCatching {
            val secs = info.javaClass.getMethod("getServiceSections").invoke(info) as? List<*> ?: return
            var changed = 0
            for (sec in secs) {
                if (sec == null) continue
                val data = runCatching {
                    sec.javaClass.getMethod("getSectionDataList").invoke(sec) as? List<*>
                }.getOrNull() ?: continue
                for (d in data) {
                    if (d == null) continue
                    val cls = d.javaClass

                    val sid = runCatching { cls.getMethod("getServiceId").invoke(d) as? String }.getOrNull()
                    if (sid == "to_connect_device" || sid == "disconnect_device") {
                        val want = if (connected) "disconnect_device" else "to_connect_device"
                        val text = if (connected) "断开连接" else "去连接"
                        if (sid != want) {
                            runCatching {
                                cls.declaredMethods.firstOrNull {
                                    it.name == "setServiceId" && it.parameterCount == 1
                                }?.invoke(d, want)
                                cls.declaredMethods.firstOrNull {
                                    it.name == "setTitle" && it.parameterCount == 1
                                }?.invoke(d, text)
                                changed++
                            }
                        }
                    }

                    val bsid = runCatching { cls.getMethod("getBsId").invoke(d) as? String }.getOrNull()
                    if (bsid != null && bsid.contains("Find", ignoreCase = true)) {
                        runCatching {
                            val setter = cls.declaredMethods.firstOrNull {
                                it.name == "setShowStatus" && it.parameterCount == 1
                            } ?: return@runCatching
                            val normal = setter.parameterTypes[0].enumConstants
                                ?.firstOrNull { it.toString() == "NORMAL" }
                            setter.invoke(d, normal)
                            changed++
                            diag("findstatus", "$bsid → NORMAL")
                        }
                    }
                }
            }
            diag("sections_fix", "changed=$changed connected=$connected")
        }.onFailure { diag("sections_fix", "fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 实时连接态：直接问蓝牙栈（A2DP），比跨进程桥更及时可靠。
     * 桥值 [isOurConnected] 只作兜底（例如本进程没有 BLUETOOTH_CONNECT 权限时）。
     */
    private fun isBtConnectedNow(): Boolean = runCatching {
        val ctx = currentApp() ?: return@runCatching isOurConnected()
        val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?: return@runCatching isOurConnected()
        val dev = bm.adapter?.getRemoteDevice(ourMac()) ?: return@runCatching isOurConnected()
        bm.getConnectionState(dev, android.bluetooth.BluetoothProfile.A2DP) ==
            android.bluetooth.BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(isOurConnected())

    /** id 是否就是我们的耳机（大小写不敏感）。 */
    private fun isOurMac(id: String?): Boolean =
        id != null && id.equals(ourMac(), ignoreCase = true)

    // ── prodId 运行期覆盖（FIX-003 任务 2 的 A/B 开关）────────────
    // 本机构建可能没有 00VVD9 的图标/产品配置 → createDeviceBall 建不出球（返回 null）。
    // 允许不改代码直接换 prodId 验证：
    //   adb shell settings put global magicorig_prodid <本机存在的耳机 prodId>
    //   adb shell settings delete global magicorig_prodid     # 还原
    private const val PROD_KEY = "magicorig_prodid"

    @Volatile private var cachedProd: String? = null
    @Volatile private var cachedProdAt = 0L

    private fun ourProd(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedProd?.let { if (now - cachedProdAt < 5_000L) return it }
        val stored = runCatching {
            currentApp()?.let {
                android.provider.Settings.Global.getString(it.contentResolver, PROD_KEY)
            }
        }.getOrNull()?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            cachedProd = stored
            cachedProdAt = now
            return stored
        }
        return OUR_PROD
    }

    // ── 字段 diff 取证（FIX-003 任务 1，临时；定位后收敛）──────────
    //   adb shell settings put global magicorig_debug_dump 1
    private const val DUMP_KEY = "magicorig_debug_dump"

    private fun isVerboseDump(): Boolean =
        io.github.yuxiximyxx.magicfree4.BuildConfig.DEBUG || runCatching {
            currentApp()?.let {
                android.provider.Settings.Global.getString(it.contentResolver, DUMP_KEY) == "1"
            } == true
        }.getOrDefault(false)

    /** 把对象（含父类）的全部字段打成一行，字段名=值，便于与本机真实设备逐字段 diff。 */
    private fun dumpFields(label: String, obj: Any?) {
        if (obj == null) { Log.i(TAG, "DUMP $label = null"); return }
        val sb = StringBuilder()
        var cls: Class<*>? = obj.javaClass
        val seen = HashSet<String>()
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    if (!seen.add(f.name)) return@runCatching
                    val v = f.get(obj)
                    sb.append(f.name).append('=')
                        .append(if (v is Array<*>) v.contentToString() else v?.toString())
                        .append("; ")
                }
            }
            cls = cls.superclass
        }
        Log.i(TAG, "DUMP $label (${obj.javaClass.name}): $sb")
    }

    private fun dumpDeviceInfoList(label: String, list: java.util.List<*>) {
        if (!isVerboseDump()) return
        Log.i(TAG, "DUMP $label size=${list.size}")
        list.take(8).forEachIndexed { i, o -> dumpFields("$label[$i]", o) }
    }

    /**
     * 诊断回读通道：把关键环节的结果写进 Settings.Global。
     * 本机 logcat 被 Honor 加密、模块日志又只在 LSPosed 里，读写 Settings 是最省事的闭环验证方式。
     * 用法：adb shell settings get global magicorig_diag_<key>
     */
    /** 同一台手机上其它设备的显示名：用来判定「这个视图子树是不是我们这张卡片」。 */
    private val OTHER_DEVICE_NAMES = listOf("WH-1000XM4", "FreeBuds", "MacBook", "LKY")

    /**
     * 断连时按原生华为耳机的样子收缩卡片：**不显示电量、不显示噪声控制**。
     *
     * 电量段标题 `earphone_battery_title` 文案是空的 → 按 id 找；
     * 噪声控制按文案找；标题右边那颗小电池按 id `battery_info_container` 找。
     * 三处都要求「所在子树里有 OPPO Enco Free4、且没有别的设备名」，不会误伤同场的其它卡片。
     */
    private fun applyOfflineVisibility(root: android.view.View?, online: Boolean) {
        if (root == null) return
        val want = if (online) android.view.View.VISIBLE else android.view.View.GONE
        var changed = 0
        val targets = listOfNotNull(
            findByIdName(root, "battery_info_container"),
            findByIdName(root, "earphone_battery_title")?.parent as? android.view.View,
            findTextView(root, "噪声控制")?.parent as? android.view.View,
        )
        for (v in targets) {
            // 归到「同一张卡片」：往上找到同时含我方可名、又不含别的设备名的那层
            if (ancestorContainingAlso(v, ourName(), OTHER_DEVICE_NAMES) == null) continue
            if (v.visibility != want) { v.visibility = want; changed++ }
        }
        // 「响铃查找」：离线时按原生样式置灰（不是隐藏 —— Free4 支持响铃，只是此刻点不动）
        findTextView(root, "响铃查找")?.let { t ->
            if (ancestorContainingAlso(t, ourName(), OTHER_DEVICE_NAMES) != null) {
                (t.parent as? android.view.View)?.alpha = if (online) 1f else 0.35f
            }
        }
        if (changed > 0) diag("offline", "online=$online 调整 $changed 段")
    }

    private fun ancestorContainingAlso(
        node: android.view.View,
        required: String,
        forbidden: List<String>,
    ): android.view.View? {
        var cur: android.view.View? = node.parent as? android.view.View ?: node
        while (cur != null) {
            if (findTextView(cur, required) != null) {
                return if (forbidden.any { findTextView(cur, it) != null }) null else cur
            }
            cur = cur.parent as? android.view.View
        }
        return null
    }

    private fun findByIdName(root: android.view.View, name: String): android.view.View? {
        val id = root.resources.getIdentifier(name, "id", "com.hihonor.controlcenter")
        if (id == 0) return null
        if (root.id == id) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) findByIdName(root.getChildAt(i), name)?.let { return it }
        }
        return null
    }

    private fun findTextView(view: android.view.View, text: String): android.view.View? {
        if (view is android.widget.TextView && view.visibility == android.view.View.VISIBLE &&
            view.text?.toString()?.contains(text) == true
        ) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) findTextView(view.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun diag(key: String, value: Any?) {
        runCatching {
            val ctx = currentApp() ?: return@runCatching
            android.provider.Settings.Global.putString(
                ctx.contentResolver,
                "magicorig_diag_$key",
                (value?.toString() ?: "null").take(600)
            )
        }
    }


    /** 读 Settings.Global["magicorig_last_anc"]（bluetooth 进程写入的用户默认档位） */
    /**
     * 任务6：耳机是否在线。bluetooth 进程在电量数据到达时写 "1"、断连时写 "0"
     * （Settings.Global["magicorig_connected"]）。默认/无记录 = false —— 断连后球不注入。
     */
    private fun isOurConnected(): Boolean = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return false
        android.provider.Settings.Global.getString(app.contentResolver, "magicorig_connected") == "1"
    }.getOrDefault(false)

    /** 任务2：设备中心卡片电量 —— 读跨进程电量（L90,R85）取双耳平均，无数据返回 null */
    /** 读跨进程电量原始值 "L100,R100,C90" → Triple(左, 右, 仓?)；L/R 缺一返回 null */
    private fun readBatteryRaw(): Triple<Int, Int, Int?>? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
            ?: return null
        // 逐段按前缀解析。旧写法 substringAfter(',') 会把 ",C90" 粘进 R 段尾巴，
        // 三段齐全时 R 解析成 null、整对被丢弃——FIX-005 记的「仓值被丢」即源于此。
        var l: Int? = null
        var r: Int? = null
        var c: Int? = null
        for (seg in raw.split(',')) {
            val s = seg.trim()
            when {
                s.startsWith("L") -> l = s.removePrefix("L").toIntOrNull()
                s.startsWith("R") -> r = s.removePrefix("R").toIntOrNull()
                s.startsWith("C") -> c = s.removePrefix("C").toIntOrNull()
            }
        }
        if (l == null || r == null) null else Triple(l!!, r!!, c)
    }.getOrNull()

    /**
     * 任务：设备中心显示双耳电量 —— 给 DeviceInfo 喂 BatteryData（left/right/powerAbility）。
     * batteryData 为 null 时电量 section 走默认（integrated=false、隐藏不显示）；
     * 而球列表常被持久化缓存（buildOur 只在首建跑一次），故也在 getDeviceInfoById
     * 出口对我们的设备补喂（电量 section 正是经它取数据）。
     */
    private fun feedDualBattery(info: Any) = runCatching {
        val lr = readBatteryRaw() ?: return@runCatching
        val bdCls = findClass("com.hihonor.controlviewnew.info.BatteryData")
        // 构造器非 public —— setAccessible 后 newInstance
        val bd = bdCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        bdCls.getMethod("setLeftPower", Int::class.javaPrimitiveType).invoke(bd, lr.first)
        bdCls.getMethod("setRightPower", Int::class.javaPrimitiveType).invoke(bd, lr.second)
        var ability = 0
        for (cn in listOf("POWER_ABILITY_LEFT", "POWER_ABILITY_RIGHT")) {
            ability = ability or bdCls.getDeclaredField(cn).apply { isAccessible = true }.getInt(null)
        }
        // ── v2.10.7 仓电量正喂：v2.10.6 的方法面转储已实锤 BatteryData 的仓
        //    setter 就是 setChargerPower(int)（另有 getChargerPower /
        //    isSupportChargerPower，无 setSupportChargerPower——支持标志应由
        //    ability 位派生）；仓能力位是 POWER_ABILITY_CHARGER（真机常量值 8）。
        //    v2.10.6 按候选名猜 setter 全部落空、误报「无仓电量接口」，本版直调。
        val caseLevel = lr.third
        var caseFed = false
        if (caseLevel != null) {
            caseFed = runCatching {
                bdCls.getMethod("setChargerPower", Int::class.javaPrimitiveType).invoke(bd, caseLevel)
                true
            }.onFailure {
                Log.w(TAG, "仓电量 setChargerPower($caseLevel) 调用失败: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
            if (caseFed) {
                val chargerBit = runCatching {
                    bdCls.getDeclaredField("POWER_ABILITY_CHARGER")
                        .apply { isAccessible = true }.getInt(null)
                }.onFailure {
                    Log.w(TAG, "POWER_ABILITY_CHARGER 常量读取失败，退回字面值 8: ${it.javaClass.simpleName}: ${it.message}")
                }.getOrDefault(8)
                ability = ability or chargerBit
            }
        }
        bdCls.getMethod("setPowerAbility", Int::class.javaPrimitiveType).invoke(bd, ability)
        info.javaClass.getMethod("setBatteryData", bdCls).invoke(info, bd)
        if (caseFed) {
            Log.w(TAG, "★★ 三段电量喂入: 左${lr.first}% 右${lr.second}% 仓${caseLevel}% (ability=0x${"%X".format(ability)})")
        } else {
            Log.w(TAG, "★★ 双耳电量喂入: 左${lr.first}% 右${lr.second}% (ability=0x${"%X".format(ability)})")
        }
    }.onFailure { Log.w(TAG, "双耳电量喂入失败: ${it.javaClass.simpleName}: ${it.message}") }

    private fun batteryLevelForCard(): Int? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
            ?: return null
        if (!raw.contains(",")) return null
        val l = raw.substringBefore(',').removePrefix("L").toIntOrNull()
        val r = raw.substringAfter(',').removePrefix("R").toIntOrNull()
        when {
            l != null && r != null -> (l + r) / 2
            l != null -> l
            r != null -> r
            else -> null
        }
    }.getOrNull()

    // ── v2.10.6 控制中心资源潜行 ─────────────────────────────
    // AAM 侧观察点 14e 的同款潜行搬到控制中心进程：小乐截图那张卡是控制
    // 中心服务卡（本进程按注入的 DeviceInfo 渲染），不是 AAM 的详情页——
    // 球/卡渲染时取的那张 drawable 的真名只能在本进程的资源层抓到。
    // 本 hook 经 HookEntry 在 com.hihonor.controlcenter 与
    // com.android.systemui 两进程各装一份；入口逐个装、逐行记成败，尽力而为。
    // 纯观察：命中只记日志，调用点一律原样 proceed。
    private val CC_RES_GUARD = ThreadLocal.withInitial { false }
    private val CC_RES_STACK_TOTAL = java.util.concurrent.atomic.AtomicInteger(0)
    private val CC_RES_PAIR_COUNTS =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun installCcResourceSnoop() {
        var hooked = 0

        runCatching {
            val m = findClass("android.widget.ImageView")
                .getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val id = chain.args.getOrNull(0) as? Int
                if (id != null) {
                    val res = (chain.thisObject as? android.widget.ImageView)?.resources
                    onCcResourceSnoopHit("ImageView.setImageResource", res, id)
                }
                chain.proceed()
            }
            hooked++
            Log.i(TAG, "控制中心资源潜行装载: ImageView.setImageResource(int) OK")
        }.onFailure {
            Log.w(TAG, "控制中心资源潜行装载: ImageView.setImageResource(int) 失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val resCls = findClass("android.content.res.Resources")
            for (m in listOf(
                resCls.getDeclaredMethod("getDrawable", Int::class.javaPrimitiveType),
                resCls.getDeclaredMethod(
                    "getDrawable", Int::class.javaPrimitiveType,
                    android.content.res.Resources.Theme::class.java
                )
            )) {
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val id = chain.args.getOrNull(0) as? Int
                    if (id != null) {
                        onCcResourceSnoopHit(
                            "Resources.getDrawable", chain.thisObject as? android.content.res.Resources, id
                        )
                    }
                    chain.proceed()
                }
                hooked++
            }
            Log.i(TAG, "控制中心资源潜行装载: Resources.getDrawable(int)/(int,Theme) OK")
        }.onFailure {
            Log.w(TAG, "控制中心资源潜行装载: Resources.getDrawable 失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val m = findClass("android.content.Context")
                .getDeclaredMethod("getDrawable", Int::class.javaPrimitiveType)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val id = chain.args.getOrNull(0) as? Int
                if (id != null) {
                    val res = (chain.thisObject as? android.content.Context)?.resources
                    onCcResourceSnoopHit("Context.getDrawable", res, id)
                }
                chain.proceed()
            }
            hooked++
            Log.i(TAG, "控制中心资源潜行装载: Context.getDrawable(int) OK")
        }.onFailure {
            Log.w(TAG, "控制中心资源潜行装载: Context.getDrawable(int) 失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        Log.i(TAG, "installed: 控制中心资源潜行（$hooked 个入口）[setImageResource/getDrawable]")
        if (hooked == 0) throw IllegalStateException("控制中心资源潜行一个入口都没装上")
    }

    /**
     * 命中处理与 AAM 侧 14e 同口径：先用宿主 Resources 把 id 解析成
     * type/entry 名（失败记 hex），按 (入口,id) 去重——每对只打前 2 次
     * 全栈、全栈总量封顶 60 条，之后只计数汇总；打栈滤框架/本模块帧，
     * 最多 25 帧。全程持 CC_RES_GUARD 防日志输出递归触发潜行。
     */
    private fun onCcResourceSnoopHit(entry: String, res: android.content.res.Resources?, id: Int) {
        if (CC_RES_GUARD.get() == true) return
        // id=0 是「清图」调用，无信息量，直接放过不记。
        if (id == 0) return
        val key = "$entry|0x${Integer.toHexString(id)}"
        val count = CC_RES_PAIR_COUNTS
            .computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }
            .incrementAndGet()
        val full = count <= 2 && CC_RES_STACK_TOTAL.get() < 60
        if (!full && count != 3 && count % 50 != 0) return
        CC_RES_GUARD.set(true)
        try {
            if (full) {
                val n = CC_RES_STACK_TOTAL.incrementAndGet()
                val name = resolveCcDrawableName(res, id)
                Log.i(TAG, "控制中心资源潜行命中 #$n $entry → $name（该入口+id 第 $count 次）")
                val frames = Throwable().stackTrace.filter { st ->
                    val cn = st.className
                    !cn.startsWith("android.") &&
                        !cn.startsWith("androidx.") &&
                        !cn.startsWith("java.") &&
                        !cn.startsWith("io.github.yuxiximyxx.magicfree4") &&
                        !cn.startsWith("io.github.libxposed") &&
                        !cn.startsWith("org.lsposed") &&
                        cn != "java.lang.Thread"
                }.take(25)
                for ((idx, st) in frames.withIndex()) {
                    Log.i(TAG, "控制中心资源潜行栈 #$n [$idx] ${st.className}.${st.methodName}")
                }
            } else {
                Log.i(TAG, "控制中心资源潜行计数 $entry id=0x${Integer.toHexString(id)} 已达 $count 次（每对只打前 2 次全栈，全栈封顶 60 条）")
            }
        } finally {
            CC_RES_GUARD.set(false)
        }
    }

    /** 用宿主 Resources 把 drawable id 解析成 "type/entry (0xid)"；失败退回 hex。 */
    private fun resolveCcDrawableName(res: android.content.res.Resources?, id: Int): String {
        val hex = "0x${Integer.toHexString(id)}"
        if (res == null) return "id=$hex（无 Resources 可解析）"
        val type = runCatching { res.getResourceTypeName(id) }.getOrNull()
        val entry = runCatching { res.getResourceEntryName(id) }.getOrNull()
        return if (type != null && entry != null) {
            "$type/$entry ($hex)"
        } else {
            "id=$hex（名称解析失败）"
        }
    }

    // ── v2.10.6 BatteryData 方法面转储（每进程只跑一次） ──────
    // 仓电量有没有接口，先把 BatteryData 的全部 declared 方法与全部
    // static int 常量（名+值）摆出来，再对着 feedDualBattery 的试喂结果看。
    private val BATTERY_SURFACE_DUMPED = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun dumpBatteryDataSurface() {
        if (!BATTERY_SURFACE_DUMPED.compareAndSet(false, true)) return
        runCatching {
            val bdCls = findClass("com.hihonor.controlviewnew.info.BatteryData")
            Log.i(TAG, "BatteryData 方法面转储开始（pkg=$packageName）")
            for (m in bdCls.declaredMethods.sortedBy { it.name }) {
                val params = m.parameterTypes.joinToString(",") { it.simpleName }
                Log.i(TAG, "BatteryData 方法: ${m.name}($params): ${m.returnType.simpleName}")
            }
            var constCount = 0
            for (f in bdCls.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type != Int::class.javaPrimitiveType) continue
                runCatching {
                    f.isAccessible = true
                    Log.i(TAG, "BatteryData 常量: ${f.name} = ${f.getInt(null)}")
                    constCount++
                }
            }
            Log.i(TAG, "BatteryData 方法面转储结束: 方法 ${bdCls.declaredMethods.size} 个, static int 常量 $constCount 个")
        }.onFailure {
            Log.w(TAG, "BatteryData 方法面转储失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ── v2.10.7 卡头图标定名 ─────────────────────────────
    // v2.10.6 资源潜行实证：服务卡头部那张头戴线条图标不走 Java 资源 API
    // （getDrawable/setImageResource 只抓到球的 BasicDeviceBallView.initIcon
    // 取三张耳机默认图、与卡创建期间电量格的图标），疑为布局 XML 原生
    // inflate 或现成 Drawable 直塞 setImageDrawable。本节改盯「成品交付」
    // 层：setImageDrawable/setImageBitmap 谁往 ImageView 塞了什么类；
    // 再在 DeviceServiceCardView 构造/init 完成后延迟 ~300ms 转储其整棵
    // 视图树，把每个 ImageView 的 drawable 用 ConstantState 与三个已知
    // 候选（device_icon_default_earphone / device_icon_in_ear_earphone_shadow /
    // device_icon_semi_in_ear_earphone_shadow，按 entry 名从宿主资源表查 id，
    // 不写死数值）逐一比对，命中即定名、未命中记 unknown。每张卡只转储一次。
    // 本 hook 在 controlcenter 与 systemui 两进程各跑一份：systemui 里没有
    // DeviceServiceCardView 类，该项装不上记一行跳过，不算失败。
    private val CARD_ICON_GUARD = ThreadLocal.withInitial { false }
    private val CARD_ICON_TOTAL = java.util.concurrent.atomic.AtomicInteger(0)
    private val CARD_ICON_SEEN: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private val CARD_TREE_DUMPED: MutableSet<Any> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
        )

    private fun installCardIconSnoop() {
        var hooked = 0

        // (a) setImageDrawable(Drawable)：卡头若是现成 Drawable 直塞，必经此处。
        runCatching {
            val m = findClass("android.widget.ImageView")
                .getDeclaredMethod("setImageDrawable", android.graphics.drawable.Drawable::class.java)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                runCatching {
                    val d = chain.args.getOrNull(0) as? android.graphics.drawable.Drawable
                    if (d != null) onCardIconDelivery("ImageView.setImageDrawable", describeDrawable(d))
                }
                chain.proceed()
            }
            hooked++
            Log.i(TAG, "卡头定名装载: ImageView.setImageDrawable(Drawable) OK")
        }.onFailure {
            Log.w(TAG, "卡头定名装载: ImageView.setImageDrawable(Drawable) 失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // (b) setImageBitmap(Bitmap)：成品若是位图直塞，走这条。
        runCatching {
            val m = findClass("android.widget.ImageView")
                .getDeclaredMethod("setImageBitmap", android.graphics.Bitmap::class.java)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                runCatching {
                    val b = chain.args.getOrNull(0) as? android.graphics.Bitmap
                    if (b != null) {
                        onCardIconDelivery(
                            "ImageView.setImageBitmap",
                            "Bitmap ${b.width}x${b.height} config=${b.config}"
                        )
                    }
                }
                chain.proceed()
            }
            hooked++
            Log.i(TAG, "卡头定名装载: ImageView.setImageBitmap(Bitmap) OK")
        }.onFailure {
            Log.w(TAG, "卡头定名装载: ImageView.setImageBitmap(Bitmap) 失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // (c) DeviceServiceCardView 构造/init：完成后延迟转储视图树（每卡一次）。
        runCatching {
            val cardCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardView")
            var n = 0
            for (ctor in cardCls.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    module.hook(ctor).intercept { chain ->
                        val r = chain.proceed()
                        chain.thisObject?.let { scheduleCardTreeDump(it) }
                        r
                    }
                    n++
                }.onFailure {
                    Log.w(TAG, "卡头定名装载: DeviceServiceCardView 构造 hook 失败: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
            for (m in cardCls.declaredMethods.filter { it.name.startsWith("init") }) {
                runCatching {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val r = chain.proceed()
                        chain.thisObject?.let { scheduleCardTreeDump(it) }
                        r
                    }
                    n++
                }.onFailure {
                    Log.w(TAG, "卡头定名装载: DeviceServiceCardView.${m.name} hook 失败: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
            if (n == 0) throw IllegalStateException("构造/init 一个都没装上")
            hooked += n
            Log.i(TAG, "卡头定名装载: DeviceServiceCardView 构造/init OK（$n 个）")
        }.onFailure {
            Log.w(TAG, "卡头定名装载: DeviceServiceCardView 跳过: ${it.javaClass.simpleName}: ${it.message}")
        }

        Log.i(TAG, "installed: 卡头图标定名（$hooked 个入口）[setImageDrawable/setImageBitmap/DeviceServiceCardView]")
        if (hooked == 0) throw IllegalStateException("卡头图标定名一个入口都没装上")
    }

    /** drawable 描述：类名 +（BitmapDrawable 再记 bitmap 宽高，其余记 intrinsic 宽高）。 */
    private fun describeDrawable(d: android.graphics.drawable.Drawable): String {
        val base = d.javaClass.name
        return if (d is android.graphics.drawable.BitmapDrawable) {
            val b = d.bitmap
            if (b != null) "$base bitmap=${b.width}x${b.height}" else "$base bitmap=null"
        } else {
            "$base intrinsic=${d.intrinsicWidth}x${d.intrinsicHeight}"
        }
    }

    /**
     * setImageDrawable/setImageBitmap 命中处理：记 drawable 类名 + 过滤栈
     * （≤20 帧）；按 (入口,描述,栈签名) 去重，总量封顶 40 条；纯观察原样放行
     * （放行在调用方的 chain.proceed()，本函数只记日志）。
     */
    private fun onCardIconDelivery(entry: String, desc: String) {
        if (CARD_ICON_GUARD.get() == true) return
        val frames = Throwable().stackTrace.filter { st ->
            val cn = st.className
            !cn.startsWith("android.") &&
                !cn.startsWith("androidx.") &&
                !cn.startsWith("java.") &&
                !cn.startsWith("io.github.yuxiximyxx.magicfree4") &&
                !cn.startsWith("io.github.libxposed") &&
                !cn.startsWith("org.lsposed") &&
                cn != "java.lang.Thread"
        }.take(20)
        val sig = entry + "|" + desc + "|" +
            frames.joinToString(">") { "${it.className}.${it.methodName}" }
        if (!CARD_ICON_SEEN.add(sig)) return
        if (CARD_ICON_TOTAL.get() >= 40) return
        val n = CARD_ICON_TOTAL.incrementAndGet()
        CARD_ICON_GUARD.set(true)
        try {
            Log.i(TAG, "卡头定名命中 #$n $entry → $desc")
            for ((idx, st) in frames.withIndex()) {
                Log.i(TAG, "卡头定名栈 #$n [$idx] ${st.className}.${st.methodName}")
            }
        } finally {
            CARD_ICON_GUARD.set(false)
        }
    }

    /** 卡构造/init 完成后，把视图树转储 post 到主线程延迟 ~300ms（等布局落位），每卡只排一次。 */
    private fun scheduleCardTreeDump(card: Any) {
        if (card !is android.view.View) return
        if (!CARD_TREE_DUMPED.add(card)) return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { dumpCardTree(card) }
                    .onFailure { Log.w(TAG, "卡视图树转储失败: ${it.javaClass.simpleName}: ${it.message}") }
                // v2.10.8：转储后接卡头修复——只在控制中心进程做视图改动，
                // 且绑定确认是本机 Free4 才动（判定在 applyCardHeaderFixes 内）。
                if (packageName == "com.hihonor.controlcenter") {
                    runCatching { applyCardHeaderFixes(card) }
                        .onFailure { Log.w(TAG, "卡头修复失败: ${it.javaClass.simpleName}: ${it.message}") }
                }
            }, 300)
        }.onFailure {
            Log.w(TAG, "卡视图树转储排程失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 转储卡视图树：先按 entry 名从宿主资源表查三个候选的 id 并预加载，
     * 取各自 ConstantState 作比对基准；再递归全树，逐个 ImageView 记
     * [视图类名, drawable 类名, intrinsic 宽高, 命中候选名或 unknown]。
     */
    private fun dumpCardTree(card: android.view.View) {
        val ctx = card.context ?: return
        val res = ctx.resources ?: return
        val pkg = ctx.packageName
        val candidates = mutableListOf<Pair<String, android.graphics.drawable.Drawable.ConstantState?>>()
        for (entryName in listOf(
            "device_icon_default_earphone",
            "device_icon_in_ear_earphone_shadow",
            "device_icon_semi_in_ear_earphone_shadow"
        )) {
            val id = runCatching { res.getIdentifier(entryName, "drawable", pkg) }.getOrDefault(0)
            val cs = if (id != 0) {
                runCatching { res.getDrawable(id, null)?.constantState }.getOrNull()
            } else {
                null
            }
            candidates += entryName to cs
            Log.i(
                TAG,
                "卡头候选: $entryName id=${if (id != 0) "0x" + Integer.toHexString(id) else "未找到"}" +
                    " constantState=${if (cs != null) "已加载" else "无"}"
            )
        }
        Log.i(TAG, "卡视图树转储开始: root=${card.javaClass.name}")
        dumpViewNode(card, candidates)
        Log.i(TAG, "卡视图树转储结束")
    }

    private fun dumpViewNode(
        v: android.view.View,
        candidates: List<Pair<String, android.graphics.drawable.Drawable.ConstantState?>>
    ) {
        if (v is android.widget.ImageView) {
            val d = v.drawable
            if (d == null) {
                Log.i(TAG, "卡视图树 ImageView[${v.javaClass.name}] drawable=null")
            } else {
                val cs = runCatching { d.constantState }.getOrNull()
                var match = "unknown"
                if (cs != null) {
                    for ((name, candCs) in candidates) {
                        if (candCs != null && (cs === candCs || cs == candCs)) {
                            match = name
                            break
                        }
                    }
                }
                Log.i(
                    TAG,
                    "卡视图树 ImageView[${v.javaClass.name}] drawable=${d.javaClass.name}" +
                        " intrinsic=${d.intrinsicWidth}x${d.intrinsicHeight} match=$match"
                )
            }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                dumpViewNode(v.getChildAt(i), candidates)
            }
        }
    }

    // ── v2.10.8 卡头修复（仅控制中心进程）─────────────────────
    // v2.10.7 视图树转储已把卡头图标定名：DeviceServiceCardView 里那张
    // 504×504 的 BitmapDrawable，ConstantState 命中宿主资源
    // device_icon_default_earphone——与球的 initIcon 取的是同一张，
    // 画的是头戴式。宿主自带入耳版 device_icon_in_ear_earphone_shadow。
    // 本节在转储之后接着做两处改动，且只在「绑定确认是本机 Free4」时动：
    //   1) 卡头图标替换：ConstantState 与 default 相同的 ImageView，
    //      drawable 换成 in_ear 版；
    //   2) 隐藏卡头单值电量：三段电量齐后，名字旁「电池图标+100%」
    //      显得多余——把这一组设为 GONE。只处理视图树前部、噪声控制
    //      section 之前的第一组匹配，电量段内的 L/R/仓三段绝不动。
    // 绑定读取：反射卡实例 declared fields（只走 com.hihonor 类层级，
    // 避开 View/ViewGroup 的海量字段），找 DeviceInfo/LinkDevice 对象
    // （再下钻一层）与其中的 String 字段（MAC / 设备名），逐项打日志；
    // MAC 命中 ourMac()/88:92… 或名字含 OPPO Enco Free4 才算确认。
    // 读不到绑定 → 只记日志、不改任何东西。
    // SystemUI 进程不做视图改动（排程处按 packageName 把关）。

    /** DFS 前序收集整棵视图树（改动定位与转储共用同一份顺序口径）。 */
    private fun collectPreorder(root: android.view.View, out: MutableList<android.view.View>) {
        out += root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) collectPreorder(root.getChildAt(i), out)
        }
    }

    /**
     * 读卡的设备绑定：反射卡实例的 declared fields（限 com.hihonor 类
     * 层级），逐项把找到的 String 字段与 DeviceInfo/LinkDevice 对象的
     * 下钻结果打日志（字段名+值）。返回 true = 绑定确认是本机 Free4。
     */
    private fun readCardBinding(card: Any): Boolean {
        var bound = false
        fun note(path: String, v: String) {
            Log.i(TAG, "卡绑定字段: $path = $v")
            if (isOurMac(v) || v.equals("88:92:CC:E5:00:8D", ignoreCase = true) ||
                v.contains("OPPO Enco Free4")
            ) {
                bound = true
            }
        }
        fun scan(obj: Any, depth: Int, path: String) {
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls.name.startsWith("com.hihonor")) {
                for (f in cls.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(obj)
                    }.getOrNull() ?: continue
                    val p = if (path.isEmpty()) f.name else "$path.${f.name}"
                    when {
                        v is String -> if (v.isNotBlank()) note(p, v)
                        v is android.view.View -> Unit // 视图字段不下钻，避免扫进整棵树
                        depth < 2 && (v.javaClass.name.contains("DeviceInfo") ||
                            v.javaClass.name.contains("LinkDevice")) -> {
                            Log.i(TAG, "卡绑定字段: $p -> ${v.javaClass.name}（下钻）")
                            scan(v, depth + 1, p)
                        }
                    }
                }
                cls = cls.superclass
            }
        }
        runCatching { scan(card, 0, "") }
            .onFailure { Log.w(TAG, "卡绑定读取失败: ${it.javaClass.simpleName}: ${it.message}") }
        Log.i(
            TAG,
            if (bound) "卡绑定判定: 本机 Free4 卡（执行卡头改动）"
            else "卡绑定判定: 未确认本机卡（跳过卡头改动）"
        )
        return bound
    }

    /** 卡头修复总入口：绑定确认后才替换图标、隐藏卡头单值电量。 */
    private fun applyCardHeaderFixes(card: android.view.View) {
        if (!readCardBinding(card)) return
        val ctx = card.context ?: return
        val res = ctx.resources ?: return
        val pkg = ctx.packageName
        val all = mutableListOf<android.view.View>()
        collectPreorder(card, all)

        // ── 1) 卡头图标：default_earphone → in_ear_earphone_shadow ──
        runCatching {
            val defId = res.getIdentifier("device_icon_default_earphone", "drawable", pkg)
            val inEarId = res.getIdentifier("device_icon_in_ear_earphone_shadow", "drawable", pkg)
            if (defId == 0 || inEarId == 0) {
                Log.w(
                    TAG,
                    "卡头图标替换跳过: 资源 id 缺失 def=0x${Integer.toHexString(defId)}" +
                        " inEar=0x${Integer.toHexString(inEarId)}"
                )
            } else {
                val defCs = res.getDrawable(defId, null)?.constantState
                var replaced = 0
                for (v in all) {
                    if (v !is android.widget.ImageView) continue
                    val cs = runCatching { v.drawable?.constantState }.getOrNull() ?: continue
                    if (defCs != null && (cs === defCs || cs == defCs)) {
                        val nd = runCatching { res.getDrawable(inEarId, null) }.getOrNull()
                        if (nd != null) {
                            v.setImageDrawable(nd)
                            replaced++
                        }
                    }
                }
                if (replaced > 0) {
                    Log.i(
                        TAG,
                        "卡头图标已替换为 in_ear: device_icon_default_earphone" +
                            " → device_icon_in_ear_earphone_shadow（$replaced 处）"
                    )
                } else {
                    Log.w(TAG, "卡头图标替换: 未找到 ConstantState 命中 default 的 ImageView")
                }
            }
        }.onFailure {
            Log.w(TAG, "卡头图标替换失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 2) 隐藏卡头单值电量（图标+百分比一组）──
        runCatching { hideHeaderBattery(all) }
            .onFailure {
                Log.w(TAG, "卡头电量隐藏失败: ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    /**
     * 定位并隐藏卡头那组单值电量。在 DFS 前序里截到「噪声控制」section
     * 之前为止（卡头区域），找第一组匹配：
     *   - 锚点 = 第一个文本匹配 ^\d+%$ 的 TextView；
     *   - 图标 = 与锚点同父的 BatteryLevelView / ImageView 兄弟；
     *   - 前提：锚点父容器子树里只有这一个 % 文本（L/R/仓那行是三个，
     *     天然被排除）；且只处理第一组，处理完即止。
     * 两者同父且父容器只含这两者 → 隐藏父容器；否则逐个隐藏。
     * 锚点找不到时退而取卡头区域第一个 BatteryLevelView 单独隐藏。
     */
    private fun hideHeaderBattery(all: List<android.view.View>) {
        val pctRe = Regex("^\\d+%$")
        val header = mutableListOf<android.view.View>()
        for (v in all) {
            if (v is android.widget.TextView &&
                v.text?.toString()?.contains("噪声控制") == true
            ) break
            header += v
        }
        fun pctCountIn(subtree: android.view.View): Int {
            var n = 0
            val stack = mutableListOf(subtree)
            while (stack.isNotEmpty()) {
                val cur = stack.removeAt(stack.size - 1)
                if (cur is android.widget.TextView &&
                    pctRe.matches(cur.text?.toString()?.trim().orEmpty())
                ) n++
                if (cur is android.view.ViewGroup) {
                    for (i in 0 until cur.childCount) stack += cur.getChildAt(i)
                }
            }
            return n
        }
        fun hideOne(v: android.view.View, why: String) {
            v.visibility = android.view.View.GONE
            val txt = (v as? android.widget.TextView)?.text?.toString()
            Log.i(
                TAG,
                "卡头电量已隐藏: ${v.javaClass.name}" +
                    (if (txt != null) " text=$txt" else "") + "（$why）"
            )
        }
        for (tv in header) {
            if (tv !is android.widget.TextView) continue
            if (!pctRe.matches(tv.text?.toString()?.trim().orEmpty())) continue
            val parent = tv.parent as? android.view.ViewGroup ?: continue
            if (pctCountIn(parent) != 1) continue // 三段电量行（多个 %）不是卡头那组
            val icon = (0 until parent.childCount)
                .map { parent.getChildAt(it) }
                .firstOrNull {
                    it !== tv && (it.javaClass.name ==
                        "com.hihonor.controlviewnew.widget.BatteryLevelView" ||
                        it is android.widget.ImageView)
                }
            if (icon != null && parent.childCount == 2) {
                parent.visibility = android.view.View.GONE
                Log.i(
                    TAG,
                    "卡头电量已隐藏: 父容器 ${parent.javaClass.name}" +
                        "（仅含电量图标 ${icon.javaClass.name} + 百分比 \"${tv.text}\"）"
                )
            } else {
                if (icon != null) hideOne(icon, "卡头电量图标")
                hideOne(tv, "卡头电量百分比")
            }
            return
        }
        // 锚点缺失的兜底：卡头区域第一个 BatteryLevelView
        val blv = header.firstOrNull {
            it.javaClass.name == "com.hihonor.controlviewnew.widget.BatteryLevelView"
        }
        if (blv != null) {
            hideOne(blv, "兜底：仅 BatteryLevelView")
        } else {
            Log.w(TAG, "卡头电量隐藏: 卡头区域未找到单值电量组")
        }
    }

    private fun readLastAnc(): String? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        android.provider.Settings.Global.getString(app.contentResolver, "magicorig_last_anc")
    }.getOrNull()
}
