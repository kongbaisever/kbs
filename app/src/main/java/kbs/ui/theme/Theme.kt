package kbs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 应用主题。
 *
 * 采用下界风格的暗色调（深紫底 + 末影珍珠青绿点缀），
 * 长时间在昏暗环境看屏幕时更舒适，也更贴合工具的使用场景。
 *
 * 与 AndroidManifest 中的 Theme.KbsPearl 配套：
 * 窗口背景同样是深色，避免启动时出现白色闪屏。
 */

// —— 主色：末影珍珠的青绿 ——
val PearlTeal = Color(0xFF12B39B)
val PearlTealLight = Color(0xFF8FF0DF)

// —— 辅助：下界的紫 ——
val NetherPurple = Color(0xFF7B4FA8)
val NetherPurpleDeep = Color(0xFF2A1B3D)

/**
 * 亮紫，专供**深色主题下的图表**使用。
 *
 * NetherPurple(#7B4FA8) 作为按钮/容器底色很好看，
 * 但当作图表里的目标位置标记线时，它在深色卡片底上
 * 对比度只有 2.95:1（低于非文字元素要求的 3.0:1），
 * 线会糊在背景里。这里提亮到约 5:1。
 */
val NetherPurpleLight = Color(0xFFA872D6)

// —— 中性：深灰紫 ——
val SurfaceDark = Color(0xFF120E1C)
val SurfaceCard = Color(0xFF1B162B)
val TextPrimary = Color(0xFFE8E0F5)
val TextSecondary = Color(0xFFB0A8C0)

// —— 浅色方案的中性色 ——
// 浅色下不能用深色方案的青绿（对比度不足），
// 改用更深的青绿保证在白底上可读（对比度 > 4.5:1）。
val PearlTealDarkOnLight = Color(0xFF00796B)
private val SurfaceLight = Color(0xFFFAF8FD)
private val SurfaceCardLight = Color(0xFFFFFFFF)
private val TextPrimaryLight = Color(0xFF1A1626)
private val TextSecondaryLight = Color(0xFF5A5470)

/**
 * 浅色方案。
 *
 * 存在理由：部分用户在**对着游戏截图核对参数**时更习惯浅色 ——
 * 游戏调试界面本身是深色，若工具也是深色，来回切换时眼睛要反复适应。
 */
private val LightColors = lightColorScheme(
    primary = PearlTealDarkOnLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00332C),

    secondary = Color(0xFF6A3D9A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7D9F5),
    onSecondaryContainer = Color(0xFF2A1245),

    background = SurfaceLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceCardLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF0ECF7),
    onSurfaceVariant = TextSecondaryLight,
)

private val DarkColors = darkColorScheme(
    primary = PearlTeal,
    onPrimary = Color(0xFF04201C),
    primaryContainer = Color(0xFF0B4A42),
    onPrimaryContainer = PearlTealLight,

    secondary = NetherPurple,
    onSecondary = Color.White,
    secondaryContainer = NetherPurpleDeep,
    onSecondaryContainer = Color(0xFFDCC8F0),

    background = SurfaceDark,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,

    error = Color(0xFFFF6B6B),
    onError = Color.White,
    errorContainer = Color(0xFF3A1520),
    onErrorContainer = Color(0xFFFFC9C9),

    outline = Color(0xFF3A3355),
    surfaceVariant = Color(0xFF241E38),
    onSurfaceVariant = TextSecondary,
)

/**
 * 主题模式。
 *
 * SYSTEM 跟随系统；LIGHT / DARK 由用户手动指定。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun fromId(id: String): ThemeMode =
            entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}

/**
 * 强调色（末影珍珠的青绿）。
 *
 * ============================================================
 * 为什么必须是主题感知的
 * ============================================================
 *
 * 深色底上用亮青绿 #12B39B 很醒目；
 * 但同一个颜色放到浅色底（#FAF8FD）上，对比度只有约 2.3:1，
 * **远低于 WCAG 要求的 4.5:1**，用户反馈"浅色模式下看不到字"。
 *
 * 因此在浅色主题下改用深青绿 #00796B（对比度约 5.3:1）。
 * 通过 CompositionLocal 下发，各组件取 `LocalAccent.current` 即可，
 * 无需在每个使用点判断当前是深还是浅。
 */
val LocalAccent = staticCompositionLocalOf { PearlTeal }

/**
 * 图表辅助色。同样需要主题感知 ——
 * 轨迹图的辅助线在浅色底上若沿用深色的灰紫，会淡到看不见。
 */
data class ChartColors(
    val line: Color,
    val guide: Color,
    val ground: Color,
    val target: Color,
    /** 峰值点颜色，与 line 区分开 */
    val peak: Color,
)

val LocalChartColors = staticCompositionLocalOf {
    ChartColors(
        line = PearlTeal,
        guide = Color(0xFF6A6390),
        ground = Color(0xFF8A6A5A),
        target = NetherPurple,
        peak = PearlTealLight,
    )
}

private val DarkChartColors = ChartColors(
    line = PearlTeal,
    guide = Color(0xFF6A6390),
    ground = Color(0xFF8A6A5A),
    target = NetherPurpleLight,
    peak = Color(0xFF8FF0DF),
)

// 浅色下全部加深：保证在近白底上对比度 > 4.5:1
private val LightChartColors = ChartColors(
    line = PearlTealDarkOnLight,
    guide = Color(0xFF4A4370),
    ground = Color(0xFF6B4A38),
    target = Color(0xFF6A3D9A),
    peak = Color(0xFF004D40),
)

@Composable
fun PearlTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(
        LocalAccent provides if (dark) PearlTeal else PearlTealDarkOnLight,
        LocalChartColors provides if (dark) DarkChartColors else LightChartColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
