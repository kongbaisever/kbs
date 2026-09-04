package kbs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kbs.data.AppPrefs
import kbs.data.PendingImport
import kbs.data.PresetFile
import kbs.ui.CalcViewModel
import kbs.ui.CrashActivity
import kbs.ui.screens.CalcScreen
import kbs.ui.theme.PearlTheme
import kbs.ui.theme.ThemeMode
import kbs.util.CrashHandler

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★★ 在 setContent 之前检查上次崩溃 ★★
        //
        // 崩溃日志如果放在主界面（Compose）里显示会形成死锁：
        //   主界面渲染崩溃 → 写日志 → 下次启动主界面又崩
        //   → 日志显示不出来 → 永远看不到原因。
        //
        // 这里改由独立的 CrashActivity（纯原生 View，零 Compose 依赖）
        // 展示，打破死锁。检测到崩溃记录就先跳过去。
        if (!intent.getBooleanExtra(EXTRA_FORCE_MAIN, false) &&
            CrashHandler.read(this).isNotBlank()
        ) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }

        // ------------------------------------------------------------
        // 从外部带入的配置
        //
        // 场景：朋友在微信里点开你发的 txt，
        //       "用其他应用打开" 选了本 App。
        //
        // 此时 intent 里带着文件内容，取出来暂存，
        // 等 CalcScreen 组合好后自己取走填进导入框。
        // 取走即清空，避免旋转屏幕时重复导入。
        // ------------------------------------------------------------
        consumeIncomingIntent(intent)

        setContent {
            // 主题模式从 AppPrefs 读取。
            //
            // 用 remember + 可变状态持有，使得用户在设置里改主题后
            // 无需重启即可生效。
            var themeMode by remember {
                mutableStateOf(ThemeMode.fromId(AppPrefs(this).themeMode))
            }

            PearlTheme(themeMode = themeMode) {
                val vm: CalcViewModel = viewModel()
                CalcScreen(
                    vm = vm,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        AppPrefs(this).themeMode = mode.name
                        themeMode = mode
                    },
                )
            }
        }
    }

    /**
     * Activity 已存在时再次收到 intent（App 在前台被调起）。
     *
     * 不重写这个方法的话：后台已有本 App 时，
     * 微信里点第二个 txt 文件不会有任何反应 ——
     * 用户会以为"怎么点不开了"，其实 intent 被丢了。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingIntent(intent)
    }

    private fun consumeIncomingIntent(src: Intent?) {
        val text = when (src?.action) {
            Intent.ACTION_VIEW -> {
                val uri = src.data
                if (uri != null) PresetFile.read(this, uri) else ""
            }
            Intent.ACTION_SEND -> {
                // 优先读文本；没有再看有没有带文件
                val direct = src.getStringExtra(Intent.EXTRA_TEXT)
                if (!direct.isNullOrBlank()) {
                    direct
                } else {
                    @Suppress("DEPRECATION")
                    val uri: Uri? = src.getParcelableExtra(Intent.EXTRA_STREAM)
                    if (uri != null) PresetFile.read(this, uri) else ""
                }
            }
            else -> ""
        }
        // 只认本 App 的分享码，避免把任意文本塞进导入框
        if (text.trim().startsWith("KBS1:")) {
            PendingImport.text = text.trim()
        }
    }

    companion object {
        /** 绕过崩溃检查、强制进入主界面 */
        const val EXTRA_FORCE_MAIN = "force_main"
    }
}
