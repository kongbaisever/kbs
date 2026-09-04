package kbs.data

import android.content.Context
import kbs.core.model.YAccumMode

/**
 * 应用设置持久化。
 *
 * 使用 SharedPreferences，apply() 异步落盘避免阻塞主线程。
 *
 * 只存「偏好」，不存计算输入 —— 后者的生命周期由 ViewModel 的
 * StateFlow 管理，重启后回到默认值反而更符合"每次都是新一次解算"的预期。
 */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============================================================
    // 物理模型设置
    // ============================================================

    /** 版本模型 id（"adp" / "legacy"） */
    var versionProfileId: String
        get() = prefs.getString(KEY_VERSION, "adp") ?: "adp"
        set(v) = prefs.edit().putString(KEY_VERSION, v).apply()

    /** Y 动量累加模式 id */
    var yModeId: String
        get() = prefs.getString(KEY_Y_MODE, YAccumMode.SUM_THEN_ABS.name)
            ?: YAccumMode.SUM_THEN_ABS.name
        set(v) = prefs.edit().putString(KEY_Y_MODE, v).apply()

    /** 排序偏好：error / tnt / tick */
    var scoringPreset: String
        get() = prefs.getString(KEY_SCORING, "error") ?: "error"
        set(v) = prefs.edit().putString(KEY_SCORING, v).apply()

    // ============================================================
    // 外观
    // ============================================================

    /**
     * 主题模式：system / light / dark
     *
     * 默认跟随系统。用户明确要求能手动指定深色或浅色，
     * 因为部分用户会在系统浅色下使用本工具（截图对照游戏画面时
     * 浅色更清晰），也有用户偏好深色（夜间/省电）。
     */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    /**
     * 计算完成后是否自动滚动到结果区。
     *
     * 有人觉得方便，有人觉得被打断 —— 尤其是只想微调一个参数
     * 反复试算时，每次都被拉到底部很烦。默认关闭。
     */
    var autoScrollToResult: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCROLL, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SCROLL, v).apply()

    /** 公告是否展开显示全部（否则只显示最新若干条） */
    var announcementsExpanded: Boolean
        get() = prefs.getBoolean(KEY_ANN_EXPANDED, false)
        set(v) = prefs.edit().putBoolean(KEY_ANN_EXPANDED, v).apply()

    // ============================================================
    // 模块折叠状态
    //
    // 老手收起「新手指南」后应长期保持收起，不必每次重复操作。
    // 存"展开的模块"集合，未记录的即为收起。
    // ============================================================

    var expandedSections: Set<String>
        get() = prefs.getStringSet(KEY_SECTIONS, null) ?: DEFAULT_SECTIONS
        set(v) = prefs.edit().putStringSet(KEY_SECTIONS, v).apply()

    fun isExpanded(key: String): Boolean = expandedSections.contains(key)

    fun setExpanded(key: String, expanded: Boolean) {
        val cur = expandedSections.toMutableSet()
        if (expanded) cur.add(key) else cur.remove(key)
        expandedSections = cur
    }

    // ============================================================
    // 公告
    // ============================================================

    /** 用户关闭过的公告 id —— 关闭状态需跨重启保持 */
    var dismissedAnnouncements: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, null) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_DISMISSED, v).apply()

    fun dismissAnnouncement(id: String) {
        dismissedAnnouncements = dismissedAnnouncements + id
    }

    fun restoreAllAnnouncements() {
        dismissedAnnouncements = emptySet()
    }

    companion object {
        private const val PREFS_NAME = "kbs_pearl_prefs"
        private const val KEY_VERSION = "version_profile"
        private const val KEY_Y_MODE = "y_mode"
        private const val KEY_SCORING = "scoring_preset"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_AUTO_SCROLL = "auto_scroll_result"
        private const val KEY_SECTIONS = "expanded_sections"
        private const val KEY_DISMISSED = "dismissed_announcements"
        private const val KEY_ANN_EXPANDED = "announcements_expanded"

        /** 默认展开：目标、基准、结果 —— 最常用的三项 */
        val DEFAULT_SECTIONS = setOf("destination", "base", "result")

        /** 模块 key 常量 */
        const val SEC_GUIDE = "guide"
        const val SEC_CONFIGS = "configs"
        const val SEC_DESTINATION = "destination"
        const val SEC_BASE = "base"
        const val SEC_SAMPLE = "sample"
        const val SEC_PASTE = "paste"
        const val SEC_ADVANCED = "advanced"
        const val SEC_RESULT = "result"
        const val SEC_FAVORITES = "favorites"
        const val SEC_CONVERTER = "converter"
    }
}
