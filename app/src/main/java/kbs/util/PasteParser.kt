package kbs.util

/**
 * 智能粘贴解析。
 *
 * ============================================================
 * 用途
 * ============================================================
 *
 * 玩家在游戏调试界面（投影 mod、Carpet /log 等）看到的是一整段文本，
 * 手动逐项抄进表单既慢又易错。这里把整段文本丢进来自动识别。
 *
 * ============================================================
 * 支持的格式
 * ============================================================
 *
 *   标签式（中英文冒号、等号、空格均可）：
 *     Tick: 72, x: 2.0, y: 169.63, z: 28.0
 *     mx=0.0 my=-0.003727 mz=0.0
 *     x：2.0  y：169.63  z：28.0
 *
 *   方括号数组：
 *     [2.0, 169.63, 28.0]
 *
 *   纯数字序列（7 个：tick x y z mx my mz）：
 *     72 2.0 169.63 28.0 0.0 -0.003727 0.0
 *
 * ============================================================
 * 关键陷阱
 * ============================================================
 *
 * `x` 的正则绝不能匹配到 `mx` 里的 x。
 * 解决办法是负向后顾 `(?<![A-Za-z])`：要求 x 前一个字符不是字母。
 * 这样 "mx: 555.0" 中的 x 前面是 m，不会被误当作坐标 x。
 */
data class ParsedSample(
    val tick: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val mx: Double? = null,
    val my: Double? = null,
    val mz: Double? = null,
    /** 识别到的字段数量，供 UI 判断解析是否充分 */
    val matched: Int = 0,
    /** 解析说明，展示给用户 */
    val hint: String = "",
) {
    val hasPosition: Boolean get() = x != null && y != null && z != null
    val hasVelocity: Boolean get() = mx != null && my != null && mz != null
    val hasAll: Boolean get() = hasPosition && hasVelocity
}

object PasteParser {

    private const val NUM = """-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?"""

    /** 分隔符：冒号（中英文）、等号、空白、逗号 */
    private const val SEP = """\s*[:：=]?\s*"""

    /**
     * 单个标签的匹配器。
     * ★ 负向后顾 (?<![A-Za-z]) 是核心：防止 mx 中的 x 被误匹配。
     */
    private fun pattern(label: String, num: String = NUM): Regex =
        Regex(
            """(?<![A-Za-z])$label$SEP($num)""",
            RegexOption.IGNORE_CASE,
        )

    private val RE_TICK = pattern("tick", """-?\d+""")
    private val RE_X = pattern("x")
    private val RE_Y = pattern("y")
    private val RE_Z = pattern("z")
    private val RE_MX = Regex("""\bmx$SEP($NUM)""", RegexOption.IGNORE_CASE)
    private val RE_MY = Regex("""\bmy$SEP($NUM)""", RegexOption.IGNORE_CASE)
    private val RE_MZ = Regex("""\bmz$SEP($NUM)""", RegexOption.IGNORE_CASE)

    /** 形如 [a, b, c] 的数组 */
    private val RE_BRACKET = Regex("""\[\s*($NUM)\s*,\s*($NUM)\s*,\s*($NUM)\s*\]""")

    /** 所有数字（用于裸数字回退） */
    private val RE_ALL_NUM = Regex(NUM)

    fun parse(text: String): ParsedSample {
        val raw = text.trim()
        if (raw.isBlank()) return ParsedSample(hint = "内容为空")

        var tick: Int? = null
        var x: Double? = null
        var y: Double? = null
        var z: Double? = null
        var mx: Double? = null
        var my: Double? = null
        var mz: Double? = null
        var matched = 0

        // ---- 1) 标签匹配（优先级最高）----
        RE_TICK.find(raw)?.let {
            tick = it.groupValues[1].toIntOrNull(); matched++
        }
        RE_X.find(raw)?.let {
            x = it.groupValues[1].toDoubleOrNull(); matched++
        }
        RE_Y.find(raw)?.let {
            y = it.groupValues[1].toDoubleOrNull(); matched++
        }
        RE_Z.find(raw)?.let {
            z = it.groupValues[1].toDoubleOrNull(); matched++
        }
        RE_MX.find(raw)?.let {
            mx = it.groupValues[1].toDoubleOrNull(); matched++
        }
        RE_MY.find(raw)?.let {
            my = it.groupValues[1].toDoubleOrNull(); matched++
        }
        RE_MZ.find(raw)?.let {
            mz = it.groupValues[1].toDoubleOrNull(); matched++
        }

        // ---- 2) 方括号数组回退：[x, y, z] ----
        if (x == null && y == null && z == null) {
            RE_BRACKET.find(raw)?.let { m ->
                x = m.groupValues[1].toDoubleOrNull()
                y = m.groupValues[2].toDoubleOrNull()
                z = m.groupValues[3].toDoubleOrNull()
                matched += 3
            }
        }

        // ---- 3) 裸数字回退：7 个或 6 个数字 ----
        if (matched < 3) {
            val nums = RE_ALL_NUM.findAll(raw).mapNotNull {
                it.value.toDoubleOrNull()
            }.toList()

            when {
                nums.size >= 7 -> {
                    // tick x y z mx my mz
                    tick = nums[0].toInt()
                    x = nums[1]; y = nums[2]; z = nums[3]
                    mx = nums[4]; my = nums[5]; mz = nums[6]
                    matched = 7
                }

                nums.size == 6 -> {
                    // x y z mx my mz（无 tick）
                    x = nums[0]; y = nums[1]; z = nums[2]
                    mx = nums[3]; my = nums[4]; mz = nums[5]
                    matched = 6
                }

                nums.size == 3 -> {
                    // 仅坐标
                    x = nums[0]; y = nums[1]; z = nums[2]
                    matched = 3
                }
            }
        }

        val hint = buildString {
            if (matched == 0) {
                append("未能识别。请检查是否包含 x/y/z 或 mx/my/mz 等字段。")
                return@buildString
            }
            val parts = mutableListOf<String>()
            if (tick != null) parts += "tick"
            if (x != null && y != null && z != null) parts += "坐标"
            if (mx != null && my != null && mz != null) parts += "速度"
            append("已识别：${parts.joinToString("、")}")
            if (tick == null) append("（未识别 tick，表单中的值保持不变）")
        }

        return ParsedSample(tick, x, y, z, mx, my, mz, matched, hint)
    }
}
