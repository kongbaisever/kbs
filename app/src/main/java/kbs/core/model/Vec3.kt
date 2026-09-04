package kbs.core.model

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 三维向量（位置或速度）。
 *
 * 不可变值类：所有运算返回新实例，避免在解算循环里产生意外别名修改。
 * 使用 Double：Minecraft 内部混用 float/double，而本工具需要亚方块级精度，
 * 统一用 Double 可避免长距离飞行时的累积舍入误差。
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    /** 水平距离（忽略 Y），用于落点误差评估 */
    fun horizontalDistanceTo(o: Vec3): Double = hypot(x - o.x, z - o.z)

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun copyWith(x: Double = this.x, y: Double = this.y, z: Double = this.z): Vec3 =
        Vec3(x, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
