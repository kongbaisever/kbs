package kbs.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * 公告系统。
 *
 * ============================================================
 * 三级降级
 * ============================================================
 *
 *   远程（GitHub Raw JSON）→ 本地缓存 → 内置兜底
 *
 * 远程地址见 [REMOTE_URLS]。更新公告只需改仓库里的
 * announcements.json 并推送，**无需发版**。
 *
 * ============================================================
 * 关于 raw.githubusercontent.com 的可达性
 * ============================================================
 *
 * 该域名在国内访问不稳定，因此这里配了多个镜像前缀，
 * 按序尝试，首个成功即采用。全部失败时退到缓存/内置，
 * 用户仍能看到内容，不会开天窗。
 */
data class Announcement(
    val id: String,
    val title: String,
    val content: String,
    /** 可选的跳转链接，为空则不显示按钮 */
    val url: String? = null,
    val level: Level = Level.INFO,
) {
    enum class Level { INFO, UPDATE, WARN }

    companion object {
        /** 教程视频（B站） */
        const val TUTORIAL_URL = "https://b23.tv/fzwVdV9"
    }
}

object AnnouncementRepo {

    /**
     * 远程公告地址（按序尝试）。
     *
     * ============================================================
     * 排序策略：国内源优先
     * ============================================================
     *
     * raw.githubusercontent.com 在国内经常超时（8 秒白等），
     * 因此把国内可达的镜像排在前面，把 GitHub 官方降为兜底。
     *
     * 各源说明：
     *   jsdelivr      国内 CDN 节点多，通常最快
     *   ghproxy       公共代理，jsdelivr 不通时的备选
     *   gh-proxy      另一公共代理，与上者互为备份
     *   gh-proxy.com  老牌代理
     *   github 官方   最终兜底，保证内容一定是最新的
     *
     * 只要任一源返回了合法非空内容就停止尝试，
     * 所以源多一些不会拖慢速度（只在前面的源都失败时才继续）。
     */
    val REMOTE_URLS: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/kongbai9288/kbs-@main/announcements.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/kongbai9288/kbs-/main/announcements.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/kongbai9288/kbs-/main/announcements.json",
        "https://ghproxy.com/https://raw.githubusercontent.com/kongbai9288/kbs-/main/announcements.json",
        "https://raw.fastgit.org/kongbai9288/kbs-/main/announcements.json",
        "https://raw.githubusercontent.com/kongbai9288/kbs-/main/announcements.json",
    )

    private const val CACHE_FILE = "announcements_cache.json"
    private const val TIMEOUT_MS = 8000

    private fun cacheFile(context: Context): File =
        File(context.applicationContext.filesDir, CACHE_FILE)

    /**
     * 拉取公告。必须在 IO 线程调用。
     *
     * @return 远程成功返回远程内容；否则依次降级到缓存、内置
     */
    suspend fun fetch(context: Context): List<Announcement> = withContext(Dispatchers.IO) {
        for (url in REMOTE_URLS) {
            val parsed = runCatching { download(url) }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                runCatching { cacheFile(context).writeText(encode(parsed)) }
                return@withContext parsed
            }
        }
        // 远程全失败 → 缓存
        val cached = runCatching { readCache(context) }.getOrNull()
        if (!cached.isNullOrEmpty()) return@withContext cached
        // 最后兜底
        builtin()
    }

    /** 同步读取缓存（供启动首帧快速显示） */
    fun readCache(context: Context): List<Announcement> = runCatching {
        val f = cacheFile(context)
        if (!f.exists()) return emptyList()
        decode(f.readText())
    }.getOrDefault(emptyList())

    private fun download(url: String): List<Announcement> {
        val conn = URL(url).openConnection()
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "kbs-pearl")
        val text = conn.getInputStream().bufferedReader().use { it.readText() }
        return decode(text)
    }

    private fun decode(text: String): List<Announcement> {
        val arr = JSONArray(text)
        val out = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            out += Announcement(
                id = id,
                title = o.optString("title"),
                content = o.optString("content"),
                url = o.optString("url").takeIf { it.isNotBlank() },
                level = runCatching {
                    Announcement.Level.valueOf(o.optString("level", "INFO").uppercase())
                }.getOrDefault(Announcement.Level.INFO),
            )
        }
        return out
    }

    private fun encode(list: List<Announcement>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("content", it.content)
                put("url", it.url ?: "")
                put("level", it.level.name)
            })
        }
        return arr.toString()
    }

    /**
     * 内置兜底公告。
     *
     * 即使完全不联网，用户也能看到这些内容 —— 保证首次打开就有信息，
     * 而不是一片空白。
     *
     * ★ id 必须稳定：用户关闭某条公告后，手机里记的是这个 id。
     *   改动 id 会让已关闭的公告重新弹出。
     */
    fun builtin(): List<Announcement> = listOf(
        Announcement(
            id = "v400",
            title = "v4.0.0 完全重写",
            content = "核心物理引擎重写：区分 1.21.2+ 与旧版本两套运动模型，" +
                    "落点改由逐 tick 仿真给出（闭式解仅用于筛选候选），" +
                    "与原作者算法逐位对齐验证。",
            level = Announcement.Level.UPDATE,
        ),
        Announcement(
            id = "guide",
            title = "新手教程（视频）",
            content = "首次使用建议先看教程：如何填基准状态、如何读炮码、如何把炮码填进炮的控制面板。",
            url = Announcement.TUTORIAL_URL,
            level = Announcement.Level.INFO,
        ),
        Announcement(
            id = "ymode",
            title = "关于 Y 动量模式",
            content = "「abs(m+n) 原作者」与原作者 Python 一致，旧炮已实机验证；" +
                    "「|m|+|n| 矢量」符合对称炮体几何，新炮建议用此项并实测标定。" +
                    "两者在 m、n 异号时结果不同。",
            level = Announcement.Level.WARN,
        ),
        Announcement(
            id = "version",
            title = "版本模型选择",
            content = "1.21.2 起抛射物顺序改为「加速度→阻力→位移」，" +
                    "旧版本为「位移→阻力→加速度」。两套模型结果不同，请按实际游戏版本选择。",
            level = Announcement.Level.WARN,
        ),
        Announcement(
            id = "pulse160",
            title = "脉冲上限 160",
            content = "八个权重槽 [80,40,20,10,4,3,2,1] 全亮之和为 160。" +
                    "这是编码上限而非物理上限，超出的解无法用炮码正确表示，工具会自动钳制。",
            level = Announcement.Level.INFO,
        ),
        Announcement(
            id = "calib",
            title = "0.602679 不是通用常数",
            content = "该数值是特定炮体在特定版本下的拟合标定值，不代表单颗 TNT 的通用冲量。" +
                    "适配新炮请重新标定：固定珍珠与 TNT 坐标，测出单颗速度增量后填入。",
            level = Announcement.Level.INFO,
        ),
        Announcement(
            id = "feedback",
            title = "问题反馈",
            content = "仓库 Issues 或 B站评论区均可。遇到闪退请把崩溃日志页的内容一并发来。",
            level = Announcement.Level.INFO,
        ),
    )
}
