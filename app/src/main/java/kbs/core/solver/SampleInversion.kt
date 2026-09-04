package kbs.core.solver

import kbs.core.model.MotionOrder
import kbs.core.model.TrajectoryResult
import kbs.core.model.Vec3
import kbs.core.model.VersionProfile
import kbs.core.physics.Integrator
import kotlin.math.hypot

/**
 * 采样点反推。
 *
 * ============================================================
 * 用途
 * ============================================================
 *
 * 在游戏里用调试界面（如 Carpet 的 /log tnt、投影 mod 的调试框）
 * 读到珍珠在**飞行途中**某个 tick 的位置与速度，
 * 反推出起爆瞬时的状态 (pos₀, vel₀)，供解算器使用。
 *
 * ============================================================
 * 方法：数值反向积分
 * ============================================================
 *
 * 这里刻意**不使用**闭式解析解，而是把 [Integrator] 的每一步
 * 反向执行一遍。理由：
 *   - 自动适配 ADP / Legacy 两种顺序，无需各自推导公式；
 *   - 与正向仿真共享同一套代码，不存在"正反不一致"的隐患；
 *   - 浮点行为与正向完全对称，反推精度可控。
 *
 * ADP    正向：v.y -= g → v *= f → pos += v
 *        反向：pos -= v → v /= f → v.y += g
 *
 * Legacy 正向：pos += v → v *= f → v.y -= g
 *        反向：v /= f → v.y += g → pos -= v
 *
 * ============================================================
 * 注意事项
 * ============================================================
 *
 * 反向积分需要连续除以 f(=0.99)，等价于乘以 1/0.99 的 t 次方。
 * 采样 tick 越大，放大倍数越高：
 *
 *     tick=10  → 1.11 倍
 *     tick=72  → 2.06 倍
 *     tick=300 → 20.1 倍
 *     tick=600 → 403 倍
 *
 * 因此**采样时刻越早越好**，晚期的读数误差会被急剧放大。
 */
object SampleInversion {

    /** 反推结果 */
    data class InversionResult(
        /** 起爆瞬时位置 */
        val origin: Vec3,
        /** 起爆瞬时速度 */
        val velocity: Vec3,
        /** 自洽性校验误差：反推后再正向仿真回采样点的位置偏差 */
        val consistencyError: Double,
        /** 误差等级 */
        val level: Level,
    ) {
        enum class Level(val mark: String, val text: String) {
            GOOD("✓", "优秀，数据可靠"),
            FAIR("✓", "可用，误差在容许范围"),
            WARN("⚠", "勉强，建议减小采样 tick"),
            BAD("✗", "不自洽，请检查数据"),
        }
    }

    /**
     * 由采样点反推起爆瞬时状态。
     *
     * @param samplePos 采样时刻的位置
     * @param sampleVel 采样时刻的速度
     * @param sampleTicks 采样发生在起爆后第几个 tick
     */
    fun invert(
        samplePos: Vec3,
        sampleVel: Vec3,
        sampleTicks: Int,
        profile: VersionProfile,
    ): InversionResult {
        var pos = samplePos
        var vel = sampleVel
        val f = profile.drag
        val g = profile.gravity

        val t = sampleTicks.coerceAtLeast(0)
        repeat(t) {
            when (profile.order) {
                MotionOrder.ADP -> {
                    // 撤销位移
                    pos = pos - vel
                    // 撤销阻力
                    vel = Vec3(vel.x / f, vel.y / f, vel.z / f)
                    // 撤销加速度
                    vel = Vec3(vel.x, vel.y + g, vel.z)
                }

                MotionOrder.LEGACY -> {
                    // 撤销阻力
                    vel = Vec3(vel.x / f, vel.y / f, vel.z / f)
                    // 撤销加速度
                    vel = Vec3(vel.x, vel.y + g, vel.z)
                    // 撤销位移
                    pos = pos - vel
                }
            }
        }

        // ---- 自洽性校验：反推后再正向跑回去，看能否回到采样点 ----
        val check: TrajectoryResult = Integrator.simulate(
            initialPos = pos,
            initialVel = vel,
            ticks = t,
            profile = profile,
        )
        val err = hypot(
            check.finalPos.x - samplePos.x,
            hypot(check.finalPos.y - samplePos.y, check.finalPos.z - samplePos.z),
        )

        val level = when {
            err < 1e-3 -> InversionResult.Level.GOOD
            err < 1e-2 -> InversionResult.Level.FAIR
            err < 1e-1 -> InversionResult.Level.WARN
            else -> InversionResult.Level.BAD
        }

        return InversionResult(
            origin = pos,
            velocity = vel,
            consistencyError = err,
            level = level,
        )
    }

    /**
     * 反推的误差放大倍数（1/f^t）。
     * 用于 UI 提示：数值过大时建议更早采样。
     */
    fun amplification(ticks: Int, profile: VersionProfile): Double =
        Math.pow(1.0 / profile.drag, ticks.coerceAtLeast(0).toDouble())
}
