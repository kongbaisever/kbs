package kbs.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kbs.core.model.ProfilePoint
import kbs.ui.theme.LocalChartColors
import kotlin.math.abs

/**
 * 飞行剖面图：高度(Y) — 水平距离 曲线。
 *
 * ============================================================
 * 用途
 * ============================================================
 *
 * 一眼看清这一炮是平射还是抛射、峰值多高、会不会撞天花板。
 * 对判断"能否越过地形""落点是否在下界顶"很有帮助，
 * 比看一串数字直观得多。
 *
 * ============================================================
 * 抽稀策略
 * ============================================================
 *
 * 轨迹点可能有几百个，逐个绘制既慢又糊。这里按 [maxPoints] 步进采样，
 * 并且**强制包含终点** —— 终点是最关键的信息（落点），
 * 若被跳过会让曲线看起来"没画完"。
 *
 * ============================================================
 * Float 陷阱
 * ============================================================
 *
 * Canvas 的 drawLine / Offset 都只接受 Float。
 * Kotlin 中 `d / maxD * size.width` 若 d、maxD 都是 Double，
 * 整个表达式会推导成 **Double**，传进 Float 参数会直接编译失败：
 *     "Type mismatch: inferred type is Double but Float was expected"
 * 因此下面的 px()/py() 都显式声明返回 Float 并做 .toFloat()。
 */

@Composable
fun TrajectoryChart(
    points: List<ProfilePoint>,
    modifier: Modifier = Modifier,
    /** 地面高度，画一条参考线 */
    groundHeight: Double? = null,
    /** 目标水平距离，画一条竖线标记 */
    targetDistance: Double? = null,
    /** 世界高度上限 */
    ceiling: Double = 256.0,
    /** 最多绘制的点数（超出则抽稀） */
    maxPoints: Int = 160,
) {
    if (points.size < 2) return

    // ★ 全部走 LocalChartColors，深浅主题各一套。
    //   硬编码的深色系辅助线在浅色底上会淡到看不见。
    val cc = LocalChartColors.current
    val lineColor = cc.line
    val guideColor = cc.guide
    val peakColor = cc.peak
    val groundColor = cc.ground

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // ---- 计算坐标范围 ----
        val maxD = points.maxOf { it.distance }.coerceAtLeast(1e-6)
        val maxDAll = listOfNotNull(maxD, targetDistance).max()
        val minY = minOf(points.minOf { it.height }, groundHeight ?: Double.MAX_VALUE)
        val maxY = maxOf(points.maxOf { it.height }, ceiling * 0.0)
        val spanY = (maxY - minY).coerceAtLeast(1e-6)

        // 留边距，避免曲线贴边
        val padL = 6f
        val padR = 6f
        val padT = 8f
        val padB = 8f
        val innerW = w - padL - padR
        val innerH = h - padT - padB
        if (innerW <= 0f || innerH <= 0f) return@Canvas

        // ★ 必须显式 Float：Double 传进 drawLine/Offset 会编译失败
        fun px(d: Double): Float =
            (padL + (d / maxDAll * innerW.toDouble()).toFloat())
                .coerceIn(padL, w - padR)

        fun py(y: Double): Float =
            (padT + ((maxY - y) / spanY * innerH.toDouble()).toFloat())
                .coerceIn(padT, h - padB)

        // ---- 地面参考线 ----
        if (groundHeight != null && groundHeight in minY..maxY) {
            drawLine(
                color = groundColor,
                start = Offset(padL, py(groundHeight)),
                end = Offset(w - padR, py(groundHeight)),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
            )
        }

        // ---- 目标竖线 ----
        if (targetDistance != null && targetDistance <= maxDAll) {
            drawLine(
                color = guideColor,
                start = Offset(px(targetDistance), padT),
                end = Offset(px(targetDistance), h - padB),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
            )
        }

        // ---- 轨迹曲线（抽稀，但强制含终点）----
        val step = (points.size / maxPoints).coerceAtLeast(1)
        val sampled = mutableListOf<ProfilePoint>()
        for (i in points.indices step step) sampled += points[i]
        val last = points.last()
        if (sampled.isEmpty() || sampled.last() !== last) sampled += last

        val path = Path()
        sampled.forEachIndexed { i, p ->
            val x = px(p.distance)
            val y = py(p.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5f),
        )

        // ---- 峰值标记 ----
        val peak = points.maxByOrNull { it.height }
        if (peak != null && abs(peak.height - maxY) < 1e-9) {
            drawCircle(
                color = peakColor,
                radius = 4f,
                center = Offset(px(peak.distance), py(peak.height)),
            )
        }

        // ---- 落点标记 ----
        drawCircle(
            color = guideColor,
            radius = 3.5f,
            center = Offset(px(last.distance), py(last.height)),
        )
    }
}
