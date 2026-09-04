package kbs.core.solver

import kbs.core.model.LandingMode
import kbs.core.model.ScoringConfig
import kbs.core.model.Solution
import kbs.core.model.SolveRequest
import kbs.core.model.SolveResult
import kbs.core.model.Vec3
import kbs.core.physics.Integrator
import kbs.core.physics.PulseModel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 珍珠炮解算器。
 *
 * ============================================================
 * 两阶段架构
 * ============================================================
 *
 *   阶段一（闭式解）：对每个候选飞行 tick，用解析公式直接反解
 *           所需的 (m, n)。这一步极快，可在毫秒级遍历数百个 tick。
 *
 *   阶段二（仿真验证）：把每个候选 (m,n) 交给 [Integrator] 做
 *           逐 tick 仿真，得到**真实**落点，再计算误差。
 *
 * 为什么必须有阶段二：
 *   解析公式与游戏真实结果并不完全等同 —— Minecraft Wiki 明确提示
 *   存在浮点差异，且珍珠触地会提前终止飞行。
 *   只用闭式解会给出"理论最优但实机打不中"的配置。
 *
 * ============================================================
 * 早停
 * ============================================================
 *
 * 珍珠触地后不再飞行，因此更大的 tick 没有物理意义。
 * 开启 [SolveRequest.legacyStop] 时，一旦某个候选在到达目标 tick
 * 前触地，即停止向外扩展（与原作者 Python 的行为一致）。
 */
object PearlSolver {

    /**
     * 执行解算。
     *
     * @param maxResults 返回的最优解数量上限
     */
    fun solve(request: SolveRequest, maxResults: Int = 12): SolveResult {
        val t0 = System.currentTimeMillis()

        val deltaX = request.targetX - request.origin.x
        val deltaZ = request.targetZ - request.origin.z
        val direction = PulseModel.determineDirection(deltaX, deltaZ)

        val candidates = mutableListOf<Solution>()
        var evaluated = 0

        val maxTicks = request.cannon.maxFlyTicks.coerceAtLeast(1)

        for (tick in 1..maxTicks) {
            evaluated++

            // ---- 阶段一：闭式解反解 (m, n) ----
            val (rawM, rawN) = PulseModel.solvePulses(
                deltaX = deltaX,
                deltaZ = deltaZ,
                ticks = tick,
                direction = direction,
                spec = request.cannon,
                profile = request.profile,
            )

            // 硬钳制到编码上限：超出 160 编不出炮码，
            // 直接使用会导致炮码静默溢出、打不到目标且查不出原因
            val m = PulseModel.clampPulse(rawM, request.cannon)
            val n = PulseModel.clampPulse(rawN, request.cannon)

            // ---- 阶段二：逐 tick 仿真验证真实落点 ----
            val vel = PulseModel.initialVelocity(
                m = m,
                n = n,
                direction = direction,
                spec = request.cannon,
                baseVel = request.baseVel,
            )

            // ★ 搜索阶段不采样轨迹，避免为几百个候选各分配一个列表。
            //   轨迹只给最终展示的 Top N 补算，见下方 refine。
            val traj = if (request.landingMode == LandingMode.INTERCEPT) {
                // 拦截模式：只飞 tick 个 tick，取整条轨迹上
                // XZ 最接近目标的那一刻
                Integrator.findClosestApproach(
                    initialPos = request.origin,
                    initialVel = vel,
                    maxTicks = tick,
                    profile = request.profile,
                    groundHeight = request.world.groundHeight,
                    targetX = request.targetX,
                    targetZ = request.targetZ,
                )
            } else {
                // 落点模式：仿真到**真正落地**，不受 tick 限制。
                //
                // 闭式解只保证"第 tick 时刻位于目标 XZ"，
                // 但那一刻珍珠通常还在半空 —— 它还会继续飞，
                // 最终落地点会**飞过**目标。
                // 必须一路飞到落地，用真实落地点计算误差。
                Integrator.simulateUntilGround(
                    initialPos = request.origin,
                    initialVel = vel,
                    maxTicks = maxTicks,
                    profile = request.profile,
                    groundHeight = request.world.groundHeight,
                )
            }

            // 触地即停：珍珠提前落地，更大的 tick 已无意义。
            //
            // ★ 落点模式下不早停：每个候选都是"换一组 m/n 重打一炮"，
            //   落地时间各不相同，不能因为某个候选落地了就停止搜索。
            val stopEarly = request.legacyStop &&
                    request.landingMode == LandingMode.INTERCEPT
            if (traj.hitGround && stopEarly) break

            // 落点模式：飞满仍未落地说明弹道过高，永远到不了地面，
            // 对"落在哪"这个问题没有答案
            if (request.landingMode == LandingMode.LANDING && !traj.hitGround) {
                continue
            }

            // ---- 终点高度约束 ----
            // 仅落点模式有效：拦截点发生在半空，
            // 用"落地高度区间"去约束它自相矛盾。
            //
            // 注意区间是 [min, max] 的**闭区间**且 min <= max，
            // 不能退化成一个点（历史上曾退化成 [128,128]，
            // 浮点上几乎不可能恰好命中，导致永远无解）。
            if (request.landingMode == LandingMode.LANDING) {
                val minY = request.endHeightMin
                val maxY = request.endHeightMax
                if (minY != null && traj.finalPos.y < minY) continue
                if (maxY != null && traj.finalPos.y > maxY) continue
            }

            val error = traj.finalPos.horizontalDistanceTo(
                Vec3(request.targetX, traj.finalPos.y, request.targetZ)
            )

            candidates += Solution(
                m = m,
                n = n,
                direction = direction,
                // ★ 用 traj.ticks 而非循环变量 tick：
                //   · 拦截模式 → 最接近目标那一刻的 tick
                //   · 落点模式 → 真正落地的 tick
                //   两者都可能与"闭式解反解时用的 tick"不同，
                //   而玩家关心的正是实际发生的时刻。
                flyTicks = traj.ticks,
                landing = traj.finalPos,
                error = error,
                totalTnt = abs(m) + abs(n),
                peakY = traj.peakY,
                hitGroundEarly = traj.hitGround,
            )
        }

        val sorted = candidates
            .sortedWith(
                compareBy<Solution> { score(it, request.scoring) }
                    .thenBy { it.totalTnt }
                    .thenBy { it.flyTicks }
            )
            .take(maxResults)
            // ★ 只为最终展示的这几条补算轨迹：
            //   搜索阶段不采样以保证速度，这里重跑一次带采样的仿真。
            //   结果完全一致（同样的初速与 tick），只是额外记录了沿途点。
            .map { refine(it, request) }

        val note = when {
            sorted.isEmpty() -> unsatisfiableReason(request)
            !sorted.first().encodable ->
                "最优解的脉冲数超出 8 位编码上限 160，炮码无法正确表示。"
            // 误差过大时也要提示：有解不等于能接受，
            // 几十格的偏差玩家会以为算错了
            sorted.first().error > FAR_THRESHOLD ->
                "最优解仍有 ${"%.1f".format(sorted.first().error)} 格偏差。" +
                        suggestFix(request, sorted.first())
            else -> ""
        }

        return SolveResult(
            solutions = sorted,
            evaluatedTicks = evaluated,
            elapsedMs = System.currentTimeMillis() - t0,
            note = note,
        )
    }

    /** 多目标加权评分，越小越优 */
    private fun score(s: Solution, cfg: ScoringConfig): Double =
        s.error * cfg.errorWeight +
                s.totalTnt * cfg.tntWeight +
                s.flyTicks * cfg.tickWeight

    /** 超过这个偏差就提示用户，而不是默默给出一组烂解 */
    private const val FAR_THRESHOLD = 8.0

    /**
     * 无解时的原因诊断。
     *
     * "算不出来"本身不解决问题，用户需要知道**下一步该改什么**。
     * 因此这里逐条排查最常见的几种成因，给出可执行的建议。
     */
    private fun unsatisfiableReason(request: SolveRequest): String {
        val dist = hypot(request.targetX - request.origin.x,
            request.targetZ - request.origin.z)

        return buildString {
            append("未找到可行解。")
            append("目标距离约 ${"%.0f".format(dist)} 格。")
            append("可能原因：")

            if (request.landingMode == LandingMode.LANDING) {
                append("① 珍珠必须落地，而落地过程会把它带过目标 —— ")
                append("远距离目标建议改用「拦截模式」；")
            } else {
                append("① 目标超出该炮的最大射程；")
            }
            if (request.endHeightMin != null || request.endHeightMax != null) {
                append("② 落点高度限制过严，")
                append("区间 [${request.endHeightMin}, ${request.endHeightMax}] 内无解，")
                append("可尝试放宽或清空；")
            } else {
                append("② 基准状态（位置/速度）填写有误；")
            }
            append("③ 脉冲上限 160 不足以产生所需的初速。")
        }
    }

    /**
     * 有解但偏差较大时的改进建议。
     */
    private fun suggestFix(request: SolveRequest, best: Solution): String {
        if (request.landingMode == LandingMode.INTERCEPT) {
            return "当前已是拦截模式；可尝试调整目标坐标或改用落点模式。"
        }
        // 落点模式下误差大，往往是"必须落地"造成的 ——
        // 拦截模式不受此约束，值得一试
        return "落点模式要求珍珠真正落地，" +
                "落地过程会把它带过目标。" +
                "若只需珍珠经过目标上空，改用「拦截模式」通常能得到更好的结果。"
    }

    /**
     * 为单条解补算飞行剖面。
     *
     * 重跑一次带采样的仿真。初速与 tick 与搜索时完全相同，
     * 因此落点一致，只是额外记录了沿途采样点。
     */
    private fun refine(s: Solution, request: SolveRequest): Solution {
        val vel = PulseModel.initialVelocity(
            m = s.m,
            n = s.n,
            direction = s.direction,
            spec = request.cannon,
            baseVel = request.baseVel,
        )
        val traj = Integrator.simulateUntilGround(
            initialPos = request.origin,
            initialVel = vel,
            maxTicks = s.flyTicks,
            profile = request.profile,
            groundHeight = request.world.groundHeight,
            // 每 2 tick 一个点：足够平滑，又不至于存太多。
            //
            // ★ 注意：simulateUntilGround 对 t==maxTicks 强制采样
            //   （见其实现中的 `|| t == n`），所以终点一定在列表里，
            //   不会因为步长为 2 而漏掉最终落点。
            sampleEvery = 2,
        )
        return s.copy(profile = traj.profile(request.origin))
    }
}
