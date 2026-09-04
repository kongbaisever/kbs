package kbs.core.model

/**
 * Y 方向动量的累加方式。
 *
 * ★ 这是本项目最重要的物理分歧点，两种模式的差异在 m、n 异号时显现。
 *
 * 真实物理（Minecraft Explosion）：
 *   每颗 TNT 对珍珠施加一个完整的速度矢量，总速度是逐颗**矢量和**：
 *       v = Σ (magnitude_i × 单位方向_i)
 *   因此 Y 分量等于各颗 TNT 方向 y 分量的代数和，不存在独立的"净数量"规则。
 *
 * 对于两侧对称、两组爆炸点均位于珍珠下方的典型矢量炮，
 * 每颗 TNT 都贡献同向的向上分量，此时总量正比于**总颗数** |m|+|n|。
 */
enum class YAccumMode(
    val label: String,
    val description: String,
) {
    /**
     * 原作者模式：abs(m + n)
     *
     * 与原作者 Python 逐字符一致，该炮已实机验证可用。
     * 注意：当 m、n 异号时会相互抵消，物理上相当于假设两组 TNT
     * 的 Y 分量符号相反 —— 对标准对称炮体并不成立。
     * 仅建议用于复现已标定的旧炮。
     */
    SUM_THEN_ABS(
        "abs(m+n) 原作者",
        "与原作者 Python 一致，旧炮已实机验证。异号时会抵消，非通用物理。",
    ),

    /**
     * 矢量模式：|m| + |n|
     *
     * 正比于 TNT 总颗数，符合"每颗都贡献向上分量"的对称炮体几何。
     * 需要炮体满足：两组爆炸点均位于珍珠下方且方向分量同号。
     * 新炮建议使用此项，并用实测落点标定。
     */
    ABS_THEN_SUM(
        "|m|+|n| 矢量",
        "正比于 TNT 总颗数，符合对称炮体几何。新炮推荐，需实测标定。",
    ),
}

/**
 * 四个主方向。编码值与硬件真值表绑定，需与炮的接线一致。
 */
enum class Direction(val code: String, val label: String) {
    N("00", "北 -Z"),
    W("01", "西 -X"),
    E("10", "东 +X"),
    S("11", "南 +Z"),
    ;

    /** 主方向在世界中的单位偏移（XZ 平面） */
    fun unitX(): Double = when (this) {
        E -> 1.0
        W -> -1.0
        else -> 0.0
    }

    fun unitZ(): Double = when (this) {
        S -> 1.0
        N -> -1.0
        else -> 0.0
    }
}

/**
 * 炮体规格。
 *
 * ★ 关于 motionPerPulseXZ / motionPerPulseY：
 *
 *   这两个数**不是** Minecraft 的通用物理常数。
 *   真实的单颗 TNT 冲量由爆炸距离、暴露度（exposure）、
 *   单位方向向量、爆炸威力、击退抗性共同决定，且随炮体几何变化。
 *
 *   这里的 0.602679… / 0.004435… 是**特定炮体在特定版本下的拟合标定值**
 *   —— 对应原作者那台炮的几何、暴露度和珍珠位置。
 *   它们不能外推到其他炮体、其他高度或改变过的篮子结构。
 *
 *   若要适配新炮，应重新标定：固定珍珠与 TNT 坐标，
 *   测出单颗 TNT 的速度增量后填入。
 */
data class CannonSpec(
    /** 单颗 TNT 产生的水平方向速度分量（标定值） */
    val motionPerPulseXZ: Double = 0.6026793588895138,

    /** 单颗 TNT 产生的垂直方向速度分量（标定值） */
    val motionPerPulseY: Double = 0.004435058914919521,

    /** Y 动量累加方式 */
    val yMode: YAccumMode = YAccumMode.SUM_THEN_ABS,

    /**
     * 单组脉冲数上限。
     *
     * ★ 160 首先是**编码上限**而非物理上限：
     *   八位权重 [80,40,20,10,4,3,2,1] 全选之和 = 160。
     *
     *   真实的 TNT 数量还受炮体承载、同 tick 爆炸时序、
     *   遮挡导致 exposure 下降等工程因素限制。
     *   因此这里同时作为搜索边界与编码边界。
     */
    val maxPulse: Int = 160,

    /**
     * 解算时允许的最大飞行 tick 数。
     * 珍珠落地后即终止，因此实际搜索区间通常远小于此值。
     */
    val maxFlyTicks: Int = 600,
)

/**
 * 世界参数：与具体炮无关的环境约束。
 */
data class WorldSpec(
    /** 地面高度（下界基岩层顶面 = 128） */
    val groundHeight: Double = 128.0,

    /** 世界高度上限（Java 版建筑上限 Y=256），用作落点合法区间的上界 */
    val ceiling: Double = 256.0,
)

/**
 * 炮体的物理布局参数。
 *
 * 与 [kbs.core.model.CannonSpec] 分开，是因为前者描述
 * **冲量标定**（每脉冲给多少速度），这里描述
 * **几何结构**（各权重槽的方位与间距）。
 * 两者独立变化：换标定值不必改布局，改布局也不必重标定。
 *
 * @param slotSpacing 相邻权重槽之间的方块间距。
 *   常见矢量炮把八个槽沿一条直线等距排开，间距多为 1 或 2。
 * @param dropHeight TNT 相对珍珠起爆点的垂直落差（格）。
 *   标准炮体中爆炸点位于珍珠下方，这个落差决定 Y 分量的方向。
 */
data class LayoutSpec(
    val slotSpacing: Double = 1.0,
    val dropHeight: Double = 1.0,
)
