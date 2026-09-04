package kbs.data

import android.content.Context
import kbs.core.model.LandingMode
import kbs.core.model.YAccumMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置预设 —— 保存**整套**解算参数，可重命名。
 *
 * ============================================================
 * 与「常用目标」的区别
 * ============================================================
 *
 * 常用目标只记 (x, z)，适合"固定几个落脚点"；
 * 配置预设记下**全部**可调项 —— 目标、基准状态、物理模型、
 * 终点判定、排序偏好。切换预设等于整套参数一起换。
 *
 * 典型用途：
 *   · 不同炮体有不同标定值 → 一台炮一个预设
 *   · 不同版本运动模型（1.21.2 前后） → 各存一份
 *   · 调试中的参数怕改坏 → 先存一份再动
 *
 * @param id 唯一标识，重命名和删除都按它定位
 * @param name 用户自定义名称
 * @param createdAt 创建时间，用于排序
 */
data class ConfigPreset(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val destX: String = "",
    val destZ: String = "",
    /**
     * 基准时刻 tick。
     *
     * ★ 这一项**曾经漏掉**，导致预设不完整：
     *   基准状态 = 位置 + 速度 + **时刻**，三者缺一不可。
     *   缺了 tick，反推出的初速基准就对不上，
     *   换回预设时算出的落点会偏。
     */
    val baseTick: String = "0",
    val groundHeight: String = "128",
    val endHeightMin: String = "",
    val endHeightMax: String = "",
    val landingMode: LandingMode = LandingMode.LANDING,
    val versionId: String = "",
    val yMode: YAccumMode = YAccumMode.SUM_THEN_ABS,
    val scoringPreset: String = "error",
    val baseX: String = "",
    val baseY: String = "",
    val baseZ: String = "",
    val baseMx: String = "0.0",
    val baseMy: String = "0.0",
    val baseMz: String = "0.0",
)

/**
 * 配置预设的本地存储。
 *
 * 存在独立的 SharedPreferences 文件里，与 AppPrefs 分开 ——
 * 预设是**用户数据**（可能越攒越多），
 * 偏好是**应用设置**（条目固定），混在一起不好清理。
 */
/**
 * 分享码的编解码。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 *
 * 预设存在私有目录 /data/data/kbs.pearl/shared_prefs/ 下，
 * Android 7+ 起没有 root 就读不到，更写不进去 ——
 * 想"复制文件发给朋友"在普通手机上根本行不通。
 *
 * 分享码把预设编成一段**纯文本**，
 * 微信/QQ 发过去，对方复制粘贴即可导入。
 *
 * ============================================================
 * 格式
 * ============================================================
 *
 *   KBS1:<Base64(JSON)>
 *
 * 带前缀有两个作用：
 *   1. 一眼能认出这是本 App 的分享码，粘贴错了立即报错
 *      （而不是解析出一堆空字段，用户还以为导入成功了）
 *   2. 万一以后字段结构有变，可以靠版本号区分
 *
 * 用 Base64 而非裸 JSON：JSON 里有大量引号与花括号，
 * 在微信里转发容易被自动转义、加换行，粘回来就废了。
 * Base64 只有字母数字和 +/=，转发多少次都不会走样。
 */
object PresetShare {

    private const val PREFIX = "KBS1:"
    private val FLAGS = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP

    /**
     * 字段顺序。
     *
     * ============================================================
     * 为什么用**数组**而不是 JSON 对象
     * ============================================================
     *
     * 实测同一份配置：
     *   JSON 对象（带键名）   499 字符
     *   JSON 数组（去键名）   236 字符   ← 省 52%
     *   管道分隔             183 字符
     *
     * 管道分隔虽然更短，但用户自定义的预设名里
     * 一旦出现 `|` 就会破坏格式，还得额外做转义 —— 得不偿失。
     *
     * JSON 数组既短又安全（引号、换行都有转义），
     * 代价是**字段顺序被写死**。因此：
     *   · 新增字段只能追加到末尾，绝不能插在中间
     *   · 若必须调整顺序，同时把 PREFIX 升到 KBS2
     */
    private const val FIELD_COUNT = 19

    /** 预设 → 定长数组（顺序见 [FIELD_COUNT] 注释约定） */
    private fun toArray(p: ConfigPreset): JSONArray = JSONArray().apply {
        put(p.id); put(p.name); put(p.createdAt)
        put(p.destX); put(p.destZ); put(p.baseTick)
        put(p.groundHeight); put(p.endHeightMin); put(p.endHeightMax)
        put(p.landingMode.name); put(p.versionId)
        put(p.yMode.name); put(p.scoringPreset)
        put(p.baseX); put(p.baseY); put(p.baseZ)
        put(p.baseMx); put(p.baseMy); put(p.baseMz)
    }

    private fun fromArray(a: JSONArray): ConfigPreset? {
        // 字段数不足说明版本不匹配或码被截断，宁可拒绝也不要补默认值 ——
        // 补默认值会让用户以为导入成功，实际参数全是错的
        if (a.length() < FIELD_COUNT) return null
        return runCatching {
            ConfigPreset(
                id = a.optString(0),
                name = a.optString(1),
                createdAt = a.optLong(2),
                destX = a.optString(3),
                destZ = a.optString(4),
                baseTick = a.optString(5),
                groundHeight = a.optString(6, "128"),
                endHeightMin = a.optString(7),
                endHeightMax = a.optString(8),
                landingMode = LandingMode.valueOf(a.optString(9, "LANDING")),
                versionId = a.optString(10),
                yMode = YAccumMode.valueOf(a.optString(11, "SUM_THEN_ABS")),
                scoringPreset = a.optString(12, "error"),
                baseX = a.optString(13),
                baseY = a.optString(14),
                baseZ = a.optString(15),
                baseMx = a.optString(16, "0.0"),
                baseMy = a.optString(17, "0.0"),
                baseMz = a.optString(18, "0.0"),
            )
        }.getOrNull()
    }

    /** 单个预设 → 分享码 */
    fun encode(preset: ConfigPreset): String = encodeAll(listOf(preset))

    /** 批量导出 */
    fun encodeAll(list: List<ConfigPreset>): String = runCatching {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        list.forEach { arr.put(toArray(it)) }
        val bytes = arr.toString().toByteArray(Charsets.UTF_8)
        PREFIX + android.util.Base64.encodeToString(bytes, FLAGS)
    }.getOrDefault("")

    /** 分享码 → 预设列表；无效时返回空列表 */
    fun decode(code: String): List<ConfigPreset> = runCatching {
        val s = code.trim()
        if (!s.startsWith(PREFIX)) return emptyList()
        val body = s.removePrefix(PREFIX)
            // 微信/QQ 转发常插入换行与空格，先全部清掉
            .replace(Regex("\\s"), "")
        val bytes = android.util.Base64.decode(body, FLAGS)
        val text = String(bytes, Charsets.UTF_8)
        val arr = JSONArray(text)
        val out = mutableListOf<ConfigPreset>()
        for (i in 0 until arr.length()) {
            fromArray(arr.getJSONArray(i))?.let { out += it }
        }
        out
    }.getOrDefault(emptyList())

    /** 只校验能否解出条目，用于 UI 即时反馈 */
    fun isValid(code: String): Boolean = decode(code).isNotEmpty()
}

object ConfigStore {

    private const val FILE = "kbs_config_presets"
    private const val KEY = "list"

    fun load(context: Context): List<ConfigPreset> = runCatching {
        val sp = sp(context)
        val text = sp.getString(KEY, null)
        if (text == null) emptyList() else decode(text)
    }.getOrDefault(emptyList())

    fun save(context: Context, list: List<ConfigPreset>) {
        runCatching {
            sp(context).edit().putString(KEY, encodeList(list)).apply()
        }
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    internal fun decode(text: String): List<ConfigPreset> {
        val arr = JSONArray(text)
        val out = mutableListOf<ConfigPreset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            out += ConfigPreset(
                id = id,
                name = o.optString("name", "未命名"),
                createdAt = o.optLong("createdAt"),
                destX = o.optString("destX"),
                destZ = o.optString("destZ"),
                baseTick = o.optString("baseTick", "0"),
                groundHeight = o.optString("groundHeight", "128"),
                endHeightMin = o.optString("endHeightMin"),
                endHeightMax = o.optString("endHeightMax"),
                landingMode = runCatching {
                    LandingMode.valueOf(o.optString("landingMode", "LANDING"))
                }.getOrDefault(LandingMode.LANDING),
                versionId = o.optString("versionId"),
                yMode = runCatching {
                    YAccumMode.valueOf(o.optString("yMode", "SUM_THEN_ABS"))
                }.getOrDefault(YAccumMode.SUM_THEN_ABS),
                scoringPreset = o.optString("scoringPreset", "error"),
                baseX = o.optString("baseX"),
                baseY = o.optString("baseY"),
                baseZ = o.optString("baseZ"),
                baseMx = o.optString("baseMx", "0.0"),
                baseMy = o.optString("baseMy", "0.0"),
                baseMz = o.optString("baseMz", "0.0"),
            )
        }
        return out
    }

    internal fun encodeList(list: List<ConfigPreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("createdAt", p.createdAt)
                put("destX", p.destX)
                put("destZ", p.destZ)
                put("baseTick", p.baseTick)
                put("groundHeight", p.groundHeight)
                put("endHeightMin", p.endHeightMin)
                put("endHeightMax", p.endHeightMax)
                put("landingMode", p.landingMode.name)
                put("versionId", p.versionId)
                put("yMode", p.yMode.name)
                put("scoringPreset", p.scoringPreset)
                put("baseX", p.baseX)
                put("baseY", p.baseY)
                put("baseZ", p.baseZ)
                put("baseMx", p.baseMx)
                put("baseMy", p.baseMy)
                put("baseMz", p.baseMz)
            })
        }
        return arr.toString()
    }
}
