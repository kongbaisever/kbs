"""
统一工程检查器 —— 在无法运行真实编译器的环境下，尽可能拦截编译错误。

================================================================================
设计原则：每个检查项都必须通过**反向注入测试**验证
================================================================================

历史教训（本项目前十几轮迭代的真实踩坑）：
  · 检查器**误报** → 照着误报"修复" → 亲手制造编译错误
      例：把 Modifier.weight 报成"缺少 import"，补上后命中 internal 声明
  · 检查器**误放行** → 真错误溜过 → CI 失败
      例：把 Context 放进"无需 import"白名单，导致缺 import 也通过

因此本文件的每一条规则都有对应的反向测试（见 scripts/test_checker.py）：
故意注入错误 → 必须报警；恢复 → 必须安静。

================================================================================
检查项
================================================================================
  [1] 括号平衡
  [2] import 完整性（标准库 / Android 框架 / Compose 扩展）
  [3] 作用域成员被误 import（weight / align 等 —— 会命中 internal）
  [4] 依赖声明完整性（直接 import 的第三方库都要显式声明）
  [5] applicationId 合规 + Manifest 类名可展开
  [6] 资源引用完整性
  [7] 调用签名（命名参数名匹配定义）
  [8] 未使用的 import
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'scripts'))

from ktlex import strip_noise, strip_comments_only, split_args  # noqa: E402

SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
MANIFEST = os.path.join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml')
GRADLE = os.path.join(ROOT, 'app', 'build.gradle.kts')

issues = []


def err(msg, detail=None):
    issues.append(msg)
    print(f"  [错误] {msg}")
    if detail:
        for d in detail:
            print(f"         {d}")


def warn(msg):
    print(f"  [警告] {msg}")


def ok(msg):
    print(f"  [OK] {msg}")


def all_kt():
    out = []
    for dp, _, fs in os.walk(SRC):
        for f in sorted(fs):
            if f.endswith('.kt'):
                out.append(os.path.join(dp, f))
    return out


def rel(p):
    return os.path.relpath(p, SRC)


# ============================================================
# [1] 括号平衡
# ============================================================
def check_balance():
    print("=" * 74)
    print("[1] 括号平衡")
    print("=" * 74)
    pairs = {')': '(', ']': '[', '}': '{'}
    bad = 0
    for p in all_kt():
        code = strip_comments_only(open(p, encoding='utf-8').read())
        stack = []
        line = 1
        broken = False
        for ch in code:
            if ch == '\n':
                line += 1
            elif ch in '([{':
                stack.append((ch, line))
            elif ch in ')]}':
                if not stack or stack[-1][0] != pairs[ch]:
                    err(f"{rel(p)}:{line} 括号不匹配：多余的 {ch}")
                    broken = True
                    bad += 1
                    break
                stack.pop()
        if not broken and stack:
            ch, ln = stack[-1]
            err(f"{rel(p)}:{ln} 括号未闭合：{ch}（剩余 {len(stack)} 层）")
            bad += 1
    if bad == 0:
        ok(f"{len(all_kt())} 个文件括号全部平衡 ✓")


# ============================================================
# [2] import 完整性
# ============================================================

# 需要显式 import 的 kotlin 标准库函数（kotlin.math / kotlin.collections 等）
KOTLIN_STDLIB_FUNCS = {
    'abs': 'kotlin.math.abs',
    'max': 'kotlin.math.max',
    'min': 'kotlin.math.min',
    'round': 'kotlin.math.round',
    'ceil': 'kotlin.math.ceil',
    'floor': 'kotlin.math.floor',
    'sqrt': 'kotlin.math.sqrt',
    'hypot': 'kotlin.math.hypot',
    'pow': 'kotlin.math.pow',
    'coerceIn': 'kotlin.ranges.coerceIn',
    'coerceAtMost': 'kotlin.ranges.coerceAtMost',
    'coerceAtLeast': 'kotlin.ranges.coerceAtLeast',
    'joinToString': '(成员函数，无需 import)',
    'format': '(String 方法，无需 import)',
}

# Android / Java 框架类 —— 必须显式 import
# ★ 只有 java.lang.* 与 kotlin.* 是自动导入的，android.* / androidx.* 全部需要
ANDROID_FRAMEWORK = {
    'Context': 'android.content.Context',
    'Intent': 'android.content.Intent',
    'Uri': 'android.net.Uri',
    'Bundle': 'android.os.Bundle',
    'Toast': 'android.widget.Toast',
    'Log': 'android.util.Log',
    'Build': 'android.os.Build',
    'Locale': 'java.util.Locale',
    'Date': 'java.util.Date',
    'SimpleDateFormat': 'java.text.SimpleDateFormat',
    'File': 'java.io.File',
    'IOException': 'java.io.IOException',
    'URL': 'java.net.URL',
    'JSONObject': 'org.json.JSONObject',
    'JSONArray': 'org.json.JSONArray',
    'FileProvider': 'androidx.core.content.FileProvider',
    'ClipboardManager': 'android.content.ClipboardManager',
    'ClipData': 'android.content.ClipData',
    'SharedPreferences': 'android.content.SharedPreferences',
}

# Compose 扩展函数 —— 必须显式 import
# ============================================================
# Compose 组件 / 函数 → 所属包
#
# ★★★ 这张表来自一次真实的 CI 失败 ★★★
#
#   CI 报了 5 个 Unresolved reference：
#       MainActivity.kt  getValue（by 委托需要）
#       CalcScreen.kt    remember / Box / IconButton
#   而本地 10 项检查全绿。
#
#   根因：check_imports() 只覆盖三张表 ——
#       Kotlin 标准库函数 / Android 框架类 / Modifier 扩展函数。
#   **Compose 组件与函数完全不在检查范围内**。
#   remember、Box、IconButton 一个都不在表上，
#   于是"用了但没 import"这种最基础的错误被 100% 放行。
#
#   补充：getValue 尤其值得记一笔。它不表现为函数调用
#   `getValue(`，而是 `by remember { }` 委托的幕后方法，
#   历史上曾因"看起来像未使用 import"被误删过一次，
#   这次 CI 证明它确实必需。
# ============================================================
COMPOSE_API = {
    # ---- runtime ----
    'remember': 'androidx.compose.runtime.remember',
    'mutableStateOf': 'androidx.compose.runtime.mutableStateOf',
    'getValue': 'androidx.compose.runtime.getValue',
    'setValue': 'androidx.compose.runtime.setValue',
    'LaunchedEffect': 'androidx.compose.runtime.LaunchedEffect',
    'collectAsState': 'androidx.compose.runtime.collectAsState',
    'rememberSaveable': 'androidx.compose.runtime.saveable.rememberSaveable',
    'derivedStateOf': 'androidx.compose.runtime.derivedStateOf',
    'DisposableEffect': 'androidx.compose.runtime.DisposableEffect',
    'SideEffect': 'androidx.compose.runtime.SideEffect',
    'CompositionLocalProvider': 'androidx.compose.runtime.CompositionLocalProvider',
    # ---- foundation.layout ----
    'Box': 'androidx.compose.foundation.layout.Box',
    'Column': 'androidx.compose.foundation.layout.Column',
    'Row': 'androidx.compose.foundation.layout.Row',
    'Spacer': 'androidx.compose.foundation.layout.Spacer',
    'BoxWithConstraints': 'androidx.compose.foundation.layout.BoxWithConstraints',
    # ---- foundation ----
    'LazyColumn': 'androidx.compose.foundation.lazy.LazyColumn',
    'LazyRow': 'androidx.compose.foundation.lazy.LazyRow',
    'rememberLazyListState': 'androidx.compose.foundation.lazy.rememberLazyListState',
    #
    # ★ background / border / clickable **刻意不列在本表**。
    #   它们既是 Modifier 扩展函数，又是 colorScheme 的参数名
    #   （`background = SurfaceDark`）。若放进"出现即检查"的表里，
    #   会把命名参数误判成函数调用 —— 实测已产生过误报。
    #   它们的检查由下方的 COMPOSE_EXT 段负责，
    #   那段要求后面紧跟 `(`，不会误伤命名参数。
    'isSystemInDarkTheme': 'androidx.compose.foundation.isSystemInDarkTheme',
    # ---- material3 ----
    'Text': 'androidx.compose.material3.Text',
    'Button': 'androidx.compose.material3.Button',
    'OutlinedButton': 'androidx.compose.material3.OutlinedButton',
    'TextButton': 'androidx.compose.material3.TextButton',
    'Icon': 'androidx.compose.material3.Icon',
    'IconButton': 'androidx.compose.material3.IconButton',
    'Card': 'androidx.compose.material3.Card',
    'CardDefaults': 'androidx.compose.material3.CardDefaults',
    'Switch': 'androidx.compose.material3.Switch',
    'Slider': 'androidx.compose.material3.Slider',
    'Checkbox': 'androidx.compose.material3.Checkbox',
    'Divider': 'androidx.compose.material3.Divider',
    'HorizontalDivider': 'androidx.compose.material3.HorizontalDivider',
    'Surface': 'androidx.compose.material3.Surface',
    'Scaffold': 'androidx.compose.material3.Scaffold',
    'TopAppBar': 'androidx.compose.material3.TopAppBar',
    'TopAppBarDefaults': 'androidx.compose.material3.TopAppBarDefaults',
    'DropdownMenu': 'androidx.compose.material3.DropdownMenu',
    'DropdownMenuItem': 'androidx.compose.material3.DropdownMenuItem',
    'OutlinedTextField': 'androidx.compose.material3.OutlinedTextField',
    'MaterialTheme': 'androidx.compose.material3.MaterialTheme',
    'darkColorScheme': 'androidx.compose.material3.darkColorScheme',
    'lightColorScheme': 'androidx.compose.material3.lightColorScheme',
    'ExperimentalMaterial3Api': 'androidx.compose.material3.ExperimentalMaterial3Api',
    # ---- animation ----
    'AnimatedVisibility': 'androidx.compose.animation.AnimatedVisibility',
    'fadeIn': 'androidx.compose.animation.fadeIn',
    'fadeOut': 'androidx.compose.animation.fadeOut',
    'expandVertically': 'androidx.compose.animation.expandVertically',
    'shrinkVertically': 'androidx.compose.animation.shrinkVertically',
    # ---- ui ----
    'Modifier': 'androidx.compose.ui.Modifier',
    'Alignment': 'androidx.compose.ui.Alignment',
    'drawWithContent': 'androidx.compose.ui.draw.drawWithContent',
}

COMPOSE_EXT = {
    'background': 'androidx.compose.foundation.background',
    'border': 'androidx.compose.foundation.border',
    'clickable': 'androidx.compose.foundation.clickable',
    'horizontalScroll': 'androidx.compose.foundation.horizontalScroll',
    'verticalScroll': 'androidx.compose.foundation.verticalScroll',
    'padding': 'androidx.compose.foundation.layout.padding',
    'size': 'androidx.compose.foundation.layout.size',
    'width': 'androidx.compose.foundation.layout.width',
    'widthIn': 'androidx.compose.foundation.layout.widthIn',
    'height': 'androidx.compose.foundation.layout.height',
    'heightIn': 'androidx.compose.foundation.layout.heightIn',
    'fillMaxWidth': 'androidx.compose.foundation.layout.fillMaxWidth',
    'fillMaxHeight': 'androidx.compose.foundation.layout.fillMaxHeight',
    'fillMaxSize': 'androidx.compose.foundation.layout.fillMaxSize',
    'wrapContentWidth': 'androidx.compose.foundation.layout.wrapContentWidth',
    'rememberScrollState': 'androidx.compose.foundation.rememberScrollState',
    # 注意：drawLine / drawPath / drawCircle 等是 DrawScope 的成员函数，
    #       在 Canvas { } 的 lambda 里 receiver 就是 DrawScope，直接可用。
    #       放进这张表会造成误报（与 Modifier.weight 同类问题）。
}


def _imported_simple_names(code):
    return {m.group(1).split('.')[-1]
            for m in re.finditer(r'^import\s+([\w.]+)', code, re.M)}


def _local_defs(code):
    """本文件定义的类型/函数/常量名（避免把自身定义当成缺 import）"""
    names = set()
    for m in re.finditer(
            r'^\s*(?:private\s+|internal\s+|public\s+)?'
            r'(?:data\s+|enum\s+|sealed\s+)*'
            r'(?:class|object|interface)\s+(\w+)', code, re.M):
        names.add(m.group(1))
    for m in re.finditer(
            r'^\s*(?:private\s+|internal\s+|public\s+)?'
            r'(?:@\w+\s+)*fun\s+(\w+)\s*\(', code, re.M):
        names.add(m.group(1))
    for m in re.finditer(r'^\s*(?:const\s+)?val\s+(\w+)\s*[:=]', code, re.M):
        names.add(m.group(1))
    for m in re.finditer(r'^\s*var\s+(\w+)\s*[:=]', code, re.M):
        names.add(m.group(1))
    return names


def check_imports():
    print()
    print("=" * 74)
    print("[2] import 完整性")
    print("=" * 74)
    print("  ★ 只有 java.lang.* 与 kotlin.* 自动导入。")
    print("    android.* / androidx.* / java.io.* 等全部需要显式 import。")

    missing = 0
    for p in all_kt():
        raw = open(p, encoding='utf-8').read()
        code = strip_noise(raw)
        imported = _imported_simple_names(code)
        local = _local_defs(code)
        relp = rel(p)

        # ---- kotlin 标准库函数 ----
        for fn, path in KOTLIN_STDLIB_FUNCS.items():
            if path.startswith('('):
                continue
            if fn in imported or fn in local:
                continue
            if re.search(r'(?<![\w.])' + fn + r'\s*\(', code):
                err(f"{relp}: 使用了 {fn}() 但缺少 import {path}")
                missing += 1

        # ---- Android 框架类 ----
        for cls, path in ANDROID_FRAMEWORK.items():
            if cls in imported or cls in local:
                continue
            if re.search(re.escape(path) + r'\b', code):
                continue
            # 作为类型使用：后跟 : < ( ) , 或 行尾
            if re.search(r'(?<![\w.])' + cls + r'\b(?=\s*[,):<.\s]|$)', code):
                for i, line in enumerate(raw.split('\n'), 1):
                    if re.search(r'(?<![\w.])' + cls + r'\b', line):
                        err(f"{relp}:{i} 使用了 {cls} 但缺少 import {path}",
                            [line.strip()[:70]])
                        break
                missing += 1

        # ---- Compose 扩展函数 ----
        for fn, path in COMPOSE_EXT.items():
            simple = path.split('.')[-1]
            if simple in imported:
                continue
            if re.search(r'\.' + fn + r'\s*\(', code) or \
                    re.search(r'(?<![\w.])' + fn + r'\s*\(', code):
                err(f"{relp}:  Modifier.{fn}() 缺少 import {path}")
                missing += 1

        # ---- Compose 组件 / 函数 ----
        #
        # 这是本次 CI 失败暴露出的最大盲区：
        #   remember / Box / IconButton / getValue 缺 import，
        #   而前三张表一张都没覆盖到。
        #
        # 匹配方式不能只看 `name(` ——
        #   · 组件名作类型/表达式首词出现，如 `Box { }`、`Text("x")`
        #   · getValue 根本不写成函数调用，而是 `by` 委托
        # 因此这里做**标识符出现即检查**：只要该名字在本文件中
        # 作为独立标识符出现，就必须已 import。
        #
        # 但必须排除命名参数：
        #   `background = SurfaceDark` 中的 background 是
        #   lightColorScheme() 的参数名，不是函数调用。
        #   判据是标识符后面紧跟 `=` 且不是 `==`。
        for name, path in COMPOSE_API.items():
            simple = path.split('.')[-1]
            if simple in imported:
                continue
            if simple in local:
                continue
            hit = None
            for m in re.finditer(r'(?<![\w.])' + re.escape(simple) + r'\b',
                                 code):
                # 后面紧跟 = （但非 ==）→ 是命名参数，跳过这一处
                rest = code[m.end():m.end() + 3].lstrip()
                if rest.startswith('=') and not rest.startswith('=='):
                    continue
                hit = m
                break
            if hit is None:
                continue
            line_no = code[:hit.start()].count('\n') + 1
            line = code.split('\n')[line_no - 1].strip()
            err(f"{relp}:{line_no} 使用了 {simple} 但缺少 import {path}",
                [f"该行: {line[:70]}"])
            missing += 1

        # ---- by 委托需要 getValue / setValue ----
        #
        # ★ 这一项不可能靠"文本里出现 getValue"查出来：
        #   `by remember { mutableStateOf(x) }` 编译后会调用
        #   getValue(...) / setValue(...)，但**源码里根本不写这两个词**。
        #   所以只能反过来：检测到 `by` 委托，就要求它们已 import。
        #
        #   历史教训：getValue 曾因"看起来像未使用的 import"
        #   被自动清理脚本删掉，导致 CI 报
        #     "Type 'TypeVariable(T)' has no method 'getValue(...)'"
        uses_delegate = re.search(r'\bby\s+(?:remember|mutableStateOf|'
                                  r'viewModel|derivedStateOf|lazy)\b', code)
        if uses_delegate:
            for need in ('getValue', 'setValue'):
                if need in imported:
                    continue
                # setValue 只在 var（可写）委托时才必需；
                # val 委托不需要。这里宽松处理：getValue 必检，
                # setValue 仅当存在 var ... by 时才检。
                if need == 'setValue':
                    if not re.search(r'\bvar\s+\w+\s+by\s+', code):
                        continue
                m = uses_delegate
                line_no = code[:m.start()].count('\n') + 1
                err(f"{relp}:{line_no} 使用了 `by` 委托但缺少 "
                    f"import androidx.compose.runtime.{need}",
                    [f"该行: {code.split(chr(10))[line_no - 1].strip()[:70]}",
                     "委托是编译期展开的，源码里看不到 getValue 调用，"
                     "必须靠这条规则兜住"])
                missing += 1

    if missing == 0:
        ok("所有 import 完整 ✓")


# ============================================================
# [2c] @OptIn 注解位置
# ============================================================
def check_optin_placement():
    """
    Kotlin 的注解作用于**紧随其后的那一个声明**。

    本项目真实踩过：

        @OptIn(ExperimentalMaterial3Api::class)
        /** 公告收起时显示的条数 */
        private const val PREVIEW_COUNT = 2

        @Composable
        fun CalcScreen(...) { TopAppBar(...) }      ← 真正需要 OptIn 的是这里

    注解被"常量声明"吃掉了，CalcScreen 反而没有注解，
    于是 CI 报：
        "This material API is experimental and is likely to change"

    这类错误静态很容易查：只要 @OptIn 后面跟的不是
    fun / class / 带 @Composable 的声明，就要报警。
    """
    print()
    print("=" * 74)
    print("[2c] @OptIn 注解位置")
    print("=" * 74)
    print("  ★ 注解只作用于紧随其后的**一个声明**。")
    print("    贴在常量/属性上会白贴，真正需要它的函数仍然报错。")

    bad = 0

    # ------------------------------------------------------------
    # import 路径里混进成员名
    #
    # ★★★ 本项目真实踩过，是**编译错误** ★★★
    #
    #   一次全量替换把源码里所有 `PearlTeal` 换成
    #   `LocalAccent.current`，**连 import 行一起改了**：
    #
    #       import kbs.ui.theme.PearlTeal
    #     → import kbs.ui.theme.LocalAccent.current   ← 非法
    #
    #   Kotlin 的 import 只能导到**声明**（类/对象/顶层函数/顶层属性），
    #   不能导到某个对象的成员。`.current` 是 CompositionLocal 的属性。
    #
    #   判据：倒数第二段首字母大写（是类型/对象），
    #         最后一段首字母小写 → 那一段是成员，非法。
    #
    #   注意不能禁止一切小写结尾：
    #   `import kotlin.math.abs` 合法（顶层函数就是小写）。
    #   区别在于它的倒数第二段是包名（全小写）。
    # ------------------------------------------------------------
    for p in all_kt():
        raw = open(p, encoding='utf-8').read()
        r = rel(p)
        for i, line in enumerate(raw.split('\n'), 1):
            st = line.strip()
            if not st.startswith('import ') or st.endswith('.*'):
                continue
            path = st[len('import '):].split()[0]
            parts = path.split('.')
            if len(parts) < 3:
                continue
            prev, last = parts[-2], parts[-1]
            if not prev or not last:
                continue
            # 倒数第二段是"类型/对象"（首字母大写），
            # 最后一段是"成员"（首字母小写）→ 非法
            if prev[0].isupper() and last[0].islower():
                err(f"{r}:{i} import 路径含成员名「.{last}」",
                    [f"该行: {st}",
                     f"import 只能导到声明，不能导到 {prev} 的成员",
                     f"应改为: import {'.'.join(parts[:-1])}"])
                bad += 1

    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')

        for m in re.finditer(r'@OptIn\s*\([^)]*\)', code):
            line_no = code[:m.start()].count('\n') + 1
            # 从注解下一行开始，跳过空行与 KDoc 注释，
            # 找到第一个真正的声明
            j = line_no  # lines 是 0 基，line_no 是 1 基 → 下一个是 index line_no
            while j < len(lines):
                s = lines[j].strip()
                if s == '' or s.startswith('//') or s.startswith('*') \
                        or s.startswith('/*') or s.startswith('*/'):
                    j += 1
                    continue
                break

            if j >= len(lines):
                err(f"{r}:{line_no} @OptIn 位于文件末尾，未作用于任何声明")
                bad += 1
                continue

            decl = lines[j].strip()

            # 合法目标：函数、带 @Composable 的函数、类、属性 getter
            ok_target = (
                re.match(r'(?:@\w+\s+)*'
                         r'(?:private\s+|internal\s+|public\s+|inline\s+|'
                         r'suspend\s+|override\s+|open\s+)*'
                         r'fun\b', decl)
                or re.match(r'(?:@\w+\s+)*'
                            r'(?:private\s+|internal\s+|public\s+|'
                            r'data\s+|sealed\s+)*'
                            r'(?:class|object|interface)\b', decl)
                or decl.startswith('@Composable')
                or re.match(r'(?:private\s+|internal\s+)?'
                            r'(?:val|var)\s+\w+\s*:\s*[\w.<>?]+\s*'
                            r'get\(\)', decl)
            )

            if not ok_target:
                err(f"{r}:{line_no} @OptIn 未作用于函数或类",
                    [f"其后第一个声明是（第 {j + 1} 行）: {decl[:62]}",
                     "注解只影响紧随其后的一个声明；"
                     "若后面是常量/属性，注解就白贴了"])
                bad += 1

    if bad == 0:
        ok("所有 @OptIn 均正确作用于函数或类 ✓")


# ============================================================
# [3] 作用域成员被误 import
# ============================================================
SCOPE_MEMBERS = {
    # ---- Compose 布局作用域 ----
    'weight': 'RowScope / ColumnScope',
    'align': 'RowScope / ColumnScope / BoxScope',
    'alignBy': 'RowScope / ColumnScope',
    'alignByBaseline': 'RowScope',
    'matchParentSize': 'BoxScope',

    # ---- DrawScope：在 Canvas { } 内直接可用 ----
    'drawLine': 'DrawScope',
    'drawPath': 'DrawScope',
    'drawCircle': 'DrawScope',
    'drawRect': 'DrawScope',
    'drawRoundRect': 'DrawScope',
    'drawArc': 'DrawScope',
    'drawOval': 'DrawScope',
    'drawPoints': 'DrawScope',
    'drawImage': 'DrawScope',
}


def check_import_paths():
    print()
    print("=" * 74)
    print("[2b] import 路径合法性")
    print("=" * 74)
    print("  ★ import 必须写**完整包路径**。")
    print("    写成 'import AnimatedVisibility' 会直接 Unresolved reference。")
    print()
    print("    这个错误的真实来源：用脚本做「全限定名 → 简名」清理时，")
    print("    没有跳过 import 行，把路径一起截断了 —— 本项目真实发生过。")

    bad = 0
    for p in all_kt():
        for i, line in enumerate(open(p, encoding='utf-8').read().split('\n'), 1):
            s = line.strip()
            if not s.startswith('import '):
                continue
            rest = s[len('import '):].strip()
            if not rest:
                continue
            # 去掉 as 别名
            rest = rest.split(' as ')[0].strip()
            if '.' not in rest:
                err(f"{rel(p)}:{i} import 路径不完整: {s}",
                    ["import 必须写完整包路径，例如 "
                     "import androidx.compose.animation.AnimatedVisibility"])
                bad += 1
    if bad == 0:
        ok(f"{len(all_kt())} 个文件的 import 路径均完整 ✓")


def check_scope_members():
    print()
    print("=" * 74)
    print("[3] 作用域成员被误 import")
    print("=" * 74)
    print("  RowScope/ColumnScope 的成员函数（如 weight）在对应 lambda 内直接可用。")
    print("  一旦 import，会命中同包的 internal 声明")
    print("  → \"Cannot access 'weight': it is internal in '...foundation.layout'\"")

    bad = 0
    for p in all_kt():
        raw = open(p, encoding='utf-8').read()
        for fn, scope in SCOPE_MEMBERS.items():
            if re.search(r'^import\s+androidx\.compose\.[\w.]*\.' + fn + r'\s*$',
                         raw, re.M):
                err(f"{rel(p)}: 不应 import ...{fn}",
                    [f"它是 {scope} 的成员函数，在 content lambda 内直接可用"])
                bad += 1
    if bad == 0:
        ok("无作用域成员被误 import ✓")


# ============================================================
# [4] 依赖声明完整性
# ============================================================
MODULE_MAP = {
    'androidx.compose.animation': ('androidx.compose.animation:animation', 'Compose 动画'),
    'androidx.compose.foundation': ('androidx.compose.foundation:foundation', 'Compose 基础'),
    'androidx.compose.material3': ('androidx.compose.material3:material3', 'Material3'),
    'androidx.compose.runtime': ('androidx.compose.runtime:runtime', 'Compose 运行时'),
    'androidx.compose.ui': ('androidx.compose.ui:ui', 'Compose UI'),
    'androidx.activity': ('androidx.activity:activity-compose', 'Activity+Compose'),
    'androidx.lifecycle': ('androidx.lifecycle:lifecycle', 'ViewModel/生命周期'),
    'androidx.core': ('androidx.core:core-ktx', 'AndroidX Core'),
    'kotlinx.coroutines': ('kotlinx-coroutines', '协程'),
}


def check_dependencies():
    print()
    print("=" * 74)
    print("[4] 依赖声明完整性")
    print("=" * 74)
    print("  ★ 直接 import 的模块必须显式声明。")
    print("    依赖传递依赖 → 运行期 NoClassDefFoundError → 启动即闪退，")
    print("    而编译完全不报错，极难排查。")

    if not os.path.exists(GRADLE):
        err("找不到 app/build.gradle.kts")
        return

    g = open(GRADLE, encoding='utf-8').read()
    used = set()
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        for m in re.finditer(r'^import\s+(androidx\.compose\.\w+|androidx\.\w+|kotlinx\.coroutines[\w.]*)',
                             code, re.M):
            used.add(m.group(1))

    missing = 0
    for mod in sorted(used):
        hit = False
        known = False
        for prefix, (coord, _desc) in MODULE_MAP.items():
            if mod == prefix or mod.startswith(prefix + '.'):
                known = True
                if ':' in coord:
                    group, artifact = coord.split(':', 1)
                    # 精确匹配 group:artifact（允许 -xxx 变体与 :version 后缀）
                    pattern = re.escape(group) + r':' + re.escape(artifact) + \
                              r'(?:-[\w-]+)?(?::[\w.-]+)?["\']'
                    hit = bool(re.search(pattern, g))
                else:
                    # 无 group 的简写（如 kotlinx-coroutines），直接查子串
                    hit = coord in g
                break
        if not hit:
            if known:
                err(f"代码直接 import 了 {mod}，但 build.gradle.kts 未声明对应依赖",
                    ["运行期 NoClassDefFoundError → 启动即闪退，而编译不报错"])
                missing += 1
            else:
                print(f"  [提示] {mod} 不在已知映射表，请人工确认是否需要声明")
        else:
            print(f"  [OK] {mod}")

    if missing == 0:
        ok("所有直接 import 的模块均已声明 ✓")


# ============================================================
# [5] applicationId 合规 + Manifest 类名可展开
# ============================================================
def check_package():
    print()
    print("=" * 74)
    print("[5] applicationId 合规 + Manifest 类名可展开")
    print("=" * 74)
    print("  ★ applicationId 必须至少两段（含一个点）。")
    print("    单段包名 → PackageParser 异常 → 图标回退默认 + 启动闪退。")

    if not os.path.exists(GRADLE):
        return
    g = open(GRADLE, encoding='utf-8').read()
    m_id = re.search(r'applicationId\s*=\s*"([^"]+)"', g)
    m_ns = re.search(r'namespace\s*=\s*"([^"]+)"', g)
    app_id = m_id.group(1) if m_id else None
    ns = m_ns.group(1) if m_ns else None

    print(f"  applicationId = {app_id}")
    print(f"  namespace     = {ns}")

    if app_id and '.' not in app_id:
        err(f"applicationId \"{app_id}\" 是单段包名 —— 违反 Android 硬性规则",
            [f"必须是至少两段，例如 \"{app_id}.pearl\"",
             "单段包名会导致启动时崩溃且图标显示异常"])
    elif app_id:
        ok(f"applicationId \"{app_id}\" 合规 ✓")

    if ns and os.path.exists(MANIFEST):
        print()
        print(f"  Manifest 相对类名（以 namespace \"{ns}\" 展开）:")
        mani = open(MANIFEST, encoding='utf-8').read()
        for m in re.finditer(r'android:name="(\.[^"]+)"', mani):
            r = m.group(1)
            full = ns + r
            parts = full.split('.')
            src_root = os.path.join(SRC)
            found = any(os.path.exists(os.path.join(src_root, *parts) + ext)
                        for ext in ('.kt', '.java'))
            if found:
                print(f"    [OK] {r} -> {full}")
            else:
                err(f"Manifest 中 {r} 展开为 {full}，找不到源文件",
                    ["namespace 与代码 package 不一致会导致 ClassNotFoundException"])


# ============================================================
# [6] 资源引用完整性
# ============================================================
def check_resources():
    print()
    print("=" * 74)
    print("[6] 资源引用完整性")
    print("=" * 74)

    if not os.path.exists(RES):
        err("找不到 res 目录")
        return

    # 收集已有资源
    # ★ 注意两类资源的命名来源不同：
    #   - drawable / mipmap 等：资源名 = **文件名**（不含扩展名与密度限定符）
    #   - values 下的 XML：资源名在**文件内容**的 name 属性里
    #     （如 <string name="app_name">），且可能带点号（如 Theme.KbsPearl）
    have = set()
    for dp, _, fs in os.walk(RES):
        folder = os.path.basename(dp).split('-')[0]
        for f in fs:
            if folder == 'values' and f.endswith('.xml'):
                content = open(os.path.join(dp, f), encoding='utf-8').read()
                for m in re.finditer(
                        r'<(string|style|color|dimen|bool|integer|array)\s+name="([^"]+)"',
                        content):
                    have.add(f"{m.group(1)}/{m.group(2)}")
            else:
                have.add(f"{folder}/{f.split('.')[0]}")

    refs = set()
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        for m in re.finditer(r'@(drawable|mipmap|string|style|xml|color)/([\w.]+)', code):
            refs.add((m.group(1), m.group(2)))
    if os.path.exists(MANIFEST):
        mani = open(MANIFEST, encoding='utf-8').read()
        for m in re.finditer(r'@(drawable|mipmap|string|style|xml|color)/([\w.]+)', mani):
            refs.add((m.group(1), m.group(2)))

    bad = 0
    for typ, name in sorted(refs):
        if typ == '__android__':
            continue
        key = f"{typ}/{name}"
        if key not in have:
            err(f"引用了 @{typ}/{name}，但 res 下不存在")
            bad += 1
        else:
            print(f"    [OK] @{typ}/{name}")

    if bad == 0:
        ok(f"{len(refs)} 处资源引用全部有效 ✓")


# ============================================================
# [7] 调用签名（命名参数名匹配定义）
# ============================================================
def check_duplicate_else():
    """
    检测同一 if 上出现两个 else 分支。

    ============================================================
    真实事故（CI 编译失败）
    ============================================================

    一次编辑把

        color = if (slot.lit) Color(0xFF04201C)
        else MaterialTheme.colorScheme.onSurfaceVariant,

    中的**第一行**替换成完整的 if/else 两行。
    但替换脚本不知道原文件下一行**已经有一个 else**，于是变成：

        color = if (slot.lit) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant     <- 新增
        else MaterialTheme.colorScheme.onSurfaceVariant,    <- 原有，重复

    编译器报：
        Expecting ')' / Expecting an element /
        Unresolved reference: MaterialTheme

    ============================================================
    为什么括号检查发现不了
    ============================================================

    括号是配平的 —— 只是多了一个 else 表达式。
    这类"语法树层面"的问题，正则数括号永远看不到。

    ============================================================
    判据
    ============================================================

    连续两行都以 `else` 开头，且中间没有其他语句 → 重复。
    """
    print()
    print("=" * 74)
    print("[2d] 重复的 else 分支")
    print("=" * 74)
    print("  ★ 一次替换若把 `if (...) X` 展开成 `if (...) X\n else Y`，")
    print("    而原处下一行已有 else，就会产生两个 else。")

    bad = 0
    for p in all_kt():
        lines = open(p, encoding='utf-8').read().split('\n')
        r = rel(p)
        prev_else = False
        for i, line in enumerate(lines, 1):
            st = line.strip()
            is_else = bool(re.match(r'^else\b', st))
            if is_else and prev_else:
                err(f"{r}:{i} 出现连续的第二个 else",
                    [f"上一行: {lines[i - 2].strip()[:66]}",
                     f"本行:   {st[:66]}",
                     "多半是替换时原 else 未被一并替换；"
                     "保留一个分支即可"])
                bad += 1
            prev_else = is_else

    if bad == 0:
        ok("无重复的 else 分支 ✓")


def check_call_signatures():
    print()
    print("=" * 74)
    print("[7] 调用签名：命名参数名是否匹配定义")
    print("=" * 74)

    # 收集顶层/成员函数的参数列表
    sigs = {}
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        for m in re.finditer(
                r'^(?:private\s+|internal\s+|public\s+)?(?:@\w+(?:\([^)]*\))?\s+)*'
                r'fun\s+(\w+)\s*\(', code, re.M):
            fname = m.group(1)
            start = m.end() - 1
            depth = 0
            params_src = ''
            for i in range(start, min(start + 3000, len(code))):
                ch = code[i]
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                    if depth == 0:
                        params_src = code[start + 1:i]
                        break
            names = []
            for part in split_args(params_src):
                pm = re.match(r'\s*(?:val\s+|var\s+)?(\w+)\s*:\s*', part)
                if pm:
                    names.append(pm.group(1))
            sigs.setdefault(fname, []).append((rel(p), names))

    bad = 0
    for p in all_kt():
        raw = open(p, encoding='utf-8').read()
        code = strip_noise(raw)
        lines = code.split('\n')
        for fname, defs in sigs.items():
            for i, line in enumerate(lines, 1):
                if not re.search(r'(?<![\w.])' + fname + r'\s*\(', line):
                    continue
                # 跳过定义行
                if re.search(r'\bfun\s+' + fname + r'\s*\(', line):
                    continue
                start_col = line.index(fname + '(') + len(fname)
                depth = 0
                buf = ''
                end_col = None
                for j in range(i - 1, min(len(lines), i + 30)):
                    seg = lines[j]
                    if j == i - 1:
                        seg = seg[start_col:]
                    for k, ch in enumerate(seg):
                        if ch == '(':
                            depth += 1
                        elif ch == ')':
                            depth -= 1
                            if depth == 0:
                                end_col = k
                                break
                        buf += ch
                    if end_col is not None:
                        break
                    buf += '\n'
                if end_col is None:
                    continue

                inner = buf[1:] if buf.startswith('(') else buf
                named = []
                for part in split_args(inner):
                    s = part.strip()
                    if not s:
                        continue
                    nm = re.match(r'^(\w+)\s*=(?!=)', s)
                    if nm:
                        named.append(nm.group(1))
                if not named:
                    continue

                allnames = {n for _, ns in defs for n in ns}
                wrong = [n for n in named if n not in allnames]
                if wrong:
                    err(f"{rel(p)}:{i} 调用 {fname}() 的参数名 {wrong} 不在定义中",
                        [f"可用参数名: {sorted(allnames)}",
                         f"该行: {line.strip()[:70]}"])
                    bad += 1
    if bad == 0:
        ok("所有命名参数都与定义匹配 ✓")


# ============================================================
# [8] 未使用的 import
# ============================================================
def check_unused_imports():
    print()
    print("=" * 74)
    print("[8] 未使用的 import")
    print("=" * 74)
    unused = 0
    for p in all_kt():
        raw = open(p, encoding='utf-8').read()
        code = strip_noise(raw)
        # 去掉 import 行后再统计
        body = '\n'.join(l for l in code.split('\n')
                         if not l.strip().startswith('import '))
        for m in re.finditer(r'^import\s+([\w.]+)', raw, re.M):
            full = m.group(1)
            simple = full.split('.')[-1]
            # by 委托会用到 getValue/setValue，但它们由 import 提供
            if simple in ('getValue', 'setValue'):
                continue
            if simple in ('Composable', 'OptIn', 'ExperimentalMaterial3Api'):
                continue
            # ★ 前面允许是点号：Compose 扩展函数以 `.background(` 形式调用，
            #   若用 (?<![\w.]) 会把它们全部误判为"未使用"。
            #   只排除前面是字母数字/下划线的情况（避免 mybackground 误匹配）。
            if not re.search(r'(?<![\w])' + re.escape(simple) + r'\b', body):
                print(f"  [提示] {rel(p)}: import {full} 未被使用")
                unused += 1
    if unused == 0:
        ok("无未使用的 import ✓")
    else:
        warn(f"{unused} 处未使用的 import（不影响编译）")


# ============================================================
# ============================================================
# [9] 公告 JSON 合法性
# ============================================================
def check_announcements():
    print()
    print("=" * 74)
    print("[9] 公告 JSON 合法性")
    print("=" * 74)
    print("  ★ id 必须唯一且稳定 —— 用户关闭某条公告后，")
    print("    手机里记的是这个 id。改了 id 会让已关闭的公告重新弹出。")

    path = os.path.join(ROOT, 'announcements.json')
    if not os.path.exists(path):
        print("  [提示] 未找到 announcements.json（App 会用内置公告，不影响构建）")
        return

    import json
    try:
        data = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        err(f"announcements.json 解析失败: {e}")
        return

    if not isinstance(data, list):
        err("announcements.json 顶层必须是数组")
        return

    ids = set()
    bad = 0
    for i, a in enumerate(data):
        if not isinstance(a, dict):
            err(f"第 {i} 条不是对象")
            bad += 1
            continue
        aid = a.get('id', '')
        if not aid:
            err(f"第 {i} 条缺少 id")
            bad += 1
            continue
        if aid in ids:
            err(f"公告 id 重复: {aid}")
            bad += 1
        ids.add(aid)

        if not a.get('title'):
            err(f"公告 {aid} 缺少 title")
            bad += 1

        level = str(a.get('level', 'INFO')).upper()
        if level not in ('INFO', 'UPDATE', 'WARN'):
            err(f"公告 {aid} 的 level '{level}' 非法（应为 info/update/warn）")
            bad += 1

        # url 必须是合法 http(s) 或是空
        url = a.get('url', '') or ''
        if url and not url.startswith(('http://', 'https://')):
            err(f"公告 {aid} 的 url 非法: {url}")
            bad += 1

    if bad == 0:
        ok(f"{len(data)} 条公告格式正确，id 唯一 ✓")


def main():
    print()
    print("#" * 74)
    print("#  珍珠炮码计算 —— 工程检查")
    print("#" * 74)
    print()
    check_balance()
    check_imports()
    check_optin_placement()
    check_import_paths()
    check_scope_members()
    check_dependencies()
    check_package()
    check_resources()
    check_duplicate_else()
    check_call_signatures()
    check_unused_imports()
    check_announcements()

    print()
    print("=" * 74)
    if issues:
        print(f"✗ 发现 {len(issues)} 个问题")
        print("=" * 74)
        return 1
    print("✓ 全部检查通过")
    print("=" * 74)
    print()
    print("提示：符号级检查请另运行  python3 scripts/check_symbols.py")
    return 0


if __name__ == '__main__':
    sys.exit(main())
