package kbs.core.codec

import kbs.core.model.Direction
import kotlin.math.abs

/**
 * 脉冲数 ↔ 炮码 的编解码。
 *
 * ============================================================
 * 编码规则
 * ============================================================
 *
 * 八个权重槽：[80, 40, 20, 10, 4, 3, 2, 1]，全亮之和为 160。
 * 编码采用贪心：从大到小，能减则置 1。
 *
 * 该权重组可覆盖 0–160 的全部整数：
 *   [80,40,20,10] 覆盖 0–150（步长 10）
 *   [4,3,2,1]     贪心覆盖 0–10
 * 两者组合无缺口，因此贪心即为最优解。
 *
 * ★ 160 这个上限首先是**编码边界**，而不是物理上限。
 *   真实 TNT 数量还受炮体承载、同 tick 爆炸时序、
 *   遮挡导致 exposure 下降等工程因素限制。
 *
 * ============================================================
 * 炮码格式
 * ============================================================
 *
 *     reverse(bits(|n|)) + " " + 方向码 + " " + bits(|m|)
 *
 * 其中 bits(x) 为 "XXXX XXXX"（高 4 位 + 空格 + 低 4 位）。
 *
 * 例：m=160, n=0, 方向 S → "0000 0000 11 1111 1111"
 *
 * ★ n 部分反转是原炮的硬件接线顺序决定的。
 *   若实际炮的槽位排列与本工具显示的阵列图对不上，
 *   只需调整本文件中的 [reverseGroupN] 即可对齐，无需改动解算逻辑。
 */
object PulseCodec {

    /** 权重槽，从高位到低位 */
    val WEIGHTS: List<Int> = listOf(80, 40, 20, 10, 4, 3, 2, 1)

    /** 单组可编码的最大值 */
    val MAX_ENCODABLE: Int = WEIGHTS.sum()   // = 160

    /** 是否反转 n 组的比特顺序（原炮硬件接线） */
    private const val reverseGroupN = true

    // ============================================================
    // 单组编码
    // ============================================================

    /**
     * 将脉冲数编码为 8 位比特串，格式为 "XXXX XXXX"。
     *
     * @param value 脉冲数（取绝对值后编码，方向由方向码表达）
     */
    fun encodeGroup(value: Int): String {
        var num = abs(value).coerceAtMost(MAX_ENCODABLE)
        val bits = StringBuilder()
        for (w in WEIGHTS) {
            if (num >= w) {
                bits.append('1')
                num -= w
            } else {
                bits.append('0')
            }
        }
        val s = bits.toString()
        return "${s.substring(0, 4)} ${s.substring(4)}"
    }

    /** 将 "XXXX XXXX" 解码回整数（用于往返校验） */
    fun decodeGroup(bits: String): Int {
        val s = bits.replace(" ", "")
        require(s.length == 8) { "比特串长度必须为 8，实际 ${s.length}" }
        var sum = 0
        for (i in 0 until 8) {
            if (s[i] == '1') sum += WEIGHTS[i]
        }
        return sum
    }

    // ============================================================
    // 完整炮码
    // ============================================================

    /**
     * 生成完整炮码字符串。
     *
     * @param m 主向脉冲数
     * @param n 副向脉冲数
     * @param direction 主方向
     */
    fun encode(m: Int, n: Int, direction: Direction): String {
        val bitsM = encodeGroup(m)
        val bitsN = encodeGroup(n)
        val nPart = if (reverseGroupN) bitsN.reversed() else bitsN
        return "$nPart ${direction.code} $bitsM"
    }

    /** 炮码是否可无损表示给定的 (m, n) —— 超出 160 会被静默截断 */
    fun isEncodable(m: Int, n: Int): Boolean =
        abs(m) <= MAX_ENCODABLE && abs(n) <= MAX_ENCODABLE

    // ============================================================
    // 阵列图数据（供 UI 直接渲染，显示各权重槽的亮灭）
    // ============================================================

    /** 单个权重槽的状态 */
    data class Slot(
        val weight: Int,
        val lit: Boolean,
    )

    /** 一组（主向或副向）的槽位状态 */
    data class SlotGroup(
        val label: String,
        val value: Int,
        val slots: List<Slot>,
    ) {
        /** 需要点亮的槽位数 */
        val litCount: Int get() = slots.count { it.lit }
    }

    /**
     * 生成阵列图数据。
     *
     * 玩家无需去数 18 位 0/1，直接看哪几个权重位要摆即可。
     *
     * @param reverse 是否按反转后的顺序显示（n 组需与炮码一致）
     */
    fun slotsOf(value: Int, reverse: Boolean = false): List<Slot> {
        var num = abs(value).coerceAtMost(MAX_ENCODABLE)
        val result = mutableListOf<Slot>()
        for (w in WEIGHTS) {
            if (num >= w) {
                result.add(Slot(w, true))
                num -= w
            } else {
                result.add(Slot(w, false))
            }
        }
        return if (reverse) result.reversed() else result
    }

    /**
     * 完整的炮码布局，含主向/副向两组与方向。
     */
    fun layout(m: Int, n: Int, direction: Direction): CodeLayout =
        CodeLayout(
            code = encode(m, n, direction),
            main = SlotGroup("主向 m = $m", m, slotsOf(m, reverse = false)),
            sub = SlotGroup("副向 n = $n", n, slotsOf(n, reverse = reverseGroupN)),
            direction = direction,
        )

    data class CodeLayout(
        val code: String,
        val main: SlotGroup,
        val sub: SlotGroup,
        val direction: Direction,
    ) {
        /** 总共需要点亮的槽位数（炮据此自动配置 TNT，无需人工摆放） */
        val totalLit: Int get() = main.litCount + sub.litCount
    }
}
