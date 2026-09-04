package kbs.core.model

/**
 * 轨迹上的一个采样点。
 *
 * @param tick 自起爆起算的 tick 序号
 * @param pos 该 tick 结束时的位置
 * @param vel 该 tick 结束时的速度
 */
data class TrajectorySample(
    val tick: Int,
    val pos: Vec3,
    val vel: Vec3,
)

/**
 * 一次飞行仿真的完整结果。
 *
 * @param finalPos 终点位置
 * @param finalVel 终点速度
 * @param ticks 实际飞行的 tick 数
 * @param hitGround 是否在终点前触及地面（提前终止）
 * @param samples 沿途采样点，用于绘制轨迹剖面图
 *                按抽稀策略保存，长距离飞行也不会占过多内存
 */
data class TrajectoryResult(
    val finalPos: Vec3,
    val finalVel: Vec3,
    val ticks: Int,
    val hitGround: Boolean,
    val samples: List<TrajectorySample> = emptyList(),
) {
    /** 轨迹最高点（判断是否会撞到天花板） */
    val peakY: Double get() = samples.maxOfOrNull { it.pos.y } ?: finalPos.y

    /** 轨迹结束时的水平飞行距离（自起点算起） */
    fun horizontalTravel(from: Vec3): Double =
        finalPos.horizontalDistanceTo(from)

    /**
     * 转成剖面图数据：(水平距离, 高度)。
     *
     * 放在 model 层而非 UI 层 —— 这样 core 模块保持零 Android 依赖，
     * UI 只负责把它画出来。
     */
    fun profile(origin: Vec3): List<ProfilePoint> =
        if (samples.isEmpty()) listOf(ProfilePoint(0.0, origin.y)) +
                listOf(ProfilePoint(horizontalTravel(origin), finalPos.y))
        else samples.map {
            ProfilePoint(it.pos.horizontalDistanceTo(origin), it.pos.y)
        }
}

/**
 * 剖面图上的一个点。
 *
 * @param distance 距起爆点的水平距离（格）
 * @param height 高度 Y
 */
data class ProfilePoint(
    val distance: Double,
    val height: Double,
)
