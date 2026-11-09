package io.github.yuxiximyxx.magicfree4

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 模块设置入口（LSPosed 管理器的 MODULE_SETTINGS）跳板：
 * v2.10.10 起删掉旧首页（HomePage+底部导航），进模块直达耳机详情页。
 * 本 Activity 不渲染任何界面，onCreate 里立即转发并 finish，避免闪旧界面。
 * 桌面启动器走 manifest 的 LauncherActivity alias（同样指向详情页）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, EarphoneDetailActivity::class.java))
        finish()
    }
}
