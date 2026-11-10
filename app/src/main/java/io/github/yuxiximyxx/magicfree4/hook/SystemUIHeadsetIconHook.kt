package io.github.yuxiximyxx.magicfree4.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.github.yuxiximyxx.magicfree4.pods.hem.PodProfiles
import io.github.yuxiximyxx.magicfree4.utils.PodIdentity
import io.github.yuxiximyxx.magicfree4.utils.SystemApisUtils.setIconVisibility
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * SystemUIHeadsetIconHook — 在 SystemUI 中管理耳机图标显示。
 *
 * 核心安全原则（防止炸机）：
 * 1. 所有反射调用必须用 runCatching 包裹，任何异常都不应抛出到 hook 链
 * 2. 回调必须用 WeakReference 存储，防止 context 泄漏
 * 3. 所有耗时操作必须在后台线程执行，不在主线程上反射
 * 4. registerReceiver 必须在 try-catch 内，系统可能在某些 Android 版本拒绝注册
 * 5. 图标更新用 iconOwned 状态机，避免重复 setIcon 导致资源竞争
 * 6. 每次操作都有最大重试次数限制，无限重试会卡死 SystemUI
 */
@SuppressLint("MissingPermission")
object SystemUIHeadsetIconHook : HookContext() {
    private const val TAG = "MagicOriG-SystemUI"

    /** 读跨进程电量（"L90,R85,C70" 三段）→ "左耳 90% 右耳 85%"；无数据返回原文占位 */
    private fun readBatteryLine(): String = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return "左耳 --% 右耳 --%"
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
            ?: return "左耳 --% 右耳 --%"
        if (!raw.contains(",")) return "左耳 --% 右耳 --%"
        // 逐段按前缀解析（同 EarphoneDetailActivity.parseBattery 口径）。
        // 写入端恒为三段，旧写法 substringAfter(',') 取右耳会把 ",C90"
        // 粘进 R 段 → 解析失败、右耳永远 --%（服务卡 v2.10.6 修的是同款 bug）。
        var l: Int? = null
        var r: Int? = null
        for (seg in raw.split(',')) {
            val s = seg.trim()
            when {
                s.startsWith("L") -> l = s.removePrefix("L").toIntOrNull()
                s.startsWith("R") -> r = s.removePrefix("R").toIntOrNull()
            }
        }
        val lv = l
        val rv = r
        val ls = if (lv != null && lv >= 0) "左耳 $lv%" else "左耳 --%"
        val rs = if (rv != null && rv >= 0) "右耳 $rv%" else "右耳 --%"
        "$ls $rs"
    }.getOrDefault("左耳 --% 右耳 --%")

    private fun currentDisplayName(): String =
        runCatching { PodIdentity.current().displayName }.getOrDefault(PodProfiles.FREE4.displayName)

    // 播控门控核对日志去重：文本+祖先链签名，只打前 20 条不同的
    private val REWRITE_CHAIN_LOGGED = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val REWRITE_CHAIN_TOTAL = java.util.concurrent.atomic.AtomicInteger(0)

    // v2.10.11 定位信号日志去重：文本+两信号签名，只打前 20 条不同的
    private val REWRITE_SIGNAL_LOGGED = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val REWRITE_SIGNAL_TOTAL = java.util.concurrent.atomic.AtomicInteger(0)

    /** 沿 view 的 parent 链向上收集祖先类名（≤12 层，含 view 自身一层）。 */
    private fun ancestorChain(view: android.view.View?): List<String> {
        val out = ArrayList<String>(12)
        var cur: Any? = view
        while (cur is android.view.View && out.size < 12) {
            out.add(cur.javaClass.name)
            cur = cur.parent
        }
        return out
    }

    /** 视图 id 名：无视图/无 id 记 "no-id"，资源名解析失败记 hex。 */
    private fun viewIdName(view: android.view.View?): String {
        val v = view ?: return "no-id"
        val id = v.id
        if (id == android.view.View.NO_ID) return "no-id"
        return runCatching { v.resources.getResourceEntryName(id) }.getOrNull()
            ?: "0x${Integer.toHexString(id)}"
    }

    /** 沿 ContextWrapper 链 unwrap 找第一个 Activity 的类名；找不到记 context 本身的类名。 */
    private fun activityClassName(ctx: android.content.Context?): String {
        if (ctx == null) return "no-ctx"
        var cur: android.content.Context? = ctx
        var hops = 0
        while (cur != null && hops++ < 20) {
            if (cur is android.app.Activity) return cur.javaClass.name
            cur = (cur as? android.content.ContextWrapper)?.baseContext ?: break
        }
        return ctx.javaClass.name
    }

    /**
     * v2.10.11 侦察：机型名分支决定改写前，先把两个定位信号打一行——
     * 视图 id 名，与沿 context unwrap 到的 Activity 类名（并列 rootView 类名）。
     * v2.10.9 的祖先链全是通用布局类、区分不了播控与通知/胶囊，
     * 靠这两个信号找门控特征。纯观察：放行行为（改不改写）不变。
     */
    private fun logRewriteSignal(text: String, view: android.view.View?) {
        runCatching {
            val idName = viewIdName(view)
            val actName = activityClassName(view?.context)
            val rootName = view?.let {
                runCatching { it.rootView?.javaClass?.name }.getOrNull()
            } ?: "no-root"
            val sig = "$text|$idName|$actName|$rootName"
            if (REWRITE_SIGNAL_LOGGED.putIfAbsent(sig, true) == null &&
                REWRITE_SIGNAL_TOTAL.incrementAndGet() <= 20
            ) {
                Log.i(TAG, "改写信号: '$text' id=$idName act=$actName root=$rootName")
            }
        }
    }

    /**
     * 占位文案换成当前机型名；已经是任一支持机型的名字时换成电量行。
     * ★ 例外（v2.10.9）：媒体输出选择器（播控）的设备行要显示设备名——
     * view 祖先链含 MediaOutput/OutputSwitcher 时跳过机型名→电量行改写。
     * ★ v2.10.12：机型名→电量行 只在 RemoteViews 通知树内做（inRemoteViewsTree=true）。
     * 真机日志实锤：所谓「播控名字没了」是下拉通知栏里切歌通知（media style）标题
     * 被 setText 终防线改写的表象，真正的播控面板从未被模块碰过。故 setText 路径
     * 对机型名一律不改（返回 null），切歌通知标题恢复设备名；「占位文案→机型名」
     * 替换两条路径都保留。
     * ★ v2.10.13：v2.10.12 把 setText 路径全关后，音频输出通知变成「标题=设备名、
     * 内容=设备名」——内容行的电量原本就是 setText 路径改出来的（RemoteViews walk
     * 改的那棵树不是实际上屏的那棵）。故 setText 路径按视图 id 分流：id 名为
     * "title" 的是标题视图，不改（保设备名）；其余视图恢复电量行改写。
     * ★ v2.10.14：占位分支加连接门控。真机实证（2026-11-10 截图）：Free4 已断开、
     * 音乐走本机扬声器时，音频通知标题仍被盖成「OPPO Enco Free4」（内容行是系统
     * 自己的「本机」）——占位分支此前不查连接状态，currentDisplayName() 又恒兜底
     * Free4 名，耳机不在也照样盖。故仅当本 hook 维护的 connectedAddresses 非空
     * （确有我方耳机连着）时才换名，为空返回 null 留系统原文案。机型名分支不动：
     * 它以「文本已是机型名」为前提，耳机不在时通知里不会出现该文本。
     */
    private fun rewriteAudioLabel(
        text: String,
        view: android.view.View? = null,
        inRemoteViewsTree: Boolean = false
    ): String? = when {
        text == "当前音频输入输出设备" ->
            if (connectedAddresses.isNotEmpty()) currentDisplayName() else null
        PodProfiles.ALL.any { it.displayName == text } -> {
            logRewriteSignal(text, view)
            val chain = ancestorChain(view)
            if (chain.isNotEmpty()) {
                val sig = "$text|${chain.joinToString(">")}"
                if (REWRITE_CHAIN_LOGGED.putIfAbsent(sig, true) == null &&
                    REWRITE_CHAIN_TOTAL.incrementAndGet() <= 20
                ) {
                    Log.i(TAG, "改写祖先链: '$text' <- ${chain.joinToString(" <- ")}")
                }
            }
            if (inRemoteViewsTree) {
                if (chain.any {
                        it.contains("MediaOutput", ignoreCase = true) ||
                            it.contains("OutputSwitcher", ignoreCase = true)
                    }
                ) {
                    null
                } else {
                    readBatteryLine()
                }
            } else {
                // v2.10.13：setText 路径——标题视图（id 名 "title"）保设备名，
                // 其余视图（通知内容行等）恢复电量行改写。
                if (viewIdName(view) == "title") {
                    null
                } else {
                    readBatteryLine()
                }
            }
        }
        else -> null
    }
    private const val SLOT = "wireless_headset"
    private const val MAX_RECONNECT_ATTEMPTS = 3

    // ── 安全状态 ──────────────────────────────────────────────────
    // 用 stateGeneration 保证状态机不会被过期回调覆盖
    private var stateGeneration = 0
    private val connectedAddresses = mutableSetOf<String>()
    private var iconOwned = false

    // ── 延迟初始化：避免 class 加载时引发 NPE ─────────────────────
    private var statusBarManager: StatusBarManager? = null
    private var statusBarManagerInitialized = false

    // ── 线程安全：所有回调必须在主线程执行 ────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        // 0. ★ 任务4：通知文案在 SystemUI 渲染端改写（RemoteViews.apply/reapply 返回的
        //    View 树 = 渲染前最后一刻）。sws 进程内的 set 层改写实测全部被荣耀混淆
        //    渲染类（QuaentCrearsaisSusts，动态 classloader 加载、无法 hook）覆盖，
        //    apply 也发生在 SystemUI 而非 sws —— 只有这里改写必然生效。
        //    文案匹配足够精确（只有音频设备通知含这两行），误伤面为零。
        runCatching {
            val rvCls = Class.forName("android.widget.RemoteViews")
            var cnt = 0
            for (mn in arrayOf("apply", "reapply")) {
                val m = rvCls.declaredMethods.firstOrNull {
                    it.name == mn && it.parameterCount >= 1 &&
                            android.view.View::class.java.isAssignableFrom(it.returnType)
                } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    if (ret is android.view.View) {
                        var changed = 0
                        fun walk(v: android.view.View) {
                            if (v is android.widget.TextView) {
                                val cur = v.text?.toString() ?: ""
                                val out = rewriteAudioLabel(cur, v, inRemoteViewsTree = true)
                                if (out != null) {
                                    v.text = out
                                    changed++
                                }
                            } else if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                            }
                        }
                        walk(ret)
                        if (changed > 0) Log.w(TAG, "★★★ SystemUI 通知改写 $changed 处（$mn）")
                    }
                    ret
                }
                cnt++
            }
            Log.i(TAG, "installed: RemoteViews.apply [SystemUI 通知文案改写] ($cnt)")
        }.onFailure {
            Log.w(TAG, "install SystemUI apply hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 0b. ★ 最终防线：hook TextView.setText（渲染的绝对终点）——
        //    实测 apply 层改写会改到 View 实例但通知栏仍显示原文（荣耀有独立的
        //    通知渲染/复用路径，apply 返回的实例未必是最终上屏的那个）。
        //    在 setText 处按精确文案改写，兜住一切渲染路径。
        //    匹配串极低频（只有音频设备通知含这两行），性能开销可忽略。
        runCatching {
            val m = Class.forName("android.widget.TextView").declaredMethods.first {
                it.name == "setText" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == CharSequence::class.java
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val t = (chain.args.getOrNull(0) as? CharSequence)?.toString()
                val out = t?.let {
                    rewriteAudioLabel(it, chain.thisObject as? android.view.View, inRemoteViewsTree = false)
                }
                if (t != null && out != null) {
                    Log.w(TAG, "★★★★ TextView.setText 改写: '$t' → '$out'")
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = out
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: TextView.setText [通知文案最终防线]")
        }.onFailure {
            Log.w(TAG, "install setText hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 1. 找 SystemUI 启动时机
        // MagicOS 的 SystemUI 里没有 CentralSurfacesImpl / SystemUIApplication / SystemUI
        // （真机 dexdump 确认），原来固定 hook CentralSurfacesImpl.start 必然 findClass 抛异常。
        // SystemUIService 是 Honor 保留下来的 AOSP 类，onCreate 是可靠的启动时机。
        val startTargets = listOf(
            "com.android.systemui.SystemUIService",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl"   // AOSP 回退
        )
        var installed = false
        for (cls in startTargets) {
            if (installed) break
            runCatching {
                hookAfter(findMethod(cls, "onCreate")) { safeStartHook(this) }
                installed = true
                Log.e(TAG, "startup hook installed on $cls.onCreate")
            }.onFailure {
                Log.i(TAG, "startup hook skipped for $cls: ${it.javaClass.simpleName}")
            }
        }

        // 2. hook StatusBarManager.setIconVisibility — 独立 hook
        runCatching {
            val smClass = findClass("android.app.StatusBarManager")
            hookAfter(smClass.method("setIconVisibility", String::class.java, Boolean::class.java)) {
                safeStatusBarHook(this)
            }
            Log.d(TAG, "StatusBarManager.setIconVisibility hook installed")
        }.onFailure { Log.d(TAG, "StatusBarManager hook skipped (may not be needed)") }
    }

    private fun safeStartHook(param: HookParam) {
        // 获取 context
        // SystemUIService 是 Service → ContextWrapper，实例本身就是 Context；
        // 跟 HeadsetStateDispatcher 同一个坑：继承链里没有 mContext 字段，
        // 反射取它必然失败 → 这里原先直接 return，整个图标 hook 静默不生效。
        val context: Context? = (param.instance as? Context)
            ?: runCatching { getObjectField(param.instance, "mContext") as? Context }.getOrNull()
            ?: runCatching { getObjectField(param.instance, "mBase") as? Context }.getOrNull()

        if (context == null) {
            Log.e(TAG, "context unavailable instance=${param.instance?.javaClass?.name}")
            return
        }
        Log.i(TAG, "startup hook fired, ctx=${context.javaClass.simpleName}")

        // 创建 receiver
        val receiver = createSafeReceiver(context)
        if (receiver == null) return  // 创建失败不阻塞

        // 注册 receiver 必须有 try-catch
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "A2DP broadcast receiver registered")
        }.onFailure { e ->
            Log.w(TAG, "failed to register A2DP receiver: ${e.message}")
            // 尝试降级方案：用 exported receiver
            runCatching {
                context.registerReceiver(
                    receiver,
                    IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED
                )
                Log.d(TAG, "A2DP receiver registered (fallback: EXPORTED)")
            }.onFailure { e2 ->
                Log.e(TAG, "both receiver registration methods failed", e2)
            }
        }

        // 系统启动时恢复图标状态
        restoreIconsOnBoot(context)
    }

    private fun createSafeReceiver(context: Context): BroadcastReceiver? {
        return try {
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    safeOnReceive(ctx, intent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create receiver", e)
            null
        }
    }

    private fun safeOnReceive(context: Context, intent: Intent) {
        try {
            val device = try {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } catch (e: Throwable) {
                // Android 14+ 可能需要不同的 extra key
                try {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (e2: Throwable) { null }
            } ?: return

            // 快速判断：非 OriG in 耳机直接返回，不处理
            if (!HeadsetStateDispatcher.isOriGPod(device)) return

            val state = try {
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            } catch (e: Throwable) { -1 }

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    safeAddConnected(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    safeRemoveConnected(device.address)
                }
            }
            stateGeneration++
            mainHandler.post { safeUpdateIcon(context) }
        } catch (e: Throwable) {
            Log.e(TAG, "onReceive failed", e)
        }
    }

    private fun safeAddConnected(address: String) {
        try { connectedAddresses.add(address) } catch (e: Throwable) { Log.w(TAG, "addConnected failed", e) }
    }

    private fun safeRemoveConnected(address: String) {
        try { connectedAddresses.remove(address) } catch (e: Throwable) { Log.w(TAG, "removeConnected failed", e) }
    }

    private fun restoreIconsOnBoot(context: Context) {
        // 蓝牙 adapter 获取可能失败，需捕获
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull() ?: return

        val generation = stateGeneration
        // getProfileProxy 回调必须在后台线程
        thread(name = "magicorig-bt-proxy") {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            if (generation != stateGeneration) {
                                // 已过期，不更新
                                return
                            }
                            // 只处理 A2DP profile
                            if (profile != BluetoothProfile.A2DP) return
                            val freshConnected = mutableListOf<String>()
                            runCatching {
                                proxy.connectedDevices
                                    .filter { HeadsetStateDispatcher.isOriGPod(it) }
                                    .forEach { freshConnected.add(it.address) }
                            }.onFailure { Log.w(TAG, "connectedDevices query failed", it) }
                            connectedAddresses.clear()
                            connectedAddresses.addAll(freshConnected)
                            mainHandler.post { safeUpdateIcon(context) }
                        } catch (e: Throwable) {
                            Log.e(TAG, "onServiceConnected failed", e)
                        } finally {
                            try { adapter.closeProfileProxy(profile, proxy) } catch (e: Throwable) {
                                Log.w(TAG, "closeProfileProxy failed", e)
                            }
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) = Unit
                }, BluetoothProfile.A2DP)
            } catch (e: Throwable) {
                Log.e(TAG, "getProfileProxy failed", e)
            }
        }
    }

    private fun safeUpdateIcon(context: Context) {
        try {
            // 懒初始化 statusBarManager
            if (!statusBarManagerInitialized) {
                statusBarManager = runCatching {
                    context.getSystemService(StatusBarManager::class.java)
                }.getOrNull()
                statusBarManagerInitialized = true
            }
            val sm = statusBarManager ?: return

            val hasConnected = connectedAddresses.isNotEmpty()

            // 状态未变化则跳过
            if (hasConnected == iconOwned) return

            // setIconVisibility 必须在主线程（SystemUI 要求）
            runCatching {
                sm.setIconVisibility(SLOT, hasConnected)
                if (hasConnected) {
                    // 尝试设置图标 — 获取 drawable ID 可能失败
                    val drawableId = runCatching {
                        context.resources.getIdentifier("stat_sys_wireless_headset", "drawable", context.packageName)
                    }.getOrNull() ?: 0
                    if (drawableId != 0) {
                        runCatching {
                            callMethod(sm, "setIcon", SLOT, drawableId, 0, "MagicPods")
                        }.onFailure { Log.w(TAG, "setIcon failed, visibility only", it) }
                    }
                }
                iconOwned = hasConnected
                Log.d(TAG, "icon updated: visible=$hasConnected owned=$iconOwned")
            }.onFailure { e ->
                Log.e(TAG, "setIconVisibility failed", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeUpdateIcon crashed", e)
        }
    }

    private fun safeStatusBarHook(param: HookParam) {
        // 拦截 setIconVisibility 确保图标不会意外被其他代码隐藏
        try {
            val slot = param.args[0] as? String
            val show = param.args[1] as? Boolean
            if (slot == SLOT && show == true) {
                // 确保 iconOwned 状态同步
                iconOwned = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeStatusBarHook failed", e)
        }
    }
}
