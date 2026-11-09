package io.github.yuxiximyxx.magicfree4.hook

import io.github.yuxiximyxx.magicfree4.utils.PodIdentity
import io.github.libxposed.api.XposedInterface

/**
 * HonorEarphoneHook — 荣耀耳机面板进程内的 MBB 协议观察点。
 *
 * 目标进程：`com.hihonor.audioaccessorymanager`
 *
 * 背景：用 `am start -a com.hihonor.audioaccessorymanager.EARPHONE_DETAILS --es mac <MAC>`
 * 可以打开面板（BaseActivity.onCreate 的 mac 校验通过），但面板起来后会通过
 * MBB 多通道协议向耳机查设备信息（tag 5a2b6b / 5a0107），我们的 NiceHCK 耳机
 * 不说 MBB → 超时 → `getSmartConnectByMac onFailed = 2001` → 面板 finish()。
 *
 * 本 hook 只做**观察**，不改变行为：先拿到真实的调用时序与回调码，
 * 再决定怎么在 MbbApi 层把 MBB 请求翻译成 NiceHCK 的 0x4E SPP 帧。
 *
 * 反汇编得到的签名（classes2.dex / classes6.dex）：
 *   MbbApi.getSmartConnect(Ljava/lang/String;Ly8/c;)V   ← 带 MAC，日志中的 getSmartConnectByMac
 *   MbbApi.getSmartConnect(Ly8/c;)V
 *   y8/c : a(I)V        ← 推测 onFailed(errorCode)，对应日志 2001
 *            b(Ljava/lang/Object;)V ← 推测 onSuccess(data)
 */
object HonorEarphoneHook : HookContext() {
    private const val TAG = "MagicOriG-Earphone"
    private const val MBB_API = "com.hihonor.productconnect.bluetoothlib.MbbApi"
    private const val CALLBACK = "y8.c"
    // 真机运行时打印出来的 y8.c 具体实现类（$ 要转义，否则 Kotlin 会当插值）
    private const val CALLBACK_IMPL = "f4.b\$a"
    // 「是否受支持」判定与 device info error 的归属类（字符串→类归属法从 dex 定位）
    private const val Y2_C = "y2.c"
    private const val Y2_C_B = "y2.c\$b"
    private const val O0 = "com.hihonor.commonutils.o0"

    // j2.a.r0 递归改写用的重入标记（ThreadLocal 保证线程安全）
    private val FCPG_REENTER = ThreadLocal.withInitial { false }
    private fun FPG_SET() { FCPG_REENTER.set(true) }
    private fun FPG_CLEAR() { FCPG_REENTER.set(false) }

    // 观察点 14：ImageLoadUtils 运行时发现的一次性守卫与重定向计数
    private val IMAGE_DISCOVERY_STARTED = java.util.concurrent.atomic.AtomicBoolean(false)
    private val IMAGE_REDIRECT_COUNT = java.util.concurrent.atomic.AtomicInteger(0)

    // 观察点 14b（v2.10.2 侦察）：android.util.Log 潜行——命中计数、全栈打印上限、
    // 线程级重入守卫（模块自己写日志若底层也走 android.util.Log，命中特征的
    // 输出行不得在同一线程再次触发潜行）。
    private val LOG_SNOOP_GUARD = ThreadLocal.withInitial { false }
    private val LOG_SNOOP_HITS = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onHook() {
        Log.i(TAG, "onHook start pkg=$packageName")

        // ── 观察点 1：getSmartConnect(mac, cb) ──────────────────────
        runCatching {
            val cb = findClass(CALLBACK)
            val method = findMethod(MBB_API, "getSmartConnect", String::class.java, cb)
            module.hook(method).intercept { chain ->
                val mac = chain.args.getOrNull(0) as? String
                val cb = chain.args.getOrNull(1)
                // 反汇编确认：f4.b$a.b(Object) 是桥方法，
                //   check-cast v1, Ljava/lang/Boolean;
                //   invoke-virtual {v0,v1}, f4.b$a.c:(Ljava/lang/Boolean;)V
                // 即 onSuccess 的载荷类型就是 java.lang.Boolean，不是复杂结构体。
                Log.i(TAG, ">>> getSmartConnect(mac=$mac) cb=${cb?.javaClass?.name} → 合成成功")
                synthesizeSuccess(cb)
                // 不调用 chain.proceed()：真机实测原路径 3 秒后必然 onFailed code=2001
                null
            }
            Log.i(TAG, "installed: MbbApi.getSmartConnect(String, y8.c)")
        }.onFailure {
            Log.w(TAG, "install getSmartConnect(String,y8.c) failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 2：getSmartConnect(cb) ────────────────────────────
        runCatching {
            val cb = findClass(CALLBACK)
            val method = findMethod(MBB_API, "getSmartConnect", cb)
            module.hook(method).intercept { chain ->
                val cb = chain.args.getOrNull(0)
                Log.i(TAG, ">>> getSmartConnect(cb) cb=${cb?.javaClass?.name} → 合成成功")
                synthesizeSuccess(cb)
                null
            }
            Log.i(TAG, "installed: MbbApi.getSmartConnect(y8.c)")
        }.onFailure {
            Log.w(TAG, "install getSmartConnect(y8.c) failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 3：回调的具体实现 ─────────────────────────────────
        // y8.c 是接口（静态 dexdump 会误判成类，导致 "Cannot hook abstract methods"）。
        // 真机运行时打印出来的实现类是 f4.b$a（三次调用三个不同实例）。
        // 这里才是真正能拿到错误码 2001 和成功数据类型的地方。
        runCatching {
            val impl = findClass(CALLBACK_IMPL)
            val mFail = impl.getDeclaredMethod("a", Int::class.javaPrimitiveType).apply { isAccessible = true }
            module.hook(mFail).intercept { chain ->
                val code = chain.args.getOrNull(0)
                Log.w(TAG, "<<< $CALLBACK_IMPL.onFailed code=$code")
                chain.proceed()
            }
            Log.i(TAG, "installed: $CALLBACK_IMPL.a(int)  [onFailed]")
        }.onFailure {
            Log.w(TAG, "install $CALLBACK_IMPL.a(int) failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val impl = findClass(CALLBACK_IMPL)
            val mOk = impl.getDeclaredMethod("b", Any::class.java).apply { isAccessible = true }
            module.hook(mOk).intercept { chain ->
                val data = chain.args.getOrNull(0)
                Log.i(
                    TAG,
                    "<<< $CALLBACK_IMPL.onSuccess data=${data?.javaClass?.name}" +
                        "@${System.identityHashCode(data)} fields=${data?.javaClass?.declaredFields?.size}"
                )
                chain.proceed()
            }
            Log.i(TAG, "installed: $CALLBACK_IMPL.b(Object)  [onSuccess]")
        }.onFailure {
            Log.w(TAG, "install $CALLBACK_IMPL.b(Object) failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 4：y2.c.h(String):boolean —— modelID 是否被 AudioKit 支持 ──
        // y2.c.f/g 里有 "Current device's modelID is supported by AudioKit:"
        //           和 "00VVD9"（= baseSupportDeviceList.json 里 Third 的 modelId）
        // 先观察参数与返回值，确认 modelID 从哪来、判定在哪一步翻转。
        runCatching {
            val method = findClass(Y2_C).getDeclaredMethod("h", String::class.java)
                .apply { isAccessible = true }
            module.hook(method).intercept { chain ->
                val arg = chain.args.getOrNull(0)
                val original = chain.proceed()
                // 实测：y2.c.h("18:5C:A1:52:10:36") => false，随后5 秒 device info 超时(2001)
                // 这是「按 MAC 判定是否为受支持设备」的开关，对第三方耳机必然 false。
                Log.i(TAG, "y2.c.h(arg=$arg) => $original  强制改写为 true")
                true
            }
            Log.i(TAG, "installed: y2.c.h(String)  [modelID 支持判定]")
        }.onFailure {
            Log.w(TAG, "install y2.c.h failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 5：y2.c$b.a(int) —— "get device info error" 的发源地 ──
        runCatching {
            val impl = findClass(Y2_C_B)
            val method = impl.getDeclaredMethod("a", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            module.hook(method).intercept { chain ->
                // 吞掉失败：不调 proceed()，阻止错误向上传播触发 Activity.finish()。
                // 已确认仅强制 y2.c.h=true 不够 —— 设备信息仍会 5 秒超时(2001)导致面板退出。
                Log.w(TAG, "$Y2_C_B.onFailed code=${chain.args.getOrNull(0)} 已吞掉（不向上传播）")
                null
            }
            Log.i(TAG, "installed: $Y2_C_B.a(int)  [device info error 发源]")
        }.onFailure {
            Log.w(TAG, "install $Y2_C_B.a(int) failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 6：谁调用了 finish() ─────────────────────────────
        // 已用 am start -W 确认面板 Status: ok / TotalTime: 278ms（确实起来了），
        // 之后变成 mLastPausedActivity t-1（已销毁）。三个改写都生效仍被关闭，
        // 说明还有第三个关闭原因 —— 直接抓调用栈最省事。
        runCatching {
            val method = findClass("android.app.Activity").getDeclaredMethod("finish")
            module.hook(method).intercept { chain ->
                val self = chain.thisObject
                val name = self?.javaClass?.name ?: ""
                if (name.contains("EarphoneDetailsActivity")) {
                    val st = Thread.currentThread().stackTrace
                        .drop(1).take(12)
                        .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
                    Log.w(TAG, "EARPHONE ACTIVITY finish() 调用栈: $st")
                    // 临时诊断：吞掉 finish，让面板留下来以便观察它渲染了什么内容。
                    // 真实关闭路径是 LiveData 观察者 h0→p0→m0→finish，不是 MBB 超时直接触发。
                    Log.w(TAG, "EARPHONE ACTIVITY finish() 已拦截（诊断用，不销毁）")
                    return@intercept null
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: Activity.finish  [抓 EarphoneDetailsActivity 关闭栈]")
        }.onFailure {
            Log.w(TAG, "install Activity.finish failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 7/8：commonutils.o0 —— 机型支持列表与 json 配置解析 ──
        // o0.i(String):boolean  日志 "isSupportDeviceListOUC: ... is supported."
        // o0.d(String):String   日志 "getBaseJsonNameFromProductId: " → 返回 assets 下的 json 名
        // 让本设备命中带降噪栏的 FCPG（uiconfig/FCPG.json 里有 homepage.noiseControl.noiseSwitchNum=4）
        runCatching {
            val m = findClass(O0).getDeclaredMethod("i", String::class.java).apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val arg = chain.args.getOrNull(0)
                val original = chain.proceed()
                Log.i(TAG, "o0.i(arg=$arg) => $original  强制改写为 true")
                true
            }
            Log.i(TAG, "installed: o0.i(String)  [isSupportDeviceListOUC]")
        }.onFailure { Log.w(TAG, "install o0.i failed: ${it.javaClass.simpleName}: ${it.message}") }

        runCatching {
            val m = findClass(O0).getDeclaredMethod("d", String::class.java).apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val arg = chain.args.getOrNull(0) as? String
                val original = chain.proceed()
                // 实测：productId=Third => "Third"（读的是 assets/<name>.json 顶层菜单表）
                // FCPG.json 只存在于 assets/uiconfig/ 子目录，顶层没有 ——
                // 强制改成 FCPG 会加载不存在的资源，所以这里只观察、不改写。
                Log.i(TAG, "o0.d(productId=$arg) => $original  [仅观察：顶层菜单表]")
                original
            }
            Log.i(TAG, "installed: o0.d(String)  [getBaseJsonNameFromProductId 观察]")
        }.onFailure { Log.w(TAG, "install o0.d failed: ${it.javaClass.simpleName}: ${it.message}") }

        // ── 观察点 9：j2.a.r0(String) → UiConfigLite —— uiconfig/<prodId>.json 加载器 ──
        // 反汇编：r0 内部拼 "uiconfig/" + prodId + ".json"，
        //         失败打 "assets file empty for prodId:"，成功打 "success for prodId:"
        // 我们的设备 prodId=Third → uiconfig/Third.json 不存在（Third.json 在顶层且无 noiseControl）
        // 改用 FCPG（uiconfig/FCPG.json 里 homepage.noiseControl.noiseSwitchNum=4）
        runCatching {
            val loader = findClass("j2.a").getDeclaredMethod("r0", String::class.java)
                .apply { isAccessible = true }
            module.hook(loader).intercept { chain ->
                if (FCPG_REENTER.get()) {
                    // 递归入口：已经是改写后的调用，直接走原逻辑，避免死循环
                    return@intercept chain.proceed()
                }
                val arg = chain.args.getOrNull(0) as? String
                FPG_SET()
                try {
                    Log.i(TAG, "j2.a.r0(prodId=$arg) → 改用 FCPG 加载 uiconfig")
                    loader.invoke(chain.thisObject, "FCPG")
                } finally {
                    FPG_CLEAR()
                }
            }
            Log.i(TAG, "installed: j2.a.r0(String)  [uiconfig 加载器 → FCPG]")
        }.onFailure {
            Log.w(TAG, "install j2.a.r0 failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 10：b2.a.a(String):int —— checkConnectionState（根因开关）────
        // 字符串归属法确认 b2.a = 日志 TAG "EarphoneHelper"：
        //   a(String):int 含 "checkConnectionState: " + "operation not supported, treat as disconnected"
        //   d(String):Bundle 含 "resultBundle is null or getDeviceInfo failed"
        // 实测顺序：checkConnectionState:false → getDeviceInfo 失败 → mDeviceName=''
        //          → mUiConfigVersion='null' → 无 noiseControl → LiveData 关闭面板
        runCatching {
            val m = findClass("b2.a").getDeclaredMethod("a", String::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val mac = chain.args.getOrNull(0)
                val original = chain.proceed()
                // 实测 original=2（不是 0/false）—— 枚举很可能是 0=断开/1=连接中/2=已连接。
                // 强制成 1 反而可能把"已连接"降级，所以这里只观察。
                // 日志里的 "checkConnectionState: false" 来自另一个方法（疑似 b2.a.l(String):boolean）。
                Log.i(TAG, "b2.a.checkConnectionState(mac=$mac) => $original  [仅观察]")
                original
            }
            Log.i(TAG, "installed: b2.a.a(String)  [checkConnectionState 观察]")
        }.onFailure {
            Log.w(TAG, "install b2.a.a failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 11：b2.a.l(String):boolean —— 日志 "checkConnectionState: false" 的可能来源 ──
        // 上一轮教训：b2.a.a 返回 2，而日志打 false，两者不是同一个量。先观察不改写。
        runCatching {
            val m = findClass("b2.a").getDeclaredMethod("l", String::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val mac = chain.args.getOrNull(0)
                val original = chain.proceed()
                Log.i(TAG, "b2.a.l(mac=$mac) => $original  [仅观察]")
                original
            }
            Log.i(TAG, "installed: b2.a.l(String)  [疑似 checkConnectionState:false 来源]")
        }.onFailure {
            Log.w(TAG, "install b2.a.l failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 12：b2.a.d(String):Bundle —— getDeviceInfo 的真实返回 ──
        // 日志 "resultBundle is null or getDeviceInfo failed" 就出自它。
        runCatching {
            val m = findClass("b2.a").getDeclaredMethod("d", String::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val mac = chain.args.getOrNull(0)
                val original = chain.proceed()
                if (original != null) {
                    Log.i(TAG, "b2.a.getDeviceInfo(mac=$mac) => bundle 非空 size=${(original as? android.os.Bundle)?.size()}  [保持原样]")
                    original
                } else {
                    // 实测原本恒为 null。反汇编 b2.a.d 拿到它写入的 key：
                    //   device_general_name / device_submodel(默认"0") /
                    //   device_uiconfig_version  ← 就是 mUiConfigVersion 的来源
                    //   device_earphone_manu / "00VVD9"
                    // 空 Bundle 不足以推进链路，这里按真实 key 合成。
                    Log.w(TAG, "b2.a.getDeviceInfo(mac=$mac) => null  合成带 key 的 Bundle")
                    android.os.Bundle().apply {
                        putString("device_general_name", PodIdentity.current().displayName)
                        putString("device_submodel", "0")
                        putString("device_uiconfig_version", "1")
                        putString("device_earphone_manu", "")
                        putString("modelId", "00VVD9")
                    }
                }
            }
            Log.i(TAG, "installed: b2.a.d(String)  [getDeviceInfo null → 空 Bundle]")
        }.onFailure {
            Log.w(TAG, "install b2.a.d failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 13：i2.x.B(UiConfigLite) → Homepage —— 注入 FCPG 配置 ──
        // i2.x.b 是 UiConfigLite 持有字段；B(取 homepage)/E(取 NoiseControl)/K 是访问器。
        // 因为 j2.a.q() 是死代码（全 dex 无调用方），b 永远为 null → 无降噪栏。
        // 这里绕过整条初始化链：直接用 app 自带 Gson 解析 assets/uiconfig/FCPG.json。
        runCatching {
            val uiClass = findClass("com.hihonor.earphonesdk.uiconfig.entity.UiConfigLite")
            val m = i2xClass().getDeclaredMethod("B", uiClass).apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val arg = chain.args.getOrNull(0)
                if (arg != null) return@intercept chain.proceed()
                val cfg = loadFcpgConfig(uiClass)
                if (cfg == null) {
                    Log.w(TAG, "i2.x.B(null) 且 FCPG 解析失败")
                    return@intercept null
                }
                val homepage = cfg.javaClass.getDeclaredField("homepage")
                    .apply { isAccessible = true }.get(cfg)
                Log.i(TAG, "i2.x.B(null) → 注入 FCPG homepage = $homepage")
                homepage
            }
            Log.i(TAG, "installed: i2.x.B(UiConfigLite)  [注入 FCPG 配置]")
        }.onFailure {
            Log.w(TAG, "install i2.x.B failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── ★ 协议桥入口：n3.a.u = setNoiseControlFunction（本进程才有 d3.f）──
        // 反汇编：
        //   u(Byte, Byte, d3/f):V
        //     000f k3.a.R(Byte,Byte):[B   ← 两个 Byte 就是模式值
        //     001c c3.c.z([BI, d3/d;)V    ← 走 MBB 通道、3000ms 超时
        // 我们的耳机不说 MBB → 必然超时静默失败（用户实测：切模式耳机无反应）。
        runCatching {
            val cb = findClass("d3.f")   // 注意：是 d3.f，不是 y8.c
            val m = findClass("n3.a").getDeclaredMethod(
                "u", Byte::class.javaObjectType, Byte::class.javaObjectType, cb
            ).apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                Log.w(TAG, "setNoiseControlFunction mode1=${chain.args.getOrNull(0)} mode2=${chain.args.getOrNull(1)}  [MBB 下发入口]")
                chain.proceed()
            }
            Log.i(TAG, "installed: n3.a.u [setNoiseControlFunction 协议桥入口]")
        }.onFailure {
            Log.w(TAG, "install n3.a.u failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 下发链诊断梯（逐层确认点击走到哪）──
        // 调用方反汇编：g2.q.C(String,int) → g2.q.A0(int) → n3.a.u(Byte,Byte,d3.f) → MBB
        val ladder = listOf(
            Pair("g2.q", "C"),
            Pair("g2.q", "A0")
        )
        for ((clsName, mName) in ladder) {
            runCatching {
                val clazz = findClass(clsName)
                val ms = clazz.declaredMethods.filter { it.name == mName }
                if (ms.isEmpty()) throw NoSuchMethodException("$clsName.$mName")
                for (m in ms) {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        Log.w(TAG, "下发链命中: $clsName.$mName(${chain.args.joinToString()})")
                        chain.proceed()
                    }
                }
                Log.i(TAG, "installed: 下发链观察 $clsName.$mName (${ms.size} 个重载)")
            }.onFailure {
                Log.w(TAG, "install $clsName.$mName failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        // ── 观察点 14：ImageLoadUtils —— 面板大图标按产品名加载资源 ──
        // 日志实证 "ImageLoadUtils: --Third res state === REQUEST_SUCCESS"：
        // 大图标由 AAM 按产品名 "Third" 加载资源；系统更新（11.0.0.117）后
        // "Third" 的资源变成了通用头戴图，Free4（入耳式）被画成了头戴式。
        // 类名未知（混淆发行），这里只启动后台一次性发现（见 installImageLoadRedirect），
        // 发现后给带 String 参数的方法装重定向：实参恰为 "Third" → "FCPG"
        // （AirPods 4 降噪版，入耳式；本模块的 uiconfig 注入已同步它的资源）。
        // 身份（modelId/productId）与 uiconfig 都不是图标来源，不动它们。
        runCatching {
            installImageLoadRedirect()
            Log.i(TAG, "installed: ImageLoadUtils 后台发现 [面板图标 Third→FCPG]")
        }.onFailure {
            Log.e(TAG, "install ImageLoadUtils failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 观察点 14b（v2.10.2 侦察）：android.util.Log 潜行 ──
        // v2.10.1 真机实证：dex 枚举里没有 simple name == ImageLoadUtils 的类
        // ——AAM 是重混淆发行，"ImageLoadUtils" 只是它打日志时的 TAG 字符串。
        // 本版潜伏在 android.util.Log 上：谁打出含 "ImageLoadUtils" / "res state"
        // 的日志，就把它的调用栈打进模块日志，栈里第一个宿主帧的类全名就是
        // 那个加载器的真名。纯观察，不改任何日志行为（见 installLogSnoop）。
        runCatching {
            installLogSnoop()
        }.onFailure {
            Log.e(TAG, "install Log 潜行 failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        Log.i(TAG, "onHook done pkg=$packageName")
    }

    /**
     * 观察点 14 的一次性启动：发现 ImageLoadUtils 需要枚举宿主全部 dex 类名
     * （数万个），放后台守护线程做、不阻塞 AAM 启动；进程内只跑一次。
     */
    private fun installImageLoadRedirect() {
        if (!IMAGE_DISCOVERY_STARTED.compareAndSet(false, true)) return
        Thread {
            runCatching { discoverAndHookImageLoadUtils() }
                .onFailure {
                    Log.e(TAG, "ImageLoadUtils 发现/装载失败: ${it.javaClass.simpleName}: ${it.message}")
                }
        }.apply {
            name = "MagicPods-ImageLoadDiscover"
            isDaemon = true
        }.start()
    }

    /**
     * 经 dex 枚举找 simple name 恰为 ImageLoadUtils 的类（候选优先 com.hihonor.*），
     * 先把全部 declared methods 打进日志作方法面证据，再给带 String 参数的方法
     * 装 Third→FCPG 重定向 hook：只替换精确等于 "Third" 的实参，其余一律不碰。
     * 重定向日志前 20 次记全、之后每 50 次汇总一行，避免刷屏。
     * 任何一步失败只记 Log.e，不影响现有功能。
     */
    private fun discoverAndHookImageLoadUtils() {
        // v2.10.2：枚举覆盖面留证——classloader pathList 与宿主 APK 文件
        // （sourceDir + splitSourceDirs）两路都扫，各来源的条数由枚举函数
        // 自己逐行打日志，最后取并集搜候选，下轮可据此确认到底扫了多少类。
        val all = (enumerateDexClassNames() + enumerateApkFileClassNames()).distinct()
        val total = all.size

        // 近似类名扫描：simple name 含 ImageLoad（忽略大小写）、或以 Utils
        // 结尾且含 Image——预期没有，记零也是证据。
        val near = all.filter { name ->
            val simple = name.substringAfterLast('.')
            simple.contains("ImageLoad", ignoreCase = true) ||
                (simple.endsWith("Utils") && simple.contains("Image"))
        }
        if (near.isEmpty()) {
            Log.i(TAG, "近似类名扫描: simple name 含 ImageLoad / 以 Utils 结尾且含 Image 的类 = 0（共扫 $total 个类名）")
        } else {
            Log.i(TAG, "近似类名扫描命中（${near.size}）: ${near.take(50).joinToString()}")
        }

        val candidates = all
            .filter { it.substringAfterLast('.') == "ImageLoadUtils" }
            .distinct()
        if (candidates.isEmpty()) {
            Log.e(TAG, "ImageLoadUtils 未发现：dex 枚举里没有 simple name == ImageLoadUtils 的类（共扫 $total 个类名）")
            return
        }
        Log.i(TAG, "ImageLoadUtils 候选（${candidates.size}）: ${candidates.joinToString()}")
        val chosen = candidates.firstOrNull { it.startsWith("com.hihonor.") } ?: candidates.first()
        val clazz = findClass(chosen)
        Log.i(TAG, "ImageLoadUtils 已定位: ${clazz.name}")

        val methods = clazz.declaredMethods.toList()
        for (m in methods) {
            val params = m.parameterTypes.joinToString(", ") { it.simpleName }
            val mods = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) " static" else ""
            Log.i(TAG, "ImageLoadUtils 方法面:$mods ${m.name}($params): ${m.returnType.simpleName}")
        }

        var hooked = 0
        for (m in methods) {
            if (m.parameterTypes.none { it == String::class.java }) continue
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val newArgs = chain.args.toTypedArray()
                    var changed = false
                    for (i in newArgs.indices) {
                        if (newArgs[i] == "Third") {
                            newArgs[i] = "FCPG"
                            changed = true
                        }
                    }
                    if (!changed) return@intercept chain.proceed()
                    val n = IMAGE_REDIRECT_COUNT.incrementAndGet()
                    when {
                        n <= 20 -> Log.i(TAG, "ImageLoadUtils.${m.name}: Third→FCPG 重定向 #$n")
                        n % 50 == 0 -> Log.i(TAG, "ImageLoadUtils: Third→FCPG 重定向累计 $n 次")
                    }
                    chain.proceed(newArgs)
                }
                hooked++
            }.onFailure {
                Log.w(TAG, "hook ImageLoadUtils.${m.name} 失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        Log.i(TAG, "ImageLoadUtils 重定向 hook 装载完成: $hooked/${methods.size} 个方法")
    }

    /**
     * 观察点 14b：给 android.util.Log 的 v/d/i/w/e 两个重载
     * （(String,String) 与 (String,String,Throwable)，共 10 个）装潜行 hook。
     * 同步装在 onHook 里，进程一起来就生效；单个重载装不上只记 Log.w，
     * 一个都没装上才抛错让外层记 failed。
     */
    private fun installLogSnoop() {
        val logClass = findClass("android.util.Log")
        var hooked = 0
        for (level in listOf("v", "d", "i", "w", "e")) {
            for (withThrowable in listOf(false, true)) {
                runCatching {
                    val m = if (withThrowable) {
                        logClass.getDeclaredMethod(
                            level, String::class.java, String::class.java, Throwable::class.java
                        )
                    } else {
                        logClass.getDeclaredMethod(level, String::class.java, String::class.java)
                    }
                    m.isAccessible = true
                    module.hook(m).intercept { chain -> onSnoopedLog(chain) }
                    hooked++
                }.onFailure {
                    val sig = if (withThrowable) "String,String,Throwable" else "String,String"
                    Log.w(TAG, "Log 潜行 hook android.util.Log.$level($sig) 失败: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
        Log.i(TAG, "installed: Log 潜行 android.util.Log v/d/i/w/e（$hooked/10 个重载）")
        if (hooked == 0) throw IllegalStateException("android.util.Log 潜行一个重载都没装上")
    }

    /** 潜行命中判定（廉价字符串 contains，命不中绝不取栈）：tag/msg 任一含
     *  "ImageLoadUtils" 或 "res state"，或同时含 "Third" 与 "res"。 */
    private fun isImageLoadLogLine(tag: String?, msg: String?): Boolean {
        fun hit(s: String?): Boolean = s != null && (
            s.contains("ImageLoadUtils") ||
                s.contains("res state") ||
                (s.contains("Third") && s.contains("res"))
            )
        return hit(tag) || hit(msg)
    }

    /**
     * 潜行回调：命中特征时把 tag/msg 原文与当前调用栈（滤掉 android.util.Log、
     * 本模块与 hook 框架帧，最多 25 帧）打进模块日志——栈里第一个宿主帧的
     * 类全名就是打出 "ImageLoadUtils" 日志的加载器真名。前 12 次打全栈，
     * 之后只计数、每 50 次汇总一行。模块自己的命中输出全程持线程级重入
     * 守卫（LOG_SNOOP_GUARD），其日志行不会再次触发潜行。无论命中与否，
     * 原日志调用一律照常 proceed，宿主行为不变。
     */
    private fun onSnoopedLog(chain: XposedInterface.Chain): Any? {
        if (LOG_SNOOP_GUARD.get() == true) return chain.proceed()
        val tag = chain.args.getOrNull(0) as? String
        val msg = chain.args.getOrNull(1) as? String
        if (!isImageLoadLogLine(tag, msg)) return chain.proceed()
        val n = LOG_SNOOP_HITS.incrementAndGet()
        when {
            n <= 12 -> {
                LOG_SNOOP_GUARD.set(true)
                try {
                    Log.i(TAG, "Log潜行命中 #$n tag=$tag msg=$msg")
                    val frames = Throwable().stackTrace.filter { st ->
                        val cn = st.className
                        cn != "android.util.Log" &&
                            !cn.startsWith("io.github.yuxiximyxx.magicfree4") &&
                            !cn.startsWith("io.github.libxposed") &&
                            !cn.startsWith("org.lsposed") &&
                            cn != "java.lang.Thread"
                    }.take(25)
                    for ((idx, st) in frames.withIndex()) {
                        Log.i(TAG, "Log潜行栈 #$n [$idx] ${st.className}.${st.methodName}")
                    }
                } finally {
                    LOG_SNOOP_GUARD.set(false)
                }
            }
            n % 50 == 0 -> {
                LOG_SNOOP_GUARD.set(true)
                try {
                    Log.i(TAG, "Log潜行命中累计 $n 次（超 12 次后只计数汇总）")
                } finally {
                    LOG_SNOOP_GUARD.set(false)
                }
            }
        }
        return chain.proceed()
    }

    /** 枚举宿主 ClassLoader 里全部 dex 的类名（反射读 pathList → dexElements → dexFile）。 */
    private fun enumerateDexClassNames(): List<String> {
        val names = LinkedHashSet<String>()
        val pathList = runCatching {
            val f = dalvik.system.BaseDexClassLoader::class.java
                .getDeclaredField("pathList").apply { isAccessible = true }
            f.get(appClassLoader)
        }.getOrNull() ?: return emptyList()
        val elements = runCatching {
            val f = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            f.get(pathList) as Array<Any>
        }.getOrNull() ?: return emptyList()
        for ((index, element) in elements.withIndex()) {
            runCatching {
                val f = element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                val dexFile = f.get(element) as? dalvik.system.DexFile ?: return@runCatching
                val entries = dexFile.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    names.add(entries.nextElement())
                    count++
                }
                Log.i(TAG, "dex 来源[classloader#$index]: $count 个类名")
            }
        }
        Log.i(TAG, "dex 类名枚举完成: ${names.size} 个（elements=${elements.size}）")
        return names.toList()
    }

    /**
     * v2.10.2 枚举来源之二：宿主 App 的安装包文件本体——经 ActivityThread 取
     * ApplicationInfo，把 sourceDir 与 splitSourceDirs 逐个用 DexFile 打开
     * 枚举，每个来源单独记一行条数。取不到 ApplicationInfo / 单个文件打开
     * 失败都只记日志跳过，不影响 classloader 路径的枚举结果。
     */
    private fun enumerateApkFileClassNames(): List<String> {
        val names = LinkedHashSet<String>()
        val appInfo = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getDeclaredMethod("currentApplication").invoke(null)
                ?: return emptyList()
            app.javaClass.getMethod("getApplicationInfo").invoke(app)
        }.getOrNull()
        if (appInfo == null) {
            Log.w(TAG, "dex 来源[apk]: 取 ApplicationInfo 失败，跳过 APK 文件枚举")
            return emptyList()
        }
        val paths = mutableListOf<String>()
        runCatching {
            appInfo.javaClass.getField("sourceDir").get(appInfo) as? String
        }.getOrNull()?.let { paths.add(it) }
        runCatching {
            @Suppress("UNCHECKED_CAST")
            appInfo.javaClass.getField("splitSourceDirs").get(appInfo) as? Array<String>
        }.getOrNull()?.let { paths.addAll(it) }
        if (paths.isEmpty()) {
            Log.w(TAG, "dex 来源[apk]: sourceDir/splitSourceDirs 均为空，跳过 APK 文件枚举")
            return emptyList()
        }
        for ((index, path) in paths.withIndex()) {
            runCatching {
                val dex = dalvik.system.DexFile(path)
                val entries = dex.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    names.add(entries.nextElement())
                    count++
                }
                Log.i(TAG, "dex 来源[apk#$index] $path: $count 个类名")
                runCatching { dex.close() }
            }.onFailure {
                Log.w(TAG, "dex 来源[apk#$index] $path 打开/枚举失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        return names.toList()
    }

    private fun i2xClass(): Class<*> = findClass("i2.x")

    private var cachedConfig: Any? = null

    /**
     * 用 app 自己的 Gson 解析 AAM 的 assets/uiconfig/FCPG.json → UiConfigLite。
     * FCPG 的 homepage 含 noiseControl{noiseSwitchNum=4}，正是降噪栏的来源。
     */
    private fun loadFcpgConfig(uiClass: Class<*>): Any? {
        cachedConfig?.let { return it }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getDeclaredMethod("currentApplication").invoke(null)
                ?: return null
            val json = app.javaClass.getMethod("getAssets").invoke(app)
                .let { assets -> assets.javaClass.getMethod("open", String::class.java)
                    .invoke(assets, "uiconfig/FCPG.json") }
                .let { stream -> java.io.InputStreamReader(stream as java.io.InputStream).use { it.readText() } }
            val gson = Class.forName("com.google.gson.Gson").getDeclaredConstructor().newInstance()
            val parsed = gson.javaClass.getMethod("fromJson", String::class.java, Class::class.java)
                .invoke(gson, json, uiClass)
            cachedConfig = parsed
            Log.i(TAG, "FCPG.json 解析成功: ${json.length} 字节 → $parsed")
            return parsed
        }.onFailure {
            Log.w(TAG, "loadFcpgConfig 失败: ${it.javaClass.simpleName}: ${it.message}")
        }
        return null
    }

    /**
     * 立刻回调 onSuccess(Boolean.TRUE)，绕开 MBB 的 3 秒超时。
     *
     * 桥方法签名是 `b(Object)`，内部 `checkcast → Boolean`，所以传 Boolean.TRUE 即可。
     * 用 `javaClass.getMethod` 而非 findMethod —— 后者走 appClassLoader 的
     * `Class.forName`，对内部类名（`f4.b$a`）同样可用，但这里拿的是**运行时具体类**，
     * 与拦截点观察到的完全一致。
     */
    private fun synthesizeSuccess(callback: Any?) {
        if (callback == null) {
            Log.w(TAG, "synthesizeSuccess skipped: callback is null")
            return
        }
        runCatching {
            val method = callback.javaClass.getMethod("b", Any::class.java)
            method.invoke(callback, java.lang.Boolean.TRUE)
            Log.i(TAG, "synthesized onSuccess(Boolean.TRUE) on ${callback.javaClass.name}")
        }.onFailure {
            Log.w(TAG, "synthesizeSuccess failed on ${callback.javaClass.name}: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
