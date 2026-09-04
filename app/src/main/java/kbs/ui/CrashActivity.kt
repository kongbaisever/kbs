package kbs.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kbs.MainActivity
import kbs.util.CrashHandler
import java.io.File

/**
 * 崩溃日志查看页 —— **故意不使用 Compose**。
 *
 * ★ 为什么必须是原生 View：
 *   崩溃日志原本放在主界面里，形成死锁 —— 主界面渲染崩溃时，
 *   日志卡片根本显示不出来，等于把钥匙锁在房间内。
 *
 *   这个 Activity 不依赖 Compose、不依赖 ViewModel、
 *   不依赖任何可能出问题的业务代码，
 *   因此即使主界面完全不可用，它也一定能正常显示。
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val log = CrashHandler.read(this).ifBlank {
            "（未读取到崩溃日志）\n\n" +
                    "若反复闪退却看不到日志，说明崩溃发生在捕获器安装之前，\n" +
                    "通常是 ContentProvider 或 Application 初始化阶段。"
        }

        val pad = dp(16)

        val title = TextView(this).apply {
            text = "💥 上次运行时闪退了"
            setTextColor(Color.parseColor("#FF6B6B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, dp(8))
        }

        val hint = TextView(this).apply {
            text = "下面是崩溃原因。点「复制」或「分享」发给开发者即可定位。\n" +
                    "若点「仍要继续」后再次闪退，会回到这里显示新的日志。"
            setTextColor(Color.parseColor("#B0A8C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
        }

        val logView = TextView(this).apply {
            text = log
            setTextColor(Color.parseColor("#D8D0E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#1B162B"))
        }

        val scroller = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                rightMargin = dp(4)
                leftMargin = dp(4)
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(button("复制") { copyLog(log) })
            addView(button("分享") { shareLog(log) })
            addView(button("清除") {
                CrashHandler.clear(this@CrashActivity)
                goMain()
            })
        }

        val btnContinue = Button(this).apply {
            text = "仍要继续（可能再次闪退）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                // 不清日志：再次崩溃时能显示**新**的堆栈
                goMain()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48),
            ).apply { topMargin = dp(8) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#120E1C"))
            addView(title)
            addView(hint)
            addView(scroller)
            addView(btnRow)
            addView(btnContinue)
        }

        setContentView(root)
    }

    private fun goMain() {
        runCatching {
            val i = Intent(this, MainActivity::class.java).apply {
                // ★ 必须带此标志，否则 MainActivity 又会检测到崩溃日志
                //   而再次跳回本页，形成两个 Activity 之间的死循环
                putExtra(MainActivity.EXTRA_FORCE_MAIN, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
            finish()
        }
    }

    private fun copyLog(log: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("崩溃日志", log))
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "复制失败，请手动截图", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLog(log: String) {
        runCatching {
            val file = File(filesDir, "crash_share.txt")
            file.writeText(log)
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "珍珠炮 App 崩溃日志")
                putExtra(Intent.EXTRA_TEXT, log)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "发送崩溃日志"))
        }.onFailure {
            Toast.makeText(this, "分享失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
