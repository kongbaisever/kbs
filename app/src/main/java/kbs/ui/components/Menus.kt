package kbs.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kbs.ui.theme.ThemeMode

/**
 * 顶部下拉菜单。
 *
 * ============================================================
 * 为什么是"菜单 + 二级页面"两层
 * ============================================================
 *
 * 功能越加越多，但绝大多数人只用「计算」这一件事。
 * 把设置、隐私政策、开发者信息收进菜单，首屏才能保持干净 ——
 * 折叠板块已经能收起内容，但标题行仍占地方；
 * 二级页面则连标题都不占。
 *
 * 菜单本身只放**入口**，具体内容在各自的全屏对话框里展示，
 * 这样菜单不会越塞越长。
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onDevelopers: () -> Unit,
    onPrivacy: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("⚙ 设置") },
            onClick = {
                onDismiss()
                onSettings()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("👥 开发者") },
            onClick = {
                onDismiss()
                onDevelopers()
            },
        )
        DropdownMenuItem(
            text = { Text("🔒 隐私政策") },
            onClick = {
                onDismiss()
                onPrivacy()
            },
        )
    }
}

/**
 * 通用二级页面：全屏对话框。
 *
 * 用 Dialog + verticalScroll 而非新的 Activity ——
 * 后者要写跳转、传参、返回键处理，成本高得多，
 * 而这里只是展示静态文本，Dialog 足够。
 */
@Composable
fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                // 内容可能很长（隐私政策），必须可滚动，
                // 否则小屏上会被截断且无法查看
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 开发者名单。
 *
 * 这里只是**署名**，没有任何鉴权含义 ——
 * App 没有账号体系，也不做功能分级，
 * 列出贡献者是出于致谢，而非"开发者才能看"。
 */
@Composable
fun DevelopersContent() {
    val developers = listOf(
        "kongbai9288" to "审核：算法核验、实机验证与参数标定",
        "元宝" to "设计：架构设计、交互与视觉设计、验证体系",
        "豆包" to "帮助构建：工程实现与迭代",
        "replit" to "早期原型与思路验证",
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "本项目由以下成员共同完成：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        developers.forEach { (name, role) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "算法以原作者 Python 实现为基准，每次改动都会与之逐位比对，"
                    + "确保计算结果不偏离。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 隐私政策（公版）。
 *
 * 本 App 的实际行为确实符合以下每一条：
 *   · 不申请通讯录、位置、相机等敏感权限
 *   · 唯一的网络请求是拉取公告 JSON
 *   · 所有计算在本地完成
 *
 * 写"公版"不是为了敷衍 —— 而是因为行为简单到
 * 确实可以用通用条款完整覆盖，没有需要特别声明的特殊处理。
 */
@Composable
fun PrivacyContent() {
    val sections = listOf(
        "一、我们收集什么" to
                "本应用**不收集、不上传任何个人信息**。\n"
                + "你填写的目标坐标、基准状态、物理模型等全部参数"
                + "仅保存在你的设备本机上，开发者无法获取。",
        "二、网络请求" to
                "应用启动时可能请求一份公告文件（JSON 文本），"
                + "用于展示版本更新与使用说明。\n"
                + "为提升国内加载速度，会依次尝试若干镜像地址，"
                + "全部失败则使用内置内容。\n"
                + "该请求**不包含**你的任何参数、设备标识或位置信息。",
        "三、本地存储" to
                "以下内容保存在应用私有目录中，卸载应用会一并清除：\n"
                + "· 常用目标坐标\n"
                + "· 配置预设（含坐标、基准状态等完整参数）\n"
                + "· 界面偏好（主题、模块展开状态等）\n"
                + "· 崩溃日志（用于排查闪退，可在应用内查看与清除）",
        "四、你主动分享时才离开设备" to
                "本应用**不会自动向外发送任何数据**。\n"
                + "仅当你主动点击「发给朋友」「复制分享码」时，"
                + "配置内容才会以文本或 txt 文件形式传出。\n"
                + "请注意：配置预设中包含你的炮体标定值（坐标与速度），"
                + "分享即等于向对方公开这些参数，请只发给你信任的人。",
        "五、剪贴板" to
                "点击复制炮码或分享码时，内容会写入系统剪贴板。"
                + "本应用不会读取你的剪贴板内容。",
        "六、权限" to
                "本应用仅申请**网络访问**权限（用于上述公告拉取）。\n"
                + "不申请通讯录、位置、相机、麦克风、存储等任何敏感权限。\n"
                + "导出配置文件通过系统分享面板完成，不需要存储权限。",
        "七、第三方" to
                "本应用不含任何第三方统计、广告或数据分析 SDK。\n"
                + "公告托管在公开代码仓库，其服务方可能记录常规访问日志"
                + "（如 IP、User-Agent），这不由本应用控制。",
        "八、儿童隐私" to
                "本应用面向所有年龄段，不针对性地收集儿童信息。",
        "九、政策更新" to
                "若本政策发生实质性变更，会在应用内公告中提示。\n"
                + "继续使用即视为接受更新后的政策。",
        "十、联系我们" to
                "如有疑问，可通过项目仓库的 Issues 反馈。",
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "生效日期：2026 年 9 月",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "本政策说明本应用如何处理你的数据。\n"
                    + "一句话概括：数据在你手机上，我们看不到。",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        sections.forEach { (title, body) ->
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 设置页。
 *
 * 只放**影响使用体验**的开关，不放算法参数 ——
 * 算法参数（版本模型、Y 动量模式、终点判定）属于"计算"范畴，
 * 放在主界面的物理模型区，改完能立刻试算；
 * 藏进设置里反而要来回跳转。
 */
@Composable
fun SettingsDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    "⚙ 设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))

                ThemeChoiceRow(
                    current = themeMode,
                    onSelect = onThemeModeChange,
                )

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))

                SwitchRow(
                    title = "算完自动滚到结果",
                    description = "关闭时结果区不会自动跳动，" +
                            "适合反复微调参数试算。",
                    checked = autoScroll,
                    onCheckedChange = onAutoScrollChange,
                )

                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 设置项：带开关的一行。
 */
@Composable
fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 主题选择行。
 */
@Composable
fun ThemeChoiceRow(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "外观",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "深色适合夜间与长时间使用；浅色在对照游戏截图核对参数时更清晰。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(onClick = { onSelect(mode) }) {
                        Text(
                            mode.label,
                            color = if (mode == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
