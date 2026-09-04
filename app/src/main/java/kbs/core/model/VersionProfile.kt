package kbs.core.model

/**
 * Minecraft 版本化运动模型。
 *
 * ★ 1.21.2 是抛射物运动学的重要分界：
 *
 *   1.21.2 之前（Legacy）：Position → Drag → Acceleration
 *     先按当前速度位移，再施加阻力，最后加减速度。
 *
 *   1.21.2 起（ADP）    ：Acceleration → Drag → Position
 *     先加减速度，再施加阻力，最后位移。
 *
 * 两种顺序对应不同的闭式解和不同的终端速度，
 * 不能通过替换 g/f 相互复用 —— 必须各自独立实现。
 *
 * 参考：Minecraft Wiki「Projectile」「Entity」条目对 1.21.2 顺序变更的记录。
 *
 * @param order tick 内三个阶段的先后顺序
 * @param gravity 每 tick 重力加速度（正值，施加时取减）
 * @param drag 每 tick 阻力系数（速度乘数）
 */
enum class MotionOrder(
    val label: String,
    val description: String,
) {
    /** 1.21.2+：加速度 → 阻力 → 位移 */
    ADP(
        "ADP (1.21.2+)",
        "加速度 → 阻力 → 位移。与原作者 Python 代码的积分顺序一致。",
    ),

    /** 1.11–1.21.1：位移 → 阻力 → 加速度 */
    LEGACY(
        "Legacy (1.11–1.21.1)",
        "位移 → 阻力 → 加速度。旧版本顺序，与 1.21.2+ 不可互换。",
    ),
}

data class VersionProfile(
    val id: String,
    val label: String,
    val order: MotionOrder,
    val gravity: Double = DEFAULT_GRAVITY,
    val drag: Double = DEFAULT_DRAG,
) {
    companion object {
        /** 每 tick 重力加速度 */
        const val DEFAULT_GRAVITY = 0.03

        /**
         * 每 tick 阻力系数。
         *
         * ★ 必须是 float32 精度下的 0.99，而不是十进制 0.99。
         *   Minecraft 内部该值以单精度参与运算，原作者 Python 用
         *   `np.float64(np.float32(0.99))` 来复现这一点。
         *
         *   Kotlin 中 `0.99f.toDouble()` 完全等价：
         *   取出 float32 最接近 0.99 的那个值，再无损转成 double。
         */
        val DEFAULT_DRAG: Double = 0.99f.toDouble()

        /** 1.21.2 及以上 */
        val ADP = VersionProfile(
            id = "adp",
            label = "1.21.2+",
            order = MotionOrder.ADP,
        )

        /** 1.11 – 1.21.1 */
        val LEGACY = VersionProfile(
            id = "legacy",
            label = "1.11–1.21.1",
            order = MotionOrder.LEGACY,
        )

        val ALL = listOf(ADP, LEGACY)

        fun fromId(id: String): VersionProfile =
            ALL.find { it.id == id } ?: ADP
    }
}
