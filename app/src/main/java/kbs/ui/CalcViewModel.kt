package kbs.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kbs.core.model.CannonSpec
import kbs.core.model.LandingMode
import kbs.core.model.ScoringConfig
import kbs.core.model.Solution
import kbs.core.model.SolveRequest
import kbs.core.model.Vec3
import kbs.core.model.VersionProfile
import kbs.core.model.WorldSpec
import kbs.core.model.YAccumMode
import kbs.core.solver.PearlSolver
import kbs.core.solver.SampleInversion
import kbs.data.Announcement
import kbs.data.ConfigPreset
import kbs.data.ConfigStore
import kbs.data.PresetShare
import kbs.data.AnnouncementRepo
import kbs.data.AppPrefs
import kbs.data.Favorite
import kbs.data.FavoriteStore
import kbs.util.ParsedSample
import kbs.util.PasteParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面状态与业务逻辑。
 *
 * 界面只负责渲染 [state]，所有计算与持久化都在这里。
 * 解算是 CPU 密集任务，统一切到 Dispatchers.Default，
 * 文件/网络 IO 切到 Dispatchers.IO。
 */
class CalcViewModel : ViewModel() {

    /** 表单与结果的完整状态 */
    data class UiState(
        // ---- 目标 ----
        val destX: String = "1234",
        val destZ: String = "",
        // ---- 基准（起爆瞬时）----
        val baseTick: String = "72",
        val baseX: String = "2.0",
        val baseY: String = "169.630464",
        val baseZ: String = "28.0",
        val baseMx: String = "0.0",
        val baseMy: String = "-0.003727",
        val baseMz: String = "0.0",
        // ---- 世界 ----
        val groundHeight: String = "128",
        val endHeightMin: String = "",
        val endHeightMax: String = "",
        /**
         * 终点判定方式，见 [LandingMode]。
         *
         * 取代原先的 netherMode —— 那个开关只表达了
         * "要不要约束落点 Y"，而真正要区分的是：
         * "珍珠落地算到达" 还是 "飞过目标上空算到达"。
         */
        val landingMode: LandingMode = LandingMode.LANDING,
        // ---- 物理模型 ----
        val versionId: String = VersionProfile.ADP.id,
        val yMode: YAccumMode = YAccumMode.SUM_THEN_ABS,
        /** 排序偏好：error / tnt / tick */
        val scoringPreset: String = "error",
        // ---- 采样点反推 ----
        val sampleTick: String = "",
        val sampleX: String = "",
        val sampleY: String = "",
        val sampleZ: String = "",
        val sampleMx: String = "",
        val sampleMy: String = "",
        val sampleMz: String = "",
        val sampleInfo: String = "",
        // ---- 智能粘贴 ----
        val pasteText: String = "",
        val pasteHint: String = "",
        // ---- 坐标换算 ----
        val convertInput: String = "",
        val convertResult: String = "",
        // ---- 结果 ----
        val solutions: List<Solution> = emptyList(),
        val isRunning: Boolean = false,
        val error: String = "",
        val note: String = "",
        val elapsedMs: Long = 0,
        val evaluatedTicks: Int = 0,
        // ---- 收藏 ----
        val favorites: List<Favorite> = emptyList(),
        /** 已保存的配置预设 */
        val configPresets: List<ConfigPreset> = emptyList(),
        // ---- 公告 ----
        val announcements: List<Announcement> = emptyList(),
        val dismissedIds: Set<String> = emptySet(),
        val announcementsExpanded: Boolean = false,
        // ---- 折叠 ----
        val expanded: Set<String> = AppPrefs.DEFAULT_SECTIONS,
    ) {
        /** 未被关闭的公告 */
        val visibleAnnouncements: List<Announcement>
            get() = announcements.filter { it.id !in dismissedIds }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var prefs: AppPrefs? = null

    // ============================================================
    // 表单更新
    // ============================================================

    fun update(transform: UiState.() -> UiState) {
        _state.update { it.transform() }
    }

    // ============================================================
    // 初始化
    // ============================================================

    fun attach(context: Context) {
        val app = context.applicationContext
        if (prefs == null) {
            prefs = AppPrefs(app)
            val p = prefs!!
            _state.update {
                it.copy(
                    versionId = p.versionProfileId,
                    yMode = runCatching { YAccumMode.valueOf(p.yModeId) }
                        .getOrDefault(YAccumMode.SUM_THEN_ABS),
                    scoringPreset = p.scoringPreset,
                    expanded = p.expandedSections,
                    dismissedIds = p.dismissedAnnouncements,
                    announcementsExpanded = p.announcementsExpanded,
                )
            }
        }
        // 首帧先用缓存，网络结果稍后覆盖
        val cached = AnnouncementRepo.readCache(app)
        if (_state.value.announcements.isEmpty()) {
            _state.update {
                it.copy(announcements = cached.ifEmpty { AnnouncementRepo.builtin() })
            }
        }
        // 拉取远程
        viewModelScope.launch {
            val remote = AnnouncementRepo.fetch(app)
            _state.update { it.copy(announcements = remote) }
        }
        // 加载收藏
        viewModelScope.launch {
            val favs = withContext(Dispatchers.IO) { FavoriteStore.load(app) }
            _state.update { it.copy(favorites = favs) }
        }
        // 加载配置预设（单独文件，与偏好分开）
        viewModelScope.launch {
            val presets = withContext(Dispatchers.IO) { ConfigStore.load(app) }
            _state.update { st ->
                // ★ 竞态保护：磁盘读取是异步的，若用户在加载完成前
                //   就点了"保存当前配置"，直接覆盖会把那条记录抹掉。
                //   因此内存里已有预设时以内存为准。
                if (st.configPresets.isNotEmpty()) st
                else st.copy(configPresets = presets)
            }
        }
    }

    // ============================================================
    // 解算
    // ============================================================

    fun solve() {
        val s = _state.value
        _state.update { it.copy(isRunning = true, error = "", note = "") }

        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { doSolve(s) }
            }

            _state.update {
                when {
                    result.isFailure -> it.copy(
                        isRunning = false,
                        error = result.exceptionOrNull()?.message ?: "解算失败",
                    )

                    else -> {
                        val r = result.getOrNull()!!
                        it.copy(
                            isRunning = false,
                            solutions = r.solutions,
                            note = r.note,
                            elapsedMs = r.elapsedMs,
                            evaluatedTicks = r.evaluatedTicks,
                        )
                    }
                }
            }
        }
    }

    private fun doSolve(s: UiState) = PearlSolver.solve(
        SolveRequest(
            origin = Vec3(
                s.baseX.trim().toDouble(),
                s.baseY.trim().toDouble(),
                s.baseZ.trim().toDouble(),
            ),
            baseVel = Vec3(
                s.baseMx.trim().toDouble(),
                s.baseMy.trim().toDouble(),
                s.baseMz.trim().toDouble(),
            ),
            targetX = s.destX.trim().toDouble(),
            targetZ = s.destZ.trim().toDouble(),
            cannon = CannonSpec(yMode = s.yMode),
            profile = VersionProfile.fromId(s.versionId),
            world = WorldSpec(groundHeight = s.groundHeight.trim().toDouble()),
            // 终点高度约束仅在落点模式生效：
            // 拦截点发生在半空，用"落地高度区间"约束它自相矛盾
            endHeightMin = if (s.landingMode == LandingMode.LANDING) {
                s.endHeightMin.trim().toDoubleOrNull()
            } else null,
            endHeightMax = if (s.landingMode == LandingMode.LANDING) {
                s.endHeightMax.trim().toDoubleOrNull()
            } else null,
            landingMode = s.landingMode,
            scoring = scoringOf(s.scoringPreset),
        )
    )

    /**
     * 排序偏好 → 评分权重。
     *
     * 三档都是"精度为主、另一项为辅"：
     *   纯按误差排会选出 TNT 消耗巨大的解，而 TNT 在服里是实打实的成本；
     *   飞行时间则影响落点受 chunk 加载、卡顿的干扰程度。
     */
    private fun scoringOf(preset: String): ScoringConfig = when (preset) {
        "tnt" -> ScoringConfig(errorWeight = 1.0, tntWeight = 0.05)
        "tick" -> ScoringConfig(errorWeight = 1.0, tickWeight = 0.01)
        else -> ScoringConfig(errorWeight = 1.0)
    }

    /** 输入校验，返回首个错误提示；无错误返回 null */
    fun validate(s: UiState): String? {
        data class Field(val label: String, val value: String)

        val required = listOf(
            Field("目标 X", s.destX),
            Field("目标 Z", s.destZ),
            Field("基准 X", s.baseX),
            Field("基准 Y", s.baseY),
            Field("基准 Z", s.baseZ),
            Field("基准 mX", s.baseMx),
            Field("基准 mY", s.baseMy),
            Field("基准 mZ", s.baseMz),
            Field("地面高度", s.groundHeight),
        )
        for (f in required) {
            val v = f.value.trim()
            if (v.isEmpty()) return "${f.label} 不能为空"
            if (v.toDoubleOrNull() == null) {
                return "${f.label} 不是有效数字（当前填的是「$v」）"
            }
        }
        // 世界坐标范围检查（Minecraft 边界 ±30,000,000）
        val x = s.destX.trim().toDoubleOrNull()
        val z = s.destZ.trim().toDoubleOrNull()
        if (x != null && kotlin.math.abs(x) > 30_000_000) {
            return "目标 X 超出世界范围（±30,000,000）"
        }
        if (z != null && kotlin.math.abs(z) > 30_000_000) {
            return "目标 Z 超出世界范围（±30,000,000）"
        }
        if (s.landingMode == LandingMode.LANDING && s.endHeightMin.isNotBlank()) {
            if (s.endHeightMin.trim().isNotEmpty() &&
                s.endHeightMin.trim().toDoubleOrNull() == null
            ) return "终点高度下限不是有效数字"
            if (s.endHeightMax.trim().isNotEmpty() &&
                s.endHeightMax.trim().toDoubleOrNull() == null
            ) return "终点高度上限不是有效数字"
        }
        return null
    }

    // ============================================================
    // 采样点反推
    // ============================================================

    /** 用游戏调试框抓到的中途状态，反推起爆瞬时基准 */
    fun invertSample() {
        val s = _state.value
        _state.update { it.copy(sampleInfo = "", error = "") }

        viewModelScope.launch {
            // ★ 必须显式声明泛型，且 runCatching 内不能出现 return@runCatching <String>。
            //
            //   原写法在校验失败时 `return@runCatching "采样 tick 不是有效整数"`，
            //   成功时 `Triple(...)` —— 两个分支类型不同，
            //   Kotlin 会推导成二者的公共父类型 **Serializable**（String 与 Triple 都实现它），
            //   于是 out.getOrNull() 是 Serializable?，解构时报：
            //     "Destructuring declaration initializer of type Serializable
            //      must have a 'component1()' function"
            //
            //   修法：用 error() 抛异常（runCatching 会捕获），
            //   保证 runCatching 只有一种返回类型。
            val out: Result<Triple<SampleInversion.InversionResult, Int, String>> =
                withContext(Dispatchers.Default) {
                    runCatching {
                        val tick = s.sampleTick.trim().toIntOrNull()
                            ?: error("采样 tick 不是有效整数")
                        val pos = Vec3(
                            s.sampleX.trim().toDouble(),
                            s.sampleY.trim().toDouble(),
                            s.sampleZ.trim().toDouble(),
                        )
                        val vel = Vec3(
                            s.sampleMx.trim().toDouble(),
                            s.sampleMy.trim().toDouble(),
                            s.sampleMz.trim().toDouble(),
                        )
                        if (tick <= 0) error("采样 tick 必须为正整数")

                        val r = SampleInversion.invert(
                            samplePos = pos,
                            sampleVel = vel,
                            sampleTicks = tick,
                            profile = VersionProfile.fromId(s.versionId),
                        )
                        Triple(r, tick, s.versionId)
                    }
                }

            // 失败：把异常消息显示到界面
            val triple = out.getOrNull()
            if (triple == null) {
                _state.update {
                    it.copy(sampleInfo = out.exceptionOrNull()?.message ?: "反推失败")
                }
                return@launch
            }
            val (r, tick, verId) = triple

            val amp = SampleInversion.amplification(
                tick, VersionProfile.fromId(verId)
            )

            // 反推结果写回基准表单
            _state.update {
                it.copy(
                    baseX = format(r.origin.x),
                    baseY = format(r.origin.y),
                    baseZ = format(r.origin.z),
                    baseMx = format(r.velocity.x),
                    baseMy = format(r.velocity.y),
                    baseMz = format(r.velocity.z),
                    baseTick = "0",
                    sampleInfo = buildString {
                        append("${r.level.mark} ${r.level.text}　")
                        append("自洽误差 ${"%.2e".format(r.consistencyError)}　")
                        append("放大 ${"%.2f".format(amp)}×")
                        if (amp > 4) {
                            append("\n误差放大 ${"%.0f".format(amp)} 倍，")
                            append("建议尽早采样以减小读数误差的影响")
                        }
                    },
                )
            }
        }
    }

    private fun format(v: Double): String =
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            "%.9f".format(v).trimEnd('0').trimEnd('.')
        }

    // ============================================================
    // 智能粘贴
    // ============================================================

    fun onPasteText(text: String) {
        _state.update { it.copy(pasteText = text) }
    }

    fun applyPaste() {
        val s = _state.value
        val parsed: ParsedSample = PasteParser.parse(s.pasteText)
        if (parsed.matched == 0) {
            _state.update { it.copy(pasteHint = parsed.hint) }
            return
        }
        _state.update {
            it.copy(
                sampleTick = parsed.tick?.toString() ?: it.sampleTick,
                sampleX = parsed.x?.let(::format) ?: it.sampleX,
                sampleY = parsed.y?.let(::format) ?: it.sampleY,
                sampleZ = parsed.z?.let(::format) ?: it.sampleZ,
                sampleMx = parsed.mx?.let(::format) ?: it.sampleMx,
                sampleMy = parsed.my?.let(::format) ?: it.sampleMy,
                sampleMz = parsed.mz?.let(::format) ?: it.sampleMz,
                pasteHint = parsed.hint,
            )
        }
        // 解析到完整信息时自动反推，省一次点击
        if (parsed.hasAll && parsed.tick != null) invertSample()
    }

    fun clearPaste() {
        _state.update { it.copy(pasteText = "", pasteHint = "") }
    }

    // ============================================================
    // 坐标换算（下界 ↔ 主世界，1:8）
    // ============================================================

    fun convert(fromNether: Boolean) {
        val s = _state.value
        val parts = s.convertInput.trim()
            .split(",", "，", " ", "　")
            .mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
        if (parts.size < 2) {
            _state.update {
                it.copy(convertResult = "请输入两个坐标，如「1234 4321」或「1234, 4321」")
            }
            return
        }
        val a = parts[0].toDoubleOrNull()
        val b = parts[1].toDoubleOrNull()
        if (a == null || b == null) {
            _state.update { it.copy(convertResult = "坐标不是有效数字") }
            return
        }
        val k = if (fromNether) 8.0 else 1.0 / 8.0
        val ra = a * k
        val rb = b * k
        val label = if (fromNether) "下界 → 主世界（×8）" else "主世界 → 下界（÷8）"
        _state.update {
            it.copy(
                convertResult = "$label：X=${format(ra)}  Z=${format(rb)}"
            )
        }
    }

    /** 把换算结果直接设为目标 */
    fun applyConvertedToTarget() {
        val s = _state.value
        val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(s.convertResult).toList()
        if (nums.size >= 2) {
            _state.update {
                it.copy(destX = nums[nums.size - 2].value, destZ = nums.last().value)
            }
        }
    }

    // ============================================================
    // 收藏
    // ============================================================

    fun addFavorite(context: Context, name: String) {
        val s = _state.value
        val x = s.destX.trim().toDoubleOrNull() ?: return
        val z = s.destZ.trim().toDoubleOrNull() ?: return
        val fav = Favorite(
            id = System.currentTimeMillis().toString(),
            name = name.ifBlank { "(${"%.0f".format(x)}, ${"%.0f".format(z)})" },
            x = x,
            z = z,
            groundHeight = s.groundHeight.trim().toDoubleOrNull() ?: 128.0,
            endHeightMin = s.endHeightMin.trim().toDoubleOrNull(),
            endHeightMax = s.endHeightMax.trim().toDoubleOrNull(),
        )
        val next = s.favorites + fav
        _state.update { it.copy(favorites = next) }
        persistFavorites(context, next)
    }

    fun removeFavorite(context: Context, id: String) {
        val next = _state.value.favorites.filter { it.id != id }
        _state.update { it.copy(favorites = next) }
        persistFavorites(context, next)
    }

    /** 点击收藏：填入坐标并立即解算 */
    fun applyFavorite(fav: Favorite) {
        _state.update {
            it.copy(
                destX = format(fav.x),
                destZ = format(fav.z),
                groundHeight = format(fav.groundHeight),
                endHeightMin = fav.endHeightMin?.let(::format) ?: "",
                endHeightMax = fav.endHeightMax?.let(::format) ?: "",
                // ★ 刻意**不**设置 landingMode。
                //
                //   这里原本写死成 LandingMode.LANDING，
                //   意味着：用户切到「拦截模式」后点任一常用目标，
                //   模式会被悄悄重置回落点模式 —— 界面上看不出来，
                //   但算出的误差会从约 1 格劣化到约 24 格。
                //
                //   常用目标回答的是「去哪里」，
                //   终点判定是「怎么算」，属于全局偏好，不该被改掉。
            )
        }
        solve()
    }

    private fun persistFavorites(context: Context, list: List<Favorite>) {
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteStore.save(context.applicationContext, list)
        }
    }

    // ============================================================
    // 公告
    // ============================================================

    fun dismissAnnouncement(context: Context, id: String) {
        val next = _state.value.dismissedIds + id
        _state.update { it.copy(dismissedIds = next) }
        prefs?.dismissedAnnouncements = next
    }

    fun dismissAllAnnouncements(context: Context) {
        val ids = _state.value.visibleAnnouncements.map { it.id }.toSet()
        val next = _state.value.dismissedIds + ids
        _state.update { it.copy(dismissedIds = next) }
        prefs?.dismissedAnnouncements = next
    }


    // ============================================================
    // 配置预设
    // ============================================================

    /** 把当前整套参数存成一个预设 */
    fun saveConfig(context: Context, rawName: String) {
        val s = _state.value
        val name = rawName.trim().ifBlank { "配置 ${s.configPresets.size + 1}" }
        val preset = ConfigPreset(
            id = System.currentTimeMillis().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            destX = s.destX,
            destZ = s.destZ,
            baseTick = s.baseTick,
            groundHeight = s.groundHeight,
            endHeightMin = s.endHeightMin,
            endHeightMax = s.endHeightMax,
            landingMode = s.landingMode,
            versionId = s.versionId,
            yMode = s.yMode,
            scoringPreset = s.scoringPreset,
            baseX = s.baseX,
            baseY = s.baseY,
            baseZ = s.baseZ,
            baseMx = s.baseMx,
            baseMy = s.baseMy,
            baseMz = s.baseMz,
        )
        // 新的排在最前，常用的手边就有
        val next = listOf(preset) + s.configPresets
        ConfigStore.save(context, next)
        _state.update { it.copy(configPresets = next) }
        // 配置预设板块默认收起 —— 若保存后不展开，
        // 用户点了"保存"却看不到任何变化，会以为没保存成功。
        ensureExpanded(AppPrefs.SEC_CONFIGS)
    }

    /** 应用某个预设：整套参数一起换 */
    fun applyConfig(preset: ConfigPreset) {
        _state.update {
            it.copy(
                destX = preset.destX,
                destZ = preset.destZ,
                baseTick = preset.baseTick,
                groundHeight = preset.groundHeight,
                endHeightMin = preset.endHeightMin,
                endHeightMax = preset.endHeightMax,
                landingMode = preset.landingMode,
                versionId = preset.versionId,
                yMode = preset.yMode,
                scoringPreset = preset.scoringPreset,
                baseX = preset.baseX,
                baseY = preset.baseY,
                baseZ = preset.baseZ,
                baseMx = preset.baseMx,
                baseMy = preset.baseMy,
                baseMz = preset.baseMz,
            )
        }
    }

    /** 重命名。空名回退为原名，避免误操作把名称清空 */
    fun renameConfig(context: Context, id: String, rawName: String) {
        val next = _state.value.configPresets.map { p ->
            if (p.id == id) p.copy(name = rawName.trim().ifBlank { p.name }) else p
        }
        ConfigStore.save(context, next)
        _state.update { it.copy(configPresets = next) }
    }

    fun deleteConfig(context: Context, id: String) {
        val next = _state.value.configPresets.filter { it.id != id }
        ConfigStore.save(context, next)
        _state.update { it.copy(configPresets = next) }
    }

    // ============================================================
    // 分享：导出 / 导入
    // ============================================================

    /** 单个预设 → 分享码 */
    fun shareCodeOf(preset: ConfigPreset): String = PresetShare.encode(preset)

    /** 全部预设 → 一条分享码 */
    fun shareCodeOfAll(): String = PresetShare.encodeAll(_state.value.configPresets)

    /**
     * 导入分享码。
     *
     * 同名处理：直接追加，不覆盖已有条目。
     * id 用"原 id + 时间戳"重新生成，避免与本地条目撞车 ——
     * 否则删除时可能一次删掉两条。
     *
     * @return 导入的条数，0 表示分享码无效
     */
    fun importShareCode(context: Context, code: String): Int {
        val parsed = PresetShare.decode(code)
        if (parsed.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val existing = _state.value.configPresets.map { it.id }.toSet()
        val added = parsed.mapIndexed { i, p ->
            // 换 id：时间戳 + 序号，确保唯一
            p.copy(id = "${now}_$i")
        }.filter { it.id !in existing }

        if (added.isEmpty()) return 0

        val next = added + _state.value.configPresets
        ConfigStore.save(context, next)
        _state.update { it.copy(configPresets = next) }
        ensureExpanded(AppPrefs.SEC_CONFIGS)
        return added.size
    }

    fun restoreAnnouncements(context: Context) {
        _state.update { it.copy(dismissedIds = emptySet()) }
        prefs?.restoreAllAnnouncements()
    }

    fun toggleAnnouncements(context: Context) {
        val next = !_state.value.announcementsExpanded
        _state.update { it.copy(announcementsExpanded = next) }
        prefs?.announcementsExpanded = next
    }

    // ============================================================
    // 折叠
    // ============================================================

    fun toggleSection(key: String) {
        val cur = _state.value.expanded.toMutableSet()
        if (!cur.add(key)) cur.remove(key)
        _state.update { it.copy(expanded = cur) }
        prefs?.expandedSections = cur
    }

    fun isExpanded(key: String): Boolean = _state.value.expanded.contains(key)

    /**
     * 确保某板块处于展开状态（已展开则不动）。
     *
     * 与 toggleSection 的区别：toggle 会把已展开的**收起**，
     * 而"保存配置后让用户看到结果"这种场景需要的是幂等的展开。
     */
    fun ensureExpanded(key: String) {
        val cur = _state.value.expanded.toMutableSet()
        if (cur.contains(key)) return
        cur.add(key)
        _state.update { it.copy(expanded = cur) }
        prefs?.expandedSections = cur
    }

    // ============================================================
    // 物理模型设置
    // ============================================================

    fun setVersion(id: String) {
        _state.update { it.copy(versionId = id) }
        prefs?.versionProfileId = id
    }

    fun setYMode(mode: YAccumMode) {
        _state.update { it.copy(yMode = mode) }
        prefs?.yModeId = mode.name
    }

    fun setScoringPreset(preset: String) {
        _state.update { it.copy(scoringPreset = preset) }
        prefs?.scoringPreset = preset
    }
}
