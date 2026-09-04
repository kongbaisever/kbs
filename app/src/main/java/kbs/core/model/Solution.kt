package kbs.core.model

import kbs.core.codec.PulseCodec

/**
 * 一组完整的解算结果。
 *
 * 对应玩家的一套可操作配置：摆多少 TNT、朝哪个方向、飞多久、
 * 预计落在哪、偏多少、炮码是什么。
 */
data class Solution(
    /** 主向脉冲数 m */
    val m: Int,
    /** 副向脉冲数 n */
    val n: Int,
    /** 主方向 */
    val direction: Direction,
    /** 飞行 tick 数 */
    val flyTicks: Int,
    /** 预测落点 */
    val landing: Vec3,
    /** 落点与目标的水平误差（格） */
    val error: Double,
    /** 需要的 TNT 总颗数 |m|+|n| */
    val totalTnt: Int,
    /** 轨迹最高点，用于判断是否会撞到天花板 */
    val peakY: Double,
    /** 是否在到达目标 tick 前就触地（true 表示中途落地） */
    val hitGroundEarly: Boolean,
    /** 飞行剖面（水平距离 → 高度），供绘制轨迹图 */
    val profile: List<ProfilePoint> = emptyList(),
) {
    /** 炮码字符串 */
    val code: String get() = PulseCodec.encode(m, n, direction)

    /** 阵列图数据（各权重槽的亮灭状态） */
    val codeLayout: PulseCodec.CodeLayout
        get() = PulseCodec.layout(m, n, direction)

    /** 是否可被 8 位编码无损表示 */
    val encodable: Boolean get() = PulseCodec.isEncodable(m, n)

    /** 落点相对目标的偏移方向描述，便于玩家微调 */
    fun offsetHint(targetX: Double, targetZ: Double): String {
        val dx = landing.x - targetX
        val dz = landing.z - targetZ
        val parts = mutableListOf<String>()
        if (kotlin.math.abs(dx) >= 0.05) {
            parts += (if (dx > 0) "东" else "西") +
                    String.format("%.2f", kotlin.math.abs(dx))
        }
        if (kotlin.math.abs(dz) >= 0.05) {
            parts += (if (dz > 0) "南" else "北") +
                    String.format("%.2f", kotlin.math.abs(dz))
        }
        return if (parts.isEmpty()) "正中" else parts.joinToString(" · ")
    }
}

/**
 * 排序偏好。
 *
 * 真实炮体存在多个竞争目标：落点精度、TNT 消耗、飞行时间。
 * 这里提供权重化评分，默认纯按精度排序。
 *
 * @param errorWeight 误差权重
 * @param tntWeight 每颗 TNT 的等效惩罚（用于偏好省 TNT 的解）
 * @param tickWeight 每个飞行 tick 的等效惩罚（用于偏好飞行时间短的解）
 */
data class ScoringConfig(
    val errorWeight: Double = 1.0,
    val tntWeight: Double = 0.0,
    val tickWeight: Double = 0.0,
)

/**
 * 终点判定方式。
 *
 * ============================================================
 * 两种模式的物理含义完全不同
 * ============================================================
 *
 * [LANDING] 落点模式
 *   珍珠**自然落地**的位置就是终点。
 *   误差 = 落点 XZ 与目标 XZ 的距离。
 *   适合：把珍珠送到某个地面位置（回家、去基地、跨地形传送）。
 *
 * [INTERCEPT] 拦截模式
 *   珍珠在飞行途中**经过目标 XZ 上空**的那一刻就是终点，不要求落地。
 *   误差 = 整条轨迹上 XZ 最接近目标的那个点与目标的距离。
 *   适合：下界顶层传送（珍珠飞到基岩顶上方时恰好在目标正上方），
 *   或在半空中用活塞 / 水接住珍珠。
 *
 * 两者给出的解完全不同 —— 拦截模式能用**更短的飞行时间**
 * 打到落点模式够不到的远距离目标，因为不必等它落地。
 */
enum class LandingMode(val label: String, val description: String) {
    LANDING(
        "落点模式",
        "珍珠自然落地的位置为终点。只看落点 XZ 与目标的偏差，不需要填 Y。",
    ),
    INTERCEPT(
        "拦截模式",
        "珍珠飞行途中经过目标 XZ 上空的那一刻为终点，不要求落地。"
                + "可覆盖落点模式够不到的远距离目标。",
    ),
}

/**
 * 解算输入：一次完整求解所需的所有前置条件。
 */
data class SolveRequest(
    /** 起爆瞬间珍珠的位置 */
    val origin: Vec3,
    /** 起爆瞬间珍珠已有的速度（玩家投掷初速，通常为 0 或很小） */
    val baseVel: Vec3,
    /** 目标 X */
    val targetX: Double,
    /** 目标 Z */
    val targetZ: Double,
    /** 炮体规格 */
    val cannon: CannonSpec,
    /** 版本运动模型 */
    val profile: VersionProfile,
    /** 世界参数 */
    val world: WorldSpec,
    /** 排序偏好 */
    val scoring: ScoringConfig = ScoringConfig(),
    /**
     * 终点高度约束。非空时要求落点 Y 落在区间内。
     * 用于地狱模式等需要精确控制落地高度的场合。
     */
    val endHeightMin: Double? = null,
    val endHeightMax: Double? = null,
    /** 与原作者一致：珍珠一旦触地即停止搜索更大的 tick */
    val legacyStop: Boolean = true,
    /**
     * 终点判定方式，见 [LandingMode]。
     *
     * INTERCEPT 时忽略 [endHeightMin] / [endHeightMax]：
     * 拦截点发生在半空，用落地高度去约束它自相矛盾。
     */
    val landingMode: LandingMode = LandingMode.LANDING,
)

/**
 * 解算结果汇总。
 */
data class SolveResult(
    /** 按评分升序排列的解（第一条为最优） */
    val solutions: List<Solution>,
    /** 实际评估的 tick 数（早停时小于 maxFlyTicks） */
    val evaluatedTicks: Int,
    /** 耗时（毫秒） */
    val elapsedMs: Long,
    /** 无解时的原因说明 */
    val note: String = "",
)
