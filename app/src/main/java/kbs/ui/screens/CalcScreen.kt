package kbs.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kbs.core.model.Solution
import kbs.core.model.VersionProfile
import kbs.core.model.YAccumMode
import kbs.data.Announcement
import kbs.data.AppPrefs
import kbs.ui.CalcViewModel
import kbs.ui.components.CodeLine
import kbs.ui.components.CollapsibleCard
import kbs.ui.components.Field
import kbs.ui.components.InfoRow
import kbs.ui.components.LabeledChoice
import kbs.ui.components.SlotRow
import kbs.ui.components.TrajectoryChart
import kbs.ui.components.SettingsDialog
import kbs.core.model.LandingMode
import kbs.ui.components.AppMenu
import kbs.ui.components.DevelopersContent
import kbs.ui.components.InfoDialog
import kbs.ui.components.PrivacyContent
import kbs.ui.theme.ThemeMode
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import kbs.ui.theme.LocalAccent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import kbs.data.PendingImport
import kbs.data.PresetFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 公告收起时显示的条数。
 *
 * 只给最新两条：再多会侵占首屏，而 App 的主要用途是算坐标。
 */
private const val PREVIEW_COUNT = 2

// ★ @OptIn 必须贴在**用到实验性 API 的函数**上。
//
//   之前它被放在了上面的 PREVIEW_COUNT 常量前面 ——
//   注解会作用于紧随其后的声明，也就是那个常量，
//   而真正用到 TopAppBar（实验性 API）的 CalcScreen 反而没有注解，
//   于是编译器报：
//     "This material API is experimental and is likely to change"
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalcScreen(
    vm: CalcViewModel,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copiedCode by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.attach(context) }

    // 自适应：宽屏限制内容宽度并居中，避免文字被拉成长条
    val cfg = LocalConfiguration.current
    val wDp = cfg.screenWidthDp
    val contentMaxWidth = when {
        wDp >= 840 -> 900.dp
        wDp >= 600 -> 720.dp
        else -> Dp.Unspecified
    }
    val twoColumns = wDp >= 600

    // 解算完成后是否自动滚到结果区。
    //
    // ★ 默认关闭：反复微调参数试算时，每次都被强行拉到底部
    //   会打断操作节奏。需要的人可在「设置」里打开。
    var autoScroll by remember {
        mutableStateOf(AppPrefs(context).autoScrollToResult)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 结果区不在视野内时，显示跳转按钮而非自动滚动。
    //
    // ★ 为什么要改成这样：
    //   之前即便关闭了自动滚动，用户仍反馈"算完会被拉到底"。
    //   原因是：结果区紧跟在运行按钮之后，而用户往往就是为了
    //   点运行按钮才滚到页面下部的 —— 结果一出现，视口里立刻
    //   就是结果区，主观感受等同于"被拉到底"。
    //
    //   这个锅不该由滚动来背。正确做法是**根本不动滚动位置**，
    //   只在结果区不可见时给一个明确的跳转入口，由用户决定。
    var resultOffscreen by remember { mutableStateOf(false) }

    // ★ 结果区 item 的索引。
    //
    //   不能简单用 `totalItemsCount - 1`：
    //   列表**最后一项是 24dp 的底部留白 Spacer**，
    //   而 animateScrollToItem() 是把目标项滚到**视口顶部** ——
    //   滚到 Spacer 等于把结果区顶出屏幕上沿，用户看到一片空白。
    //
    //   结果区是倒数第二项，所以减 2。
    //   用 coerceAtLeast(0) 兜底，避免极端情况下索引变负。
    fun resultIndex(): Int =
        (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)

    LaunchedEffect(s.solutions) {
        if (s.solutions.isEmpty()) {
            resultOffscreen = false
            return@LaunchedEffect
        }
        val target = resultIndex()

        if (autoScroll) {
            // 用户明确要求自动滚动，直接跳到结果区
            listState.animateScrollToItem(target)
            resultOffscreen = false
        } else {
            // 保持当前位置不动，仅提示"结果在下面"
            val visible = listState.layoutInfo.visibleItemsInfo
                .any { it.index == target }
            resultOffscreen = !visible
        }
    }

    // 顶部菜单与二级页面
    var menuExpanded by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showDevelopers by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 配置预设的暂存输入
    var configNameDraft by rememberSaveable { mutableStateOf("") }
    // 正在重命名的预设 id（null = 没有在重命名）
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    // 展开的分享码（null = 未展开）
    var shareCode by remember { mutableStateOf<String?>(null) }
    // 导入输入框内容
    var importDraft by remember { mutableStateOf("") }
    // 导入结果提示
    var importHint by remember { mutableStateOf<String?>(null) }
    // 导出文件名
    //
    // 默认用「珍珠炮配置_N条_MMdd」，用户想改随时能改。
    var fileNameDraft by rememberSaveable {
        mutableStateOf(PresetFile.suggestName(1).removeSuffix(".txt"))
    }
    // 用户是否手动改过文件名。
    // 改过之后就**不再**自动更新，否则新增预设会把人家起的名字冲掉。
    var fileNameEdited by rememberSaveable { mutableStateOf(false) }

    // 未编辑过时，让建议名跟随预设数量变化
    LaunchedEffect(s.configPresets.size) {
        if (!fileNameEdited) {
            fileNameDraft = PresetFile
                .suggestName(s.configPresets.size)
                .removeSuffix(".txt")
        }
    }

    // 文件选择器：从微信/文件管理里挑一个 txt
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = PresetFile.read(context, uri)
        if (text.isBlank()) {
            importHint = "✗ 这个文件读不出内容"
            return@rememberLauncherForActivityResult
        }
        importDraft = text
        importHint = "已从文件读入，点「导入」即可"
    }

    // 从微信/文件管理器点 txt 进来时，把内容填进导入框。
    //
    // ★ 必须放在 importDraft / importHint 声明**之后**：
    //   Kotlin 局部变量要先声明后使用（与类成员不同），
    //   放在前面会直接 Unresolved reference。
    //   这也是本轮 CI 编译失败的原因。
    //
    // take() 取走即清空，旋转屏幕不会重复填充。
    LaunchedEffect(Unit) {
        val incoming = PendingImport.take()
        if (!incoming.isNullOrBlank()) {
            importDraft = incoming
            importHint = "已从文件读入，点「导入」即可"
        }
    }

    // 系统分享面板：把 txt 发给微信/QQ
    fun shareFile(code: String, count: Int, fileName: String = "") {
        val uri = PresetFile.write(context, code, fileName)
        if (uri == null) {
            importHint = "✗ 导出失败：无法写入文件"
            return
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    PresetFile.shareIntent(context, uri, count),
                    "发送配置文件",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (wDp < 360) "珍珠炮码" else "珍珠炮码计算",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    // 主题快捷切换：深浅色是最常用的外观操作，
                    // 放顶栏一键可达，不必进菜单翻两层
                    IconButton(onClick = {
                        onThemeModeChange(
                            if (themeMode == ThemeMode.DARK) ThemeMode.LIGHT
                            else ThemeMode.DARK
                        )
                    }) {
                        Text(
                            if (themeMode == ThemeMode.DARK) "\u2600" else "\uD83C\uDF19",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("\u22EE", style = MaterialTheme.typography.titleLarge)
                        }
                        AppMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            onSettings = { showSettings = true },
                            onDevelopers = { showDevelopers = true },
                            onPrivacy = { showPrivacy = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .then(
                    if (contentMaxWidth != Dp.Unspecified) {
                        Modifier
                            .widthIn(max = contentMaxWidth)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                    } else Modifier
                )
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }

            // ========== 公告 ==========
            //
            // ★ 条件从「有可见公告」放宽到「有可见公告 **或** 有关闭过的」。
            //
            //   原先只要把公告全部关闭，整张卡片就从列表里消失，
            //   而「恢复已关闭的公告」按钮也在卡片内部 ——
            //   于是**关掉之后就再也找不回来了**（真实反馈）。
            //   现在即使全部关闭，也保留一行极简的恢复入口。
            if (s.visibleAnnouncements.isNotEmpty() || s.dismissedIds.isNotEmpty()) {
                item {
                    CollapsibleCard(
                        title = if (s.visibleAnnouncements.isEmpty()) {
                            "🔔 公告（${s.dismissedIds.size} 条已关闭）"
                        } else {
                            "🔔 公告 (${s.visibleAnnouncements.size})"
                        },
                        expanded = s.announcementsExpanded,
                        onToggle = { vm.toggleAnnouncements(context) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // ★ 收起状态只显示最新 2 条。
                            //
                            //   公告会越积越多（版本更新、教程、告警…），
                            //   全展开会把首屏顶到看不见计算区域 ——
                            //   而绝大多数人打开 App 是为了算坐标，不是读公告。
                            //   收起时给最新两条，既保证重要信息可见，
                            //   又不侵占首屏；想看全部再展开。
                            val shown = if (s.announcementsExpanded) {
                                s.visibleAnnouncements
                            } else {
                                s.visibleAnnouncements.take(PREVIEW_COUNT)
                            }

                            if (shown.size > 1 || s.announcementsExpanded) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        vm.dismissAllAnnouncements(context)
                                    }) { Text("全部关闭 ×") }
                                }
                            }

                            shown.forEach { ann ->
                                AnnouncementCard(
                                    ann = ann,
                                    onDismiss = { vm.dismissAnnouncement(context, ann.id) },
                                    onOpen = { url ->
                                        runCatching {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW, Uri.parse(url)
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }
                                    },
                                )
                            }

                            // 展开入口：明确告诉用户还有几条没显示
                            val hidden = s.visibleAnnouncements.size - shown.size
                            if (hidden > 0) {
                                // 用 filled Button 而非 TextButton：
                                // 公告有多条时，"还有 N 条没显示"这个入口
                                // 必须一眼看到，否则会以为只有这几条。
                                Button(
                                    onClick = { vm.toggleAnnouncements(context) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("展开查看其余 $hidden 条") }
                            } else if (s.announcementsExpanded &&
                                s.visibleAnnouncements.size > PREVIEW_COUNT
                            ) {
                                TextButton(onClick = {
                                    vm.toggleAnnouncements(context)
                                }) { Text("收起，只看最新 $PREVIEW_COUNT 条") }
                            }

                            if (s.dismissedIds.isNotEmpty()) {
                                TextButton(onClick = {
                                    vm.restoreAnnouncements(context)
                                }) { Text("恢复已关闭的公告") }
                            }
                        }
                    }
                }
            }

            // ========== 新手指南 ==========
            item {
                CollapsibleCard(
                    title = "📖 新手指南",
                    expanded = vm.isExpanded(AppPrefs.SEC_GUIDE),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_GUIDE) },
                ) {
                    GuideContent(onOpenTutorial = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(Announcement.TUTORIAL_URL)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    })
                }
            }

            // ========== 目标坐标 ==========
            item {
                CollapsibleCard(
                    title = "🎯 目标坐标",
                    expanded = vm.isExpanded(AppPrefs.SEC_DESTINATION),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_DESTINATION) },
                ) {
                    Column {
                        if (twoColumns) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field(
                                    "* 目标 X", s.destX, Modifier.weight(1f),
                                    on = { vm.update { copy(destX = it) } },
                                )
                                Field(
                                    "* 目标 Z", s.destZ, Modifier.weight(1f),
                                    imeAction = ImeAction.Done,
                                    onIme = { runSolve(vm) },
                                    on = { vm.update { copy(destZ = it) } },
                                )
                            }
                        } else {
                            Field(
                                "* 目标 X", s.destX,
                                on = { vm.update { copy(destX = it) } },
                            )
                            Spacer(Modifier.height(8.dp))
                            Field(
                                "* 目标 Z", s.destZ,
                                imeAction = ImeAction.Done,
                                onIme = { runSolve(vm) },
                                on = { vm.update { copy(destZ = it) } },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Field(
                            "地面高度", s.groundHeight,
                            on = { vm.update { copy(groundHeight = it) } },
                        )
                    }
                }
            }

            // ========== 坐标换算 ==========
            item {
                CollapsibleCard(
                    title = "🔄 坐标换算（下界 ↔ 主世界）",
                    expanded = vm.isExpanded(AppPrefs.SEC_CONVERTER),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_CONVERTER) },
                ) {
                    Column {
                        OutlinedTextField(
                            value = s.convertInput,
                            onValueChange = { vm.update { copy(convertInput = it) } },
                            label = { Text("输入两个坐标，如 1234 4321") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.convert(fromNether = true) },
                                modifier = Modifier.weight(1f),
                            ) { Text("下界→主世界 ×8") }
                            OutlinedButton(
                                onClick = { vm.convert(fromNether = false) },
                                modifier = Modifier.weight(1f),
                            ) { Text("主世界→下界 ÷8") }
                        }
                        if (s.convertResult.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                s.convertResult,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = LocalAccent.current,
                            )
                            TextButton(onClick = { vm.applyConvertedToTarget() }) {
                                Text("设为目标坐标")
                            }
                        }
                    }
                }
            }

            // ========== 常用目标 ==========
            item {
                CollapsibleCard(
                    title = "⭐ 常用目标 (${s.favorites.size})",
                    expanded = vm.isExpanded(AppPrefs.SEC_FAVORITES),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_FAVORITES) },
                ) {
                    Column {
                        if (s.favorites.isEmpty()) {
                            Text(
                                "还没有收藏。填好目标坐标后点下方按钮保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            s.favorites.forEach { fav ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            fav.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            "X=${fav.x}  Z=${fav.z}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { vm.applyFavorite(fav) }) {
                                        Text("计算")
                                    }
                                    TextButton(onClick = {
                                        vm.removeFavorite(context, fav.id)
                                    }) { Text("删除") }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                vm.addFavorite(context, "目标 (${s.destX}, ${s.destZ})")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("收藏当前目标") }
                    }
                }
            }

            // ========== 配置预设 ==========
            item {
                CollapsibleCard(
                    title = "💾 配置预设 (${s.configPresets.size})",
                    expanded = vm.isExpanded(AppPrefs.SEC_CONFIGS),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_CONFIGS) },
                ) {
                    Column {
                        Text(
                            "把整套参数（目标、基准状态、物理模型、终点判定）"
                                    + "存成预设，换炮或换版本时一键切换。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = configNameDraft,
                            onValueChange = { configNameDraft = it },
                            label = { Text("预设名称（留空自动命名）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                vm.saveConfig(context, configNameDraft)
                                configNameDraft = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("保存当前配置") }

                        if (s.configPresets.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                        }

                        s.configPresets.forEach { preset ->
                            Spacer(Modifier.height(10.dp))
                            if (renamingId == preset.id) {
                                // ---- 重命名态：原地编辑 ----
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = renameDraft,
                                        onValueChange = { renameDraft = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    TextButton(onClick = {
                                        vm.renameConfig(context, preset.id, renameDraft)
                                        renamingId = null
                                    }) { Text("✓") }
                                    TextButton(onClick = { renamingId = null }) {
                                        Text("✕")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            preset.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            "X=${preset.destX} Z=${preset.destZ} · "
                                                    + "${preset.landingMode.label} · "
                                                    + preset.versionId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme
                                                .onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { vm.applyConfig(preset) }) {
                                        Text("应用")
                                    }
                                    TextButton(onClick = {
                                        renamingId = preset.id
                                        renameDraft = preset.name
                                    }) { Text("重命名") }
                                    TextButton(onClick = {
                                        vm.deleteConfig(context, preset.id)
                                    }) { Text("删除") }
                                }
                            }
                        }

                        // ---- 分享 / 导入 ----
                        if (s.configPresets.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "分享给朋友",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "预设存在 App 私有目录里，普通手机读不到。" +
                                        "用分享码：复制一段文本发给朋友，" +
                                        "对方粘贴即可导入。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            // 文件名可改。默认带条数与日期，想改随时能改。
                            OutlinedTextField(
                                value = fileNameDraft,
                                onValueChange = {
                                    fileNameDraft = it
                                    fileNameEdited = true
                                },
                                label = { Text("文件名（.txt 会自动补上）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "发送后对方看到的就是这个名字。" +
                                        "留空用默认名。不能包含 / \\ : * ? \" < > | ，" +
                                        "最长 60 字。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            // ★ 主推发文件：一步直达微信好友
                            Button(
                                onClick = {
                                    shareFile(
                                        vm.shareCodeOfAll(),
                                        s.configPresets.size,
                                        fileNameDraft,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("发给朋友（全部 ${s.configPresets.size} 条）") }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                "会生成一个 txt 文件并打开系统分享面板，" +
                                        "选微信/QQ 发过去。对方点开文件即可跳进本 App。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(8.dp))
                            // 分享码作为备用通道：自己备份、贴群里、写笔记
                            OutlinedButton(
                                onClick = {
                                    shareCode = vm.shareCodeOfAll()
                                    importHint = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("改用分享码（纯文本）") }

                            shareCode?.let { code ->
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "分享码（长按可全选复制）：",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    SelectionContainer {
                                        Text(
                                            code,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(10.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = {
                                    // 复用文件顶部已获取的 clipboard（Compose 版），
                                    // 不另写 Android ClipboardManager ——
                                    // 两者混用容易在部分 ROM 上出现时序问题。
                                    clipboard.setText(AnnotatedString(code))
                                    importHint = "已复制到剪贴板，发给朋友即可"
                                }) { Text("复制分享码") }
                                TextButton(onClick = { shareCode = null }) {
                                    Text("收起")
                                }
                            }
                        }

                        // ---- 导入 ----
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "导入朋友的分享码",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { pickFile.launch("text/plain") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("从文件导入（选一个 txt）") }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importDraft,
                            onValueChange = {
                                importDraft = it
                                importHint = null
                            },
                            label = { Text("或粘贴分享码（KBS1: 开头）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val n = vm.importShareCode(context, importDraft)
                                importHint = if (n > 0) {
                                    importDraft = ""
                                    "✓ 成功导入 $n 条配置"
                                } else {
                                    "✗ 分享码无效或不完整，请检查是否复制完整"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = importDraft.trim().isNotEmpty(),
                        ) { Text("导入") }

                        importHint?.let { hint ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hint.startsWith("✓"))
                                    LocalAccent.current
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // ========== 智能粘贴 ==========
            item {
                CollapsibleCard(
                    title = "📋 智能粘贴",
                    expanded = vm.isExpanded(AppPrefs.SEC_PASTE),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_PASTE) },
                ) {
                    Column {
                        Text(
                            "把游戏调试框的文本整段粘进来，自动识别坐标与速度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.pasteText,
                            onValueChange = { vm.onPasteText(it) },
                            label = { Text("粘贴到这里") },
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            maxLines = 5,
                        )
                        if (s.pasteHint.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.pasteHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalAccent.current,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.applyPaste() },
                                modifier = Modifier.weight(1f),
                            ) { Text("解析并填入") }
                            OutlinedButton(
                                onClick = { vm.clearPaste() },
                                modifier = Modifier.weight(1f),
                            ) { Text("清空") }
                        }
                    }
                }
            }

            // ========== 基准状态 ==========
            item {
                CollapsibleCard(
                    title = "📍 基准状态（起爆瞬时）",
                    expanded = vm.isExpanded(AppPrefs.SEC_BASE),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_BASE) },
                ) {
                    Column {
                        Text(
                            "珍珠在起爆瞬间的位置与速度。这是解算的起点，填错会全盘偏离。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (twoColumns) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field(
                                    "* 基准 X", s.baseX, Modifier.weight(1f),
                                    on = { vm.update { copy(baseX = it) } },
                                )
                                Field(
                                    "* 基准 Y", s.baseY, Modifier.weight(1f),
                                    on = { vm.update { copy(baseY = it) } },
                                )
                                Field(
                                    "* 基准 Z", s.baseZ, Modifier.weight(1f),
                                    on = { vm.update { copy(baseZ = it) } },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field(
                                    "* mX", s.baseMx, Modifier.weight(1f),
                                    on = { vm.update { copy(baseMx = it) } },
                                )
                                Field(
                                    "* mY", s.baseMy, Modifier.weight(1f),
                                    on = { vm.update { copy(baseMy = it) } },
                                )
                                Field(
                                    "* mZ", s.baseMz, Modifier.weight(1f),
                                    on = { vm.update { copy(baseMz = it) } },
                                )
                            }
                        } else {
                            Field("* 基准 X", s.baseX,
                                on = { vm.update { copy(baseX = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("* 基准 Y", s.baseY,
                                on = { vm.update { copy(baseY = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("* 基准 Z", s.baseZ,
                                on = { vm.update { copy(baseZ = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("* 基准 mX", s.baseMx,
                                on = { vm.update { copy(baseMx = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("* 基准 mY", s.baseMy,
                                on = { vm.update { copy(baseMy = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("* 基准 mZ", s.baseMz,
                                on = { vm.update { copy(baseMz = it) } })
                        }
                    }
                }
            }

            // ========== 采样点反推 ==========
            item {
                CollapsibleCard(
                    title = "🔍 采样点反推",
                    expanded = vm.isExpanded(AppPrefs.SEC_SAMPLE),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_SAMPLE) },
                ) {
                    Column {
                        Text(
                            "只有飞行途中的调试数据？填进来反推起爆瞬时的基准状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Field("采样 tick", s.sampleTick,
                            on = { vm.update { copy(sampleTick = it) } })
                        Spacer(Modifier.height(8.dp))
                        if (twoColumns) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field("x", s.sampleX, Modifier.weight(1f),
                                    on = { vm.update { copy(sampleX = it) } })
                                Field("y", s.sampleY, Modifier.weight(1f),
                                    on = { vm.update { copy(sampleY = it) } })
                                Field("z", s.sampleZ, Modifier.weight(1f),
                                    on = { vm.update { copy(sampleZ = it) } })
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field("mx", s.sampleMx, Modifier.weight(1f),
                                    on = { vm.update { copy(sampleMx = it) } })
                                Field("my", s.sampleMy, Modifier.weight(1f),
                                    on = { vm.update { copy(sampleMy = it) } })
                                Field("mz", s.sampleMz, Modifier.weight(1f),
                                    on = { vm.update { copy(sampleMz = it) } })
                            }
                        } else {
                            Field("x", s.sampleX,
                                on = { vm.update { copy(sampleX = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("y", s.sampleY,
                                on = { vm.update { copy(sampleY = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("z", s.sampleZ,
                                on = { vm.update { copy(sampleZ = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("mx", s.sampleMx,
                                on = { vm.update { copy(sampleMx = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("my", s.sampleMy,
                                on = { vm.update { copy(sampleMy = it) } })
                            Spacer(Modifier.height(8.dp))
                            Field("mz", s.sampleMz,
                                on = { vm.update { copy(sampleMz = it) } })
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.invertSample() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("反推并填入基准") }
                        if (s.sampleInfo.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                s.sampleInfo,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalAccent.current,
                            )
                        }
                    }
                }
            }

            // ========== 高级设置 ==========
            item {
                CollapsibleCard(
                    title = "⚙️ 物理模型",
                    expanded = vm.isExpanded(AppPrefs.SEC_ADVANCED),
                    onToggle = { vm.toggleSection(AppPrefs.SEC_ADVANCED) },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        LabeledChoice(
                            title = "版本模型",
                            description = "1.21.2 起抛射物顺序改为「加速度→阻力→位移」，" +
                                    "旧版本为「位移→阻力→加速度」。两者结果不同，请按实际版本选择。",
                            options = VersionProfile.ALL.map { it.id to it.label },
                            selected = s.versionId,
                            onSelect = { vm.setVersion(it) },
                        )
                        LabeledChoice(
                            title = "排序偏好",
                            description = "默认纯按落点误差排序。拖动可兼顾省 TNT " +
                                    "或缩短飞行时间——两者都是真实炮体上的成本。",
                            options = listOf(
                                "error" to "精度优先",
                                "tnt" to "省 TNT",
                                "tick" to "飞行短",
                            ),
                            selected = s.scoringPreset,
                            onSelect = { vm.setScoringPreset(it) },
                        )
                        LabeledChoice(
                            title = "Y 动量模式",
                            description = "「abs(m+n)」与原作者一致，旧炮已验证；" +
                                    "「|m|+|n|」符合对称炮体几何，新炮建议用此项。",
                            options = YAccumMode.entries.map { it.name to it.label },
                            selected = s.yMode.name,
                            onSelect = { vm.setYMode(YAccumMode.valueOf(it)) },
                        )
                        // ---- 终点判定方式 ----
                        //
                        // 这是本工具最关键的一个选择：
                        //   · 落点模式：珍珠要真正落在目标附近
                        //   · 拦截模式：珍珠飞过目标上空就算到达
                        //
                        // 目标很远时，落点模式会失败（珍珠必须落地，
                        // 而落地过程会把它带过目标），此时应切换到拦截模式。
                        LabeledChoice(
                            title = "终点判定",
                            description = LandingMode.entries
                                .joinToString("；") { "${it.label}：${it.description}" },
                            options = LandingMode.entries.map { it.name to it.label },
                            selected = s.landingMode.name,
                            onSelect = {
                                vm.update {
                                    copy(landingMode = LandingMode.valueOf(it))
                                }
                            },
                        )

                        // 高度约束只在落点模式有意义：
                        // 拦截点发生在半空，用"落地高度"约束它自相矛盾
                        if (s.landingMode == LandingMode.LANDING) {
                            Text(
                                "限制落点 Y 的区间（可选）。留空则不限制 —— " +
                                        "通常不需要填，只有要把珍珠精确送到"
                                        + "某一层高度时才用得到。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (twoColumns) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Field("高度下限", s.endHeightMin, Modifier.weight(1f),
                                        on = { vm.update { copy(endHeightMin = it) } })
                                    Field("高度上限", s.endHeightMax, Modifier.weight(1f),
                                        on = { vm.update { copy(endHeightMax = it) } })
                                }
                            } else {
                                Field("高度下限", s.endHeightMin,
                                    on = { vm.update { copy(endHeightMin = it) } })
                                Spacer(Modifier.height(8.dp))
                                Field("高度上限", s.endHeightMax,
                                    on = { vm.update { copy(endHeightMax = it) } })
                            }
                        }
                    }
                }
            }

            // ========== 运行按钮 ==========
            item {
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = { runSolve(vm) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !s.isRunning,
                ) {
                    Text(if (s.isRunning) "解算中…" else "运行解算")
                }
            }

            // 结果区不在视野内时的跳转入口。
            // 不自动滚动，把"要不要跳"的决定权交给用户。
            if (resultOffscreen && s.solutions.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(resultIndex())
                            }
                            resultOffscreen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("↓ 查看解算结果（${s.solutions.size} 组）")
                    }
                }
            }

            // ========== 错误提示 ==========
            if (s.error.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            s.error,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ========== 结果 ==========
            if (s.solutions.isNotEmpty()) {
                item {
                    CollapsibleCard(
                        title = "📋 解算结果（${s.solutions.size} 组，按误差升序）",
                        expanded = vm.isExpanded(AppPrefs.SEC_RESULT),
                        onToggle = { vm.toggleSection(AppPrefs.SEC_RESULT) },
                    ) {
                        Column {
                            Text(
                                "评估 ${s.evaluatedTicks} 个 tick，耗时 ${s.elapsedMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.note.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    s.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            s.solutions.forEachIndexed { i, sol ->
                                if (i > 0) Spacer(Modifier.height(12.dp))
                                SolutionCard(
                                    sol = sol,
                                    best = i == 0,
                                    targetX = s.destX.trim().toDoubleOrNull(),
                                    targetZ = s.destZ.trim().toDoubleOrNull(),
                                    targetDistance = run {
                                        val ox = s.baseX.trim().toDoubleOrNull()
                                        val oz = s.baseZ.trim().toDoubleOrNull()
                                        val tx = s.destX.trim().toDoubleOrNull()
                                        val tz = s.destZ.trim().toDoubleOrNull()
                                        if (ox != null && oz != null &&
                                            tx != null && tz != null
                                        ) kotlin.math.hypot(tx - ox, tz - oz)
                                        else null
                                    },
                                    groundHeight = s.groundHeight.trim()
                                        .toDoubleOrNull(),
                                    copied = copiedCode == sol.code,
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(sol.code))
                                        copiedCode = sol.code
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }


        // ============================================================
        // 二级页面
        //
        // 放在 LazyColumn **之外**：这些是全屏 Dialog，
        // 若塞进 LazyColumn 的 item 里会随列表滚动而错位。
        // ============================================================
        if (showSettings) {
            SettingsDialog(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                autoScroll = autoScroll,
                onAutoScrollChange = { v ->
                    autoScroll = v
                    AppPrefs(context).autoScrollToResult = v
                },
                onDismiss = { showSettings = false },
            )
        }
        if (showDevelopers) {
            InfoDialog(title = "👥 开发者", onDismiss = { showDevelopers = false }) {
                DevelopersContent()
            }
        }
        if (showPrivacy) {
            InfoDialog(title = "🔒 隐私政策", onDismiss = { showPrivacy = false }) {
                PrivacyContent()
            }
        }
    }
}

/** 校验 → 解算 */
private fun runSolve(vm: CalcViewModel) {
    val s = vm.state.value
    val err = vm.validate(s)
    if (err != null) {
        vm.update { copy(error = err) }
        return
    }
    vm.solve()
}

// ============================================================
// 子组合项
// ============================================================

@Composable
private fun GuideContent(onOpenTutorial: () -> Unit) {
    Column {
        Text(
            "三步使用：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "① 填「目标坐标」—— 想让珍珠落在哪\n" +
                    "② 填「基准状态」—— 起爆瞬间珍珠的位置与速度；" +
                    "只有飞行途中的数据就用「采样点反推」\n" +
                    "③ 点「运行解算」，复制第一条的炮码填入炮的控制面板",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "几个要点：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "· 结果按误差升序，第一条即最优\n" +
                    "· 炮码只有 8 个槽位，全亮 = 160，超出的解无法表示\n" +
                    "· 0.602679 是特定炮体的标定值，换炮需重新标定\n" +
                    "· 采样 tick 越大，反推误差放大越明显，建议尽早采样",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenTutorial, modifier = Modifier.fillMaxWidth()) {
            Text("📺 观看视频教程（B站）")
        }
    }
}

@Composable
private fun AnnouncementCard(
    ann: Announcement,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val color = when (ann.level) {
        Announcement.Level.WARN -> MaterialTheme.colorScheme.error
        Announcement.Level.UPDATE -> MaterialTheme.colorScheme.primary
        Announcement.Level.INFO -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ann.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                ann.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ann.url.isNullOrBlank()) {
                TextButton(onClick = { onOpen(ann.url) }) {
                    Text("打开链接 →")
                }
            }
        }
    }
}

@Composable
private fun SolutionCard(
    sol: Solution,
    best: Boolean,
    targetX: Double?,
    targetZ: Double?,
    copied: Boolean,
    onCopy: () -> Unit,
    targetDistance: Double? = null,
    groundHeight: Double? = null,
) {
    var showSlots by rememberSaveable { mutableStateOf(best) }
    var showChart by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (best) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (best) "★ 最优解" else "备选",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (best) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "误差 ${"%.3f".format(sol.error)} 格",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (best) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showSlots = !showSlots }) {
                    Text(if (showSlots) "收起图" else "阵列图")
                }
                if (sol.profile.size >= 2) {
                    TextButton(onClick = { showChart = !showChart }) {
                        Text(if (showChart) "收起线" else "轨迹")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 飞行剖面图
            AnimatedVisibility(
                visible = showChart && sol.profile.size >= 2,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    TrajectoryChart(
                        points = sol.profile,
                        groundHeight = groundHeight,
                        targetDistance = targetDistance,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "横轴：水平距离　纵轴：高度　虚线：地面/目标位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (best) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            // 炮码（整行可复制）
            CodeLine(code = sol.code, copied = copied, onClick = onCopy)

            Spacer(Modifier.height(10.dp))

            // 关键参数
            InfoRow(
                "脉冲", "m=${sol.m}  n=${sol.n}  方向=${sol.direction.label}",
                if (best) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            InfoRow(
                "飞行", "${sol.flyTicks} tick　峰值 Y=${"%.1f".format(sol.peakY)}",
                if (best) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            InfoRow(
                "落点", "X=${"%.2f".format(sol.landing.x)}  " +
                        "Y=${"%.2f".format(sol.landing.y)}  " +
                        "Z=${"%.2f".format(sol.landing.z)}",
                if (best) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            if (targetX != null && targetZ != null) {
                InfoRow(
                    "偏移", sol.offsetHint(targetX, targetZ),
                    if (best) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            InfoRow(
                "TNT", "|m|+|n| = ${sol.totalTnt} 颗",
                if (best) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )

            if (!sol.encodable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠ 脉冲数超出 8 位编码上限 160，炮码无法正确表示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 阵列图
            AnimatedVisibility(
                visible = showSlots,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    SlotRow(sol.codeLayout.main)
                    Spacer(Modifier.height(10.dp))
                    SlotRow(sol.codeLayout.sub)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共需点亮 ${sol.codeLayout.totalLit} 处（炮自动配置）",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (best) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
