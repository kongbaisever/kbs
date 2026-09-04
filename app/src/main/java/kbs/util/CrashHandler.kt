package kbs.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获 —— 无需 adb / 电脑即可拿到闪退堆栈。
 *
 * ============================================================
 * 为什么需要
 * ============================================================
 *
 * 大多数玩家的环境没有 adb，闪退后拿不到 logcat，只能靠猜。
 * 本模块在进程崩溃前把堆栈写进内部存储，下次启动时
 * 由 CrashActivity 显示出来，可复制、可分享。
 *
 * ============================================================
 * 为什么必须在 attachBaseContext 安装
 * ============================================================
 *
 * attachBaseContext 是应用进程中**最早**可执行用户代码的时机，
 * 早于所有 ContentProvider 的 onCreate，也早于 Application.onCreate。
 * 放在这里才能捕获到 ContentProvider 初始化、Application 自身的异常。
 *
 * ============================================================
 * 死锁风险（已在架构层面解决）
 * ============================================================
 *
 * 早期版本把崩溃日志卡片放在主界面（Compose）里，形成死锁：
 *   主界面渲染崩溃 → 写日志 → 下次启动主界面又崩
 *   → 卡片显示不出来 → 永远看不到原因。
 *
 * 解决：日志改由独立的 CrashActivity（纯原生 View，零 Compose 依赖）展示，
 * 即使主界面完全不可用也能打开。本模块只负责"记录"，不负责"显示"。
 */
object CrashHandler {

    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_CHARS = 24_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 崩溃现场做 IO 必须同步，且绝不能抛出二次异常
            runCatching { save(app, thread, throwable) }
            // ★ 交给原 handler 收尾（弹"已停止运行"并杀进程）
            //   若不转交，进程不会退出，界面会卡死
            previous?.uncaughtException(thread, throwable)
        }
        installed = true
    }

    fun record(context: Context, tag: String, e: Throwable) {
        runCatching { save(context.applicationContext, Thread.currentThread(), e, tag) }
    }

    private fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun save(
        context: Context,
        thread: Thread,
        e: Throwable,
        tag: String? = null,
    ) {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        sb.appendLine("========== 崩溃时间 ==========")
        sb.appendLine(stamp)
        sb.appendLine()
        sb.appendLine("========== 设备信息 ==========")
        sb.appendLine(deviceInfo())
        sb.appendLine()
        sb.appendLine("========== 异常 ==========")
        if (tag != null) sb.appendLine("[$tag]")
        sb.appendLine("线程: ${thread.name}")

        // 展开 cause 链 —— 只打印最外层会漏掉真正的根因
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 6) {
            if (depth > 0) {
                sb.appendLine()
                sb.appendLine("--- Caused by ---")
            }
            sb.appendLine("${cause.javaClass.name}: ${cause.message}")
            cause.stackTrace.take(40).forEach { sb.appendLine("    at $it") }
            cause = cause.cause
            depth++
        }

        val text = sb.toString().let {
            if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "\n... (已截断)" else it
        }

        // 只保留最近一次：直接覆盖，避免累积占空间
        logFile(context).writeText(text)
    }

    private fun deviceInfo(): String = buildString {
        appendLine("型号: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
    }

    /** 读取上次崩溃日志；无则空串 */
    fun read(context: Context): String = runCatching {
        val f = logFile(context)
        if (!f.exists()) return ""
        f.readText()
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }
}
