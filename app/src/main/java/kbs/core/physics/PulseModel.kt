package kbs.core.physics

import kbs.core.model.CannonSpec
import kbs.core.model.Direction
import kbs.core.model.MotionOrder
import kbs.core.model.Vec3
import kbs.core.model.VersionProfile
import kbs.core.model.YAccumMode
import kotlin.math.abs
import kotlin.math.round

/**
 * 脉冲（TNT）矢量模型。
 *
 * ============================================================
 * 物理图像
 * ============================================================
 *
 * 矢量炮有两组爆炸点（俗称红/蓝 TNT），分别记为 A、B。
 * 设单颗 TNT 在水平面上产生的速度分量为 `xz`，则两组的基向量为：
 *
 *   主方向为 ±Z（N/S）时：
 *     A = (+xz, +xz)   即 X 正向、Z 主向
 *     B = (−xz, +xz)   即 X 负向、Z 主向
 *
 *   主方向为 ±X（E/W）时：
 *     A = (+xz, +xz)   即 X 主向、Z 正向
 *     B = (+xz, −xz)   即 X 主向、Z 负向
 *
 * 投入 m 颗 A、n 颗 B 后，水平初速度为整数线性组合：
 *
 *     v = m·A + n·B
 *
 * 展开即得原作者代码中的形式（m、n 同号时）：
 *     主方向分量 = (m + n)·xz
 *     副方向分量 = (m − n)·xz
 *
 * 这正是 360° 矢量炮用两组对角爆炸点覆盖 X-Z 平面的原理。
 *
 * ★ m、n 的符号由目标相对位置自然决定：
 *   主方向分量为负时 m+n 为负，珍珠自然朝反向飞行。
 *   N/W 方向额外交换 m、n，等价于把副方向镜像翻转。
 *
 * ============================================================
 * 闭式解 vs 仿真
 * ============================================================
 *
 * 本文件提供闭式解，仅用于**快速生成候选 (m,n)**。
 * 任何候选都必须经 [Integrator] 逐 tick 仿真验证后才进入结果列表 ——
 * 因为解析公式与游戏真实结果存在浮点与碰撞终止的差异。
 */
object PulseModel {

    /**
     * 水平位移系数 D(t)：单位初速度在 t 个 tick 后的水平位移。
     *
     *   ADP    (1.21.2+)：第 k 个 tick 的位移为 v₀·f^k，
     *           求和得 D(t) = f·(1−f^t)/(1−f)
     *           —— 与原作者 Python 中的系数一致。
     *
     *   Legacy (旧版)   ：第 k 个 tick 的位移为 v₀·f^(k−1)，
     *           求和得 D(t) = (1−f^t)/(1−f)
     */
    fun horizontalDisplacementFactor(
        ticks: Int,
        profile: VersionProfile,
    ): Double {
        val f = profile.drag
        val t = ticks.coerceAtLeast(0)
        val pow = Math.pow(f, t.toDouble())
        return when (profile.order) {
            MotionOrder.ADP -> f * (1.0 - pow) / (1.0 - f)
            MotionOrder.LEGACY -> (1.0 - pow) / (1.0 - f)
        }
    }

    /**
     * 单位脉冲在 t tick 内的水平位移，再乘 2。
     *
     * 因子 2 的来源：主方向分量 (m+n) 与副方向分量 (m−n) 联立时，
     * 解出 m 需要 (Δ主+Δ副)/2。原作者把它并入分母：
     *     kp = 2·xz·D(t)
     * 于是 m = (Δz + Δx) / kp。
     */
    fun pulseScale(
        ticks: Int,
        spec: CannonSpec,
        profile: VersionProfile,
    ): Double = 2.0 * spec.motionPerPulseXZ *
            horizontalDisplacementFactor(ticks, profile)

    /**
     * 由水平位移反解两组的脉冲数 (m, n)。
     *
     * @param deltaX 目标相对起爆点的 X 位移
     * @param deltaZ 目标相对起爆点的 Z 位移
     * @param direction 主方向
     * @return Pair(m, n)
     */
    fun solvePulses(
        deltaX: Double,
        deltaZ: Double,
        ticks: Int,
        direction: Direction,
        spec: CannonSpec,
        profile: VersionProfile,
    ): Pair<Int, Int> {
        val kp = pulseScale(ticks, spec, profile)
        if (abs(kp) < 1e-12) return Pair(0, 0)

        var m: Int
        var n: Int
        when (direction) {
            Direction.N, Direction.S -> {
                // 主方向为 ±Z：主分量 = deltaZ，副分量 = deltaX
                m = round((deltaX + deltaZ) / kp).toInt()
                n = round((deltaZ - deltaX) / kp).toInt()
                if (direction == Direction.N) {
                    // 交换等价于副方向镜像
                    val t = m; m = n; n = t
                }
            }

            Direction.E, Direction.W -> {
                // 主方向为 ±X：主分量 = deltaX，副分量 = deltaZ
                m = round((deltaX + deltaZ) / kp).toInt()
                n = round((deltaX - deltaZ) / kp).toInt()
                if (direction == Direction.W) {
                    val t = m; m = n; n = t
                }
            }
        }
        return Pair(m, n)
    }

    /**
     * 由 (m, n) 计算**起爆瞬时**的珍珠速度。
     *
     * ★★ 接口约定（务必遵守，否则会产生极难察觉的定向错误）★★
     *
     *   传入的 (m, n) 必须是**已完成方向交换**的值，
     *   即 [solvePulses] 的返回值。本函数内部**不再交换**。
     *
     *   原作者 Python 的交换只发生一次，就在计算 motion 之前：
     *       m, n = n, m            # 仅 N / W 方向
     *       motion_x = (|m|−|n|)·xz
     *       motion_z = (m + n)·xz
     *
     *   若在调用方交换后本函数又交换一次，N/W 方向会**双重交换**而还原，
     *   导致副方向符号翻转 —— 表现为朝北/朝西的目标全部打偏，
     *   而朝南/朝东完全正常（S/E 不交换，所以症状具有方向选择性，极易漏检）。
     *
     * @param m 已交换的主向脉冲数
     * @param n 已交换的副向脉冲数
     * @param baseVel 珍珠在起爆前已有的速度（玩家投掷初速或采样点速度）
     */
    fun initialVelocity(
        m: Int,
        n: Int,
        direction: Direction,
        spec: CannonSpec,
        baseVel: Vec3,
    ): Vec3 {
        val xz = spec.motionPerPulseXZ
        val yUnit = spec.motionPerPulseY

        // ---- 垂直分量 ----
        val pulsesY = when (spec.yMode) {
            // 原作者：abs(m+n)。异号时会抵消，仅用于复现旧炮
            YAccumMode.SUM_THEN_ABS -> abs(m + n)
            // 矢量：|m|+|n|，正比于总颗数，符合对称炮体几何
            YAccumMode.ABS_THEN_SUM -> abs(m) + abs(n)
        }
        val vy = pulsesY * yUnit + baseVel.y

        // ---- 水平分量 ----
        // ★ 直接使用传入的 m、n，不再交换。
        //   交换已在 solvePulses 中完成（仅 N/W 方向），
        //   此处重复交换会让 N/W 方向的副方向符号翻转。
        //   详见本函数文档中的接口约定。
        val main = (m + n) * xz             // 主方向分量（代数和）
        val sub = (abs(m) - abs(n)) * xz    // 副方向分量

        return when (direction) {
            // 主方向为 Z：副方向落在 X
            Direction.N, Direction.S ->
                Vec3(sub + baseVel.x, vy, main + baseVel.z)

            // 主方向为 X：副方向落在 Z
            Direction.E, Direction.W ->
                Vec3(main + baseVel.x, vy, sub + baseVel.z)
        }
    }

    /**
     * 判断主方向：水平位移较大的轴为主方向轴，符号决定 N/S 或 E/W。
     */
    fun determineDirection(deltaX: Double, deltaZ: Double): Direction =
        if (abs(deltaX) > abs(deltaZ)) {
            if (deltaX > 0) Direction.E else Direction.W
        } else {
            if (deltaZ > 0) Direction.S else Direction.N
        }

    /**
     * 硬上限钳制：超过编码能力的值无法用 8 位权重表示，
     * 若直接使用会导致炮码静默溢出（炮按码配置后打不到目标）。
     */
    fun clampPulse(v: Int, spec: CannonSpec): Int =
        v.coerceIn(-spec.maxPulse, spec.maxPulse)
}
