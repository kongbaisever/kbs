package kbs.core.physics

import kbs.core.model.MotionOrder
import kbs.core.model.TrajectoryResult
import kbs.core.model.TrajectorySample
import kbs.core.model.Vec3
import kbs.core.model.VersionProfile

/**
 * 版本化逐 tick 飞行仿真器 —— **物理的唯一权威来源**。
 *
 * ★ 设计原则：闭式公式只用于快速筛选候选解，
 *   任何进入结果列表的解都必须经过这里的逐 tick 仿真验证。
 *
 *   理由：Minecraft 的实际运动受浮点累积、碰撞终止、
 *   版本相关的阶段顺序共同影响。解析公式与游戏结果并不完全等同
 *   （Minecraft Wiki 明确提示了浮点差异），
 *   只有逐步推进才能复现真实落点。
 *
 * 两种顺序（务必区分，不可互相替换）：
 *
 *   ADP    (1.21.2+)：加速度 → 阻力 → 位移
 *     v.y -= g
 *     v   *= f
 *     pos += v
 *
 *   Legacy (1.11–1.21.1)：位移 → 阻力 → 加速度
 *     pos += v
 *     v   *= f
 *     v.y -= g
 *
 * 原作者 Python 代码采用 ADP 顺序，因此其炮对应 1.21.2+ 版本。
 */
object Integrator {

    /**
     * 推进单个 tick。
     *
     * @return Pair(新位置, 新速度)
     */
    fun step(
        pos: Vec3,
        vel: Vec3,
        profile: VersionProfile,
    ): Pair<Vec3, Vec3> {
        val g = profile.gravity
        val f = profile.drag

        return when (profile.order) {
            MotionOrder.ADP -> {
                // ① 加速度：仅 Y 受重力
                val ay = vel.y - g
                // ② 阻力：三个分量同乘
                val nv = Vec3(vel.x * f, ay * f, vel.z * f)
                // ③ 位移
                Pair(pos + nv, nv)
            }

            MotionOrder.LEGACY -> {
                // ① 位移：使用本 tick 开始时的速度
                val np = pos + vel
                // ② 阻力
                val dv = Vec3(vel.x * f, vel.y * f, vel.z * f)
                // ③ 加速度
                val nv = Vec3(dv.x, dv.y - g, dv.z)
                Pair(np, nv)
            }
        }
    }

    /**
     * 仿真固定 tick 数，不检测碰撞。
     * 用于轨迹预览与闭式解校验。
     */
    fun simulate(
        initialPos: Vec3,
        initialVel: Vec3,
        ticks: Int,
        profile: VersionProfile,
        sampleEvery: Int = 0,
    ): TrajectoryResult {
        var pos = initialPos
        var vel = initialVel
        val samples = mutableListOf<TrajectorySample>()

        if (sampleEvery > 0) samples.add(TrajectorySample(0, pos, vel))

        val n = ticks.coerceAtLeast(0)
        for (t in 1..n) {
            val (np, nv) = step(pos, vel, profile)
            pos = np
            vel = nv
            if (sampleEvery > 0 && (t % sampleEvery == 0 || t == n)) {
                samples.add(TrajectorySample(t, pos, vel))
            }
        }

        return TrajectoryResult(
            finalPos = pos,
            finalVel = vel,
            ticks = n,
            hitGround = false,
            samples = samples,
        )
    }

    /**
     * 仿真至指定 tick 数，**触及地面即提前终止**。
     *
     * 这是解算器使用的主入口：珍珠一旦落地就停止飞行，
     * 之后的 tick 没有物理意义。
     *
     * @param groundHeight 地面高度，pos.y <= 该值时判定落地
     */
    fun simulateUntilGround(
        initialPos: Vec3,
        initialVel: Vec3,
        maxTicks: Int,
        profile: VersionProfile,
        groundHeight: Double,
        sampleEvery: Int = 0,
    ): TrajectoryResult {
        var pos = initialPos
        var vel = initialVel
        val samples = mutableListOf<TrajectorySample>()

        if (sampleEvery > 0) samples.add(TrajectorySample(0, pos, vel))

        val n = maxTicks.coerceAtLeast(0)
        for (t in 1..n) {
            val (np, nv) = step(pos, vel, profile)
            pos = np
            vel = nv

            if (sampleEvery > 0 && (t % sampleEvery == 0 || t == n)) {
                samples.add(TrajectorySample(t, pos, vel))
            }

            // 落地判定：与地面接触即终止
            if (pos.y <= groundHeight) {
                return TrajectoryResult(
                    finalPos = pos,
                    finalVel = vel,
                    ticks = t,
                    hitGround = true,
                    samples = samples,
                )
            }
        }

        return TrajectoryResult(
            finalPos = pos,
            finalVel = vel,
            ticks = n,
            hitGround = false,
            samples = samples,
        )
    }

    /**
     * 仿真并找出整条轨迹上 **XZ 最接近目标** 的那个点。
     *
     * ============================================================
     * 用途：拦截模式
     * ============================================================
     *
     * 拦截模式不要求珍珠落地 —— 只要它在飞行途中某个 tick
     * 恰好经过目标 XZ 的上空，就算命中。此时珍珠可能还在半空。
     *
     * 因此不能用 [simulateUntilGround] 的 finalPos 计算误差：
     * 那个点是落地点，而落地点往往已经**飞过**了目标。
     *
     * ============================================================
     * 为什么不采样后遍历
     * ============================================================
     *
     * 解算器会对几百个 tick 候选各调一次，若每次都分配一个
     * 采样列表，GC 压力会直接拖垮搜索。这里只在循环中
     * 维护"当前最优"，**零额外分配**。
     *
     * @return 最接近目标那一刻的状态（ticks 为该时刻，非 maxTicks）
     */
    fun findClosestApproach(
        initialPos: Vec3,
        initialVel: Vec3,
        maxTicks: Int,
        profile: VersionProfile,
        groundHeight: Double,
        targetX: Double,
        targetZ: Double,
    ): TrajectoryResult {
        var pos = initialPos
        var vel = initialVel

        var bestPos = pos
        var bestVel = vel
        var bestTick = 0
        var bestErr = pos.horizontalDistanceTo(Vec3(targetX, pos.y, targetZ))

        val n = maxTicks.coerceAtLeast(0)
        for (t in 1..n) {
            val (np, nv) = step(pos, vel, profile)
            pos = np
            vel = nv

            val err = pos.horizontalDistanceTo(Vec3(targetX, pos.y, targetZ))
            if (err < bestErr) {
                bestErr = err
                bestPos = pos
                bestVel = vel
                bestTick = t
            }

            // 触地后珍珠不再飞行，之后的轨迹没有物理意义
            if (pos.y <= groundHeight) {
                return TrajectoryResult(
                    finalPos = bestPos,
                    finalVel = bestVel,
                    ticks = bestTick,
                    hitGround = true,
                )
            }
        }

        return TrajectoryResult(
            finalPos = bestPos,
            finalVel = bestVel,
            ticks = bestTick,
            hitGround = false,
        )
    }

    /**
     * 二分查找珍珠落地的 tick 数。
     *
     * 用于解算前的区间估计：先确定「从某初速出发多久会落地」，
     * 只需搜索该 tick 之前的候选，避免对不可能的飞行时间做无用计算。
     *
     * @return 落地的 tick 数；若 maxTicks 内未落地则返回 maxTicks
     */
    fun findGroundTick(
        initialPos: Vec3,
        initialVel: Vec3,
        maxTicks: Int,
        profile: VersionProfile,
        groundHeight: Double,
    ): Int {
        // 若初速向上，先粗步长扫描找到下落区间，再二分细化
        var low = 0
        var high = maxTicks

        // 先在稀疏网格上找第一个落地点
        val stride = 4
        var found = -1
        for (t in stride..maxTicks step stride) {
            val r = simulate(initialPos, initialVel, t, profile)
            if (r.finalPos.y <= groundHeight) {
                found = t
                break
            }
        }

        if (found < 0) return maxTicks

        high = found
        low = (found - stride).coerceAtLeast(0)

        // 二分收敛到精确 tick
        while (low + 1 < high) {
            val mid = (low + high) / 2
            val r = simulate(initialPos, initialVel, mid, profile)
            if (r.finalPos.y <= groundHeight) high = mid else low = mid
        }
        return high
    }
}
