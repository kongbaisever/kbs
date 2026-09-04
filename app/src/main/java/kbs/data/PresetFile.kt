package kbs.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 配置预设的 **txt 文件** 导入导出。
 *
 * ============================================================
 * 为什么要有文件这条路
 * ============================================================
 *
 * 分享码（PresetShare）能工作，但操作链路太长：
 *   我这边复制 → 发过去 → 对方复制 → 打开 App → 粘贴 → 导入
 *
 * 而发文件是：
 *   我这边点一下 → 选微信好友 → 对方点文件 → 自动跳进本 App → 点导入
 *
 * 后者才是手机上顺手的做法。
 *
 * ============================================================
 * 两条路并存
 * ============================================================
 *
 *   txt 文件 —— 发给别人（顺手，但要经过微信）
 *   分享码   —— 自己备份、贴在群里、写在笔记里（纯文本，到处能粘）
 *
 * 两者内容**完全一致**，都是 `KBS1:...` 那段文本，
 * 只是载体不同，所以可以互相转换、互为备份。
 */
object PresetFile {

    /** 导出目录（filesDir 下，已被 FileProvider 白名单覆盖） */
    private const val DIR = "exports"

    /** 默认文件名（用户不填或填了非法字符时用这个） */
    private const val DEFAULT_NAME = "珍珠炮配置"

    /** 扩展名 */
    private const val EXT = ".txt"

    /**
     * Linux / Android 文件名里不能出现的字符。
     *
     * 尤其 `/` —— 它会把文件写到子目录去，
     * 而子目录可能不存在，导致写出失败或写错位置。
     */
    private val ILLEGAL = Regex("[\\/:*?\"<>|\u0000]")

    /** 最大长度。Android 单文件名上限 255 字节，留足余量 */
    private const val MAX_LEN = 60

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    /**
     * 规范化用户输入的文件名。
     *
     * 用户会输入各种东西，必须全部兜住：
     *   · 空 / 纯空白        → 用默认名
     *   · 含 `a/b.txt` 这类  → 去掉非法字符
     *   · 自己带了 `.txt`    → 不重复添加
     *   · 超长              → 截断
     *   · 只剩非法字符      → 回退默认名
     *
     * @return 一定是一个安全、非空、带 .txt 的文件名
     */
    fun sanitize(raw: String): String {
        var n = raw.trim()
            .replace(ILLEGAL, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        // 去掉首尾的点：`.txt` 会以隐藏文件形式出现，
        // `abc.` 在某些系统上会被拒绝
        n = n.trim('.')
        // 大小写不敏感地去掉已有扩展名，避免变成 "a.txt.txt"
        if (n.length > EXT.length && n.endsWith(EXT, ignoreCase = true)) {
            n = n.dropLast(EXT.length)
        }
        n = n.trim()
        // 清洗后为空（例如用户只输了 "///"）→ 回退
        if (n.isEmpty()) n = DEFAULT_NAME
        if (n.length > MAX_LEN) n = n.take(MAX_LEN)
        return n + EXT
    }

    /**
     * 建议的文件名：默认名 + 条数 + 月日。
     *
     * 带条数让对方一眼知道里面有几套配置；
     * 带月日方便区分"上周发的"和"今天发的"。
     * 默认就填好，用户想改随时能改 ——
     * 比给个光秃秃的 "珍珠炮配置.txt" 更实用。
     */
    fun suggestName(count: Int): String {
        val d = java.text.SimpleDateFormat("MMdd", java.util.Locale.CHINA)
            .format(java.util.Date())
        return sanitize("${DEFAULT_NAME}_${count}条_$d")
    }

    /**
     * 把分享码写成 txt，返回可供分享的 content URI。
     *
     * ★ 必须走 FileProvider：
     *   Android 7.0+ 禁止跨应用传递 file:// URI，直接传会抛
     *   FileUriExposedException。
     *
     * ★ 导出目录只保留最近一个文件：
     *   用户改了名字后，旧名字的文件会残留。
     *   每次导出前先清空目录，避免私有空间被攒满。
     *
     * @param fileName 已经过 [sanitize] 处理；为空则自动建议
     * @return content URI，失败返回 null
     */
    fun write(context: Context, code: String, fileName: String = ""): Uri? =
        runCatching {
            val dir = dir(context)
            // 清空旧导出
            dir.listFiles()?.forEach { f ->
                if (f.isFile) runCatching { f.delete() }
            }
            val name = if (fileName.isBlank()) suggestName(1) else sanitize(fileName)
            val f = File(dir, name)
            f.writeText(code, Charsets.UTF_8)
            FileProvider.getUriForFile(
                context.applicationContext,
                "${context.applicationContext.packageName}.fileprovider",
                f,
            )
        }.getOrNull()

    /** 当前目录下已有的导出文件名（用于在 UI 上提示） */
    fun existing(context: Context): List<String> =
        runCatching {
            dir(context).listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())

    /**
     * 构造系统分享面板的 Intent。
     *
     * 用 ACTION_SEND 而不是自己实现发送 ——
     * 系统面板里有微信、QQ、蓝牙、网盘等所有目标，
     * 用户选哪个都行，不需要我们逐个适配。
     */
    fun shareIntent(context: Context, uri: Uri, count: Int): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "珍珠炮配置 $count 条")
            // 部分 App（如旧版 QQ）优先读 EXTRA_TEXT。
            // 把码也放进去，对方即使只收到文本也能用。
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 从 content URI 读回文本 */
    fun read(context: Context, uri: Uri): String = runCatching {
        context.applicationContext.contentResolver
            .openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
    }.getOrDefault("")

    /** 文件选择器的 Intent（让用户从微信/文件管理里挑一个 txt） */
    fun pickIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
}

/**
 * 从外部（微信点开 txt、其他 App 分享）带进来的待导入文本。
 *
 * ============================================================
 * 为什么用一个全局单例
 * ============================================================
 *
 * MainActivity 通过 intent 收到文本时，CalcScreen 这个
 * Composable **还没组合**。而 intent 只在 onCreate 里来一次，
 * 之后 Activity 重建就丢了。
 *
 * 用单例暂存，CalcScreen 首次组合时取走（并清空），
 * 比改造 ViewModel 构造链风险低得多 ——
 * 后者要动 ViewModelProvider.Factory，牵一发动全身。
 */
object PendingImport {
    @Volatile
    var text: String? = null

    /** 取走并清空，保证只导入一次 */
    fun take(): String? {
        val t = text
        text = null
        return t
    }
}
