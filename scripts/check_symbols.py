"""
未定义符号检查器。

================================================================================
要解决的问题
================================================================================

"写了调用，但忘了写定义" —— 这是最容易发生、也最容易被漏掉的一类错误。

为什么容易漏：
  · Kotlin 编译到一半才会报 Unresolved reference，而本环境跑不了真编译
  · 括号平衡、import 完整性等检查都**看不出**符号是否存在
  · 在开发过程中，"打算写某个函数"和"已经写了"很容易混淆，
    尤其在多次往返修改之后

历史教训：
  本项目曾出现 ResultCard 使用 `s.model` 参数名、定义处却是 `model` 的
  不一致；也曾出现 MainViewModel 定义了两次 toggleAnnouncements。
  这两类问题都是"符号层面"的，括号检查完全无感。

================================================================================
检查范围
================================================================================

  [A] 跨文件符号：kbs.xxx 包内定义 vs 使用
  [B] 成员调用：obj.method() —— obj 的类型是否真的有这个方法
  [C] 重复定义：同一个类里出现两次同名函数
  [D] 数据类字段：构造参数名 vs 使用处的字段名
  [E] 枚举条目：enum class 里声明的条目 vs 使用处
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'scripts'))

from ktlex import strip_noise  # noqa: E402

SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java')

issues = []


def err(msg, detail=None):
    issues.append(msg)
    print(f"  [错误] {msg}")
    if detail:
        for d in detail:
            print(f"         {d}")


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
# 符号表：收集所有顶层与成员定义
# ============================================================
def collect_definitions():
    """
    返回:
      toplevel: 顶层符号名 -> [(文件, 行号)]
      members:  类名 -> {成员名: 行号}
      enumentries: 枚举名 -> {条目名}
      dataclass_fields: 类名 -> [字段名]
    """
    toplevel = {}
    members = {}
    enum_entries = {}
    dc_fields = {}

    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        lines = code.split('\n')
        r = rel(p)

        # 类层级栈（用于正确归属成员）
        #
        # ★ 不能用单个 cur_class：
        #   CalcViewModel 里嵌套了 `data class UiState(...)`，
        #   单变量会把之后的 fun 全部误归到 UiState 名下，
        #   导致 vm.xxx 的检查形同虚设（历史上真的漏报过）。
        #   改用 (缩进, 类名) 栈，按缩进弹出。
        stack = []  # [(indent, class_name)]

        for i, line in enumerate(lines, 1):
            stripped = line.strip()

            # ★ 空行与纯注释行必须跳过，不能参与缩进判断。
            #   空行的 indent 恒为 0，若参与 `indent <= stack[-1][0]` 判断，
            #   会把整个类栈清空 —— 实测中 CalcViewModel 就是这样被弹出的，
            #   导致它的所有 fun 失去归属，vm.xxx 检查彻底失效。
            if not stripped:
                continue

            indent = len(line) - len(line.lstrip())

            # ---- 缩进回退 → 弹出已结束的类 ----
            #
            # ★ 闭合括号行（`} ` / `) {` / `},`）不参与弹出判断。
            #
            #   典型结构：
            #       enum class YAccumMode(     indent=0  入栈
            #           val label: String,     indent=4
            #       ) {                        indent=0  ← 若参与判断会误弹出！
            #           SUM_THEN_ABS(          indent=4  此时栈已空，条目丢失
            #
            #   实测中 YAccumMode、MotionOrder 的条目就是这样被全部丢掉的。
            #   跳过闭合行后，类会一直留到下一个**同级声明**出现时才弹出。
            if not stripped.startswith(('}', ')', '{', '//', '*', '/*')):
                while stack and indent <= stack[-1][0]:
                    stack.pop()

            # ---- class / object / interface（含嵌套）----
            #
            # ★ 不能要求"无缩进"：嵌套类型（如 InversionResult.Level）
            #   是缩进的。之前漏掉它们，导致嵌套枚举的条目
            #   （GOOD/FAIR/WARN/BAD）被误报为"未定义"。
            m = re.match(
                r'^(?:private\s+|internal\s+|public\s+|sealed\s+|data\s+|enum\s+)*'
                r'(?:class|object|interface)\s+(\w+)', stripped)
            if m:
                name = m.group(1)
                if not line.startswith(' '):
                    toplevel.setdefault(name, []).append((r, i))
                stack.append((indent, name))
                members.setdefault(name, {})
                if re.search(r'\benum\s+class', stripped):
                    st = enum_entries.setdefault(name, set())
                    # ★ 单行的 enum class Level { INFO, UPDATE, WARN }
                    #   这种写法的条目跟 class 在同一行，
                    #   会被上面的 continue 跳过 —— 必须在这里就提取。
                    brace = re.search(r'\{\s*([^}]*)\}', stripped)
                    if brace:
                        for item in brace.group(1).split(','):
                            item = item.strip()
                            if re.fullmatch(r'[A-Z][A-Z0-9_]*', item):
                                st.add(item)
                if re.search(r'\bdata\s+class', stripped):
                    dc_fields.setdefault(name, [])
                continue

            # ---- 顶层函数 ----
            m = re.match(
                r'^(?:private\s+|internal\s+|public\s+|inline\s+|suspend\s+|'
                r'@\w+(?:\([^)]*\))?\s+)*fun\s+(\w+)', stripped)
            if m and not line.startswith(' '):
                toplevel.setdefault(m.group(1), []).append((r, i))
                continue

            # ---- 顶层 val / var 常量 ----
            m = re.match(r'^(?:private\s+|const\s+|internal\s+)?(?:val|var)\s+(\w+)',
                         stripped)
            if m and not line.startswith(' '):
                toplevel.setdefault(m.group(1), []).append((r, i))
                continue

            # ---- 成员函数 / 属性（有缩进）----
            cur_class = stack[-1][1] if stack else None
            if cur_class and line.startswith(' ') and stripped:
                mm = re.match(
                    r'^(?:private\s+|internal\s+|public\s+|override\s+|suspend\s+|'
                    r'inline\s+|@\w+(?:\([^)]*\))?\s+)*fun\s+(\w+)', stripped)
                if mm:
                    members.setdefault(cur_class, {}).setdefault(mm.group(1), i)
                    continue
                mv = re.match(
                    r'^(?:private\s+|internal\s+|public\s+|override\s+|const\s+)?'
                    r'(?:val|var)\s+(\w+)', stripped)
                if mv:
                    members.setdefault(cur_class, {}).setdefault(mv.group(1), i)
                    continue

            # ---- 枚举条目 ----
            # ★ 条目可能是单行（N("00","北"),）也可能是多行：
            #       SUM_THEN_ABS(
            #           "abs(m+n) 原作者",
            #           "…",
            #       ),
            #   因此只匹配开头的 `NAME(` 或 `NAME,`，不要求行尾闭合。
            if cur_class and cur_class in enum_entries:
                me = re.match(r'^([A-Z][A-Z0-9_]*)\s*[\(,;]', stripped)
                if me and not stripped.startswith(('//', '*', '/*')):
                    enum_entries[cur_class].add(me.group(1))

            # ---- data class 构造参数 ----
            if cur_class in dc_fields and not dc_fields[cur_class]:
                # 只在第一个 '(' 之后收集
                pass

        # data class 字段：用括号匹配抓构造参数
        for cname in list(dc_fields.keys()):
            m = re.search(
                r'data\s+class\s+' + re.escape(cname) + r'\s*\(([^)]*)\)',
                code, re.S)
            if m:
                fields = []
                for part in m.group(1).split('\n'):
                    pm = re.match(
                        r'\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:val|var)?\s*(\w+)\s*:',
                        part)
                    if pm and pm.group(1) not in ('val', 'var'):
                        fields.append(pm.group(1))
                dc_fields[cname] = fields

    return toplevel, members, enum_entries, dc_fields


# ============================================================
# [A] 跨文件符号引用
# ============================================================
def build_symbol_packages(toplevel):
    """
    符号名 -> 定义它的**包名集合**。

    ★ 关键：光知道"项目里有没有定义这个符号"是不够的。
      符号在别的包里定义，本文件用之前**必须有 import**，
      否则就是 Unresolved reference。

      本项目真实案例：CalcViewModel（kbs.ui）用了 ScoringConfig
      （kbs.core.model），忘记 import —— 而 ScoringConfig 明明
      在项目里定义了，旧版检查器因此判定"有定义"而放行。
    """
    result = {}
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        pm = re.search(r'^package\s+([\w.]+)', code, re.M)
        pkg = pm.group(1) if pm else ''
        for m in re.finditer(
                r'^(?:private\s+|internal\s+|public\s+|sealed\s+|data\s+|enum\s+)*'
                r'(?:class|object|interface)\s+(\w+)', code, re.M):
            result.setdefault(m.group(1), set()).add(pkg)
        for m in re.finditer(r'^fun\s+(\w+)', code, re.M):
            result.setdefault(m.group(1), set()).add(pkg)
    return result


def check_cross_file(toplevel, members, enum_entries):
    print("=" * 74)
    print("[A] 跨文件符号引用（含包可见性）")
    print("=" * 74)
    print("  ★ 符号在别的包定义时，本文件必须有 import。")
    print("    旧版只判断「项目里存不存在」，漏掉了 import —— 真实踩过。")

    sym_pkgs = build_symbol_packages(toplevel)
    project_syms = set(sym_pkgs.keys())

    all_enum_entries = set()
    for v in enum_entries.values():
        all_enum_entries |= v

    bad = 0
    for p in all_kt():
        raw = open(p, encoding='utf-8').read()
        code = strip_noise(raw)
        r = rel(p)

        # 本文件的包
        pm = re.search(r'^package\s+([\w.]+)', code, re.M)
        this_pkg = pm.group(1) if pm else ''

        # 本文件已 import 的末段名（含 as 别名）
        imported = set()
        for m in re.finditer(r'^import\s+([\w.]+)(?:\s+as\s+(\w+))?',
                             code, re.M):
            if m.group(2):
                imported.add(m.group(2))
            else:
                imported.add(m.group(1).split('.')[-1])

        # 本文件自己定义的符号
        #
        # ★ 必须允许缩进：嵌套类型（如 CalcViewModel 内的
        #   `data class Field(...)`）是有缩进的。
        #   只看行首会把它们漏掉，然后误报"缺少 import"。
        self_defined = set()
        for m in re.finditer(
                r'^[ \t]*(?:private\s+|internal\s+|public\s+|sealed\s+|data\s+|enum\s+)*'
                r'(?:class|object|interface)\s+(\w+)', code, re.M):
            self_defined.add(m.group(1))
        for m in re.finditer(r'^[ \t]*(?:private\s+|internal\s+|public\s+|suspend\s+|inline\s+)*'
                             r'fun\s+(\w+)', code, re.M):
            self_defined.add(m.group(1))

        # 本文件中的局部声明（val/var 后的大写名，避免误报）
        local_decl = set()
        for m in re.finditer(r'\b(?:val|var)\s+([A-Z]\w*)\s*[:=]', code):
            local_decl.add(m.group(1))

        seen = set()
        # 匹配所有大写开头的标识符（不再要求后跟 ( 或 . ，
        # 因为返回类型 `: ScoringConfig` 这种写法同样需要 import）
        for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\b', code):
            name = m.group(1)
            if len(name) <= 1:
                continue
            if name not in project_syms:
                continue
            if name in seen:
                continue
            if name in all_enum_entries:
                continue
            if name in self_defined:
                continue
            if name in imported:
                continue
            if name in local_decl:
                continue
            # 枚举条目名（不带限定符直接使用，如 `SUM_THEN_ABS ->`）
            # 在自身 enum 内是定义；在别处通常通过 `YAccumMode.SUM_THEN_ABS`
            # 限定引用，那条路径被 lookbehind 排除了。这里再兜一层。
            if name.isupper():
                continue

            pkgs = sym_pkgs.get(name, set())
            if this_pkg in pkgs:
                continue  # 同包，无需 import

            # 走到这里：符号只在别的包定义，且本文件没有 import
            seen.add(name)
            line_no = code[:m.start()].count('\n') + 1
            err(f"{r}:{line_no} 使用了 {name}，"
                f"但它定义在 {sorted(pkgs)}，本文件缺少 import",
                [f"应在 import 区加入: import {sorted(pkgs)[0]}.{name}",
                 f"该行: {code.split(chr(10))[line_no - 1].strip()[:70]}"])
            bad += 1

    if bad == 0:
        ok(f"{len(project_syms)} 个跨包符号的引用均有对应 import ✓")


STD_TYPES = {
    # Kotlin / Java
    'String', 'Int', 'Long', 'Double', 'Float', 'Boolean', 'Char', 'Byte',
    'Short', 'List', 'MutableList', 'Set', 'MutableSet', 'Map', 'MutableMap',
    'Pair', 'Triple', 'Any', 'Unit', 'Nothing', 'Array', 'IntArray',
    'DoubleArray', 'FloatArray', 'BooleanArray', 'StringBuilder', 'Exception',
    'RuntimeException', 'IllegalArgumentException', 'IllegalStateException',
    'File', 'Math', 'Locale', 'Thread', 'Runnable', 'System', 'Objects',
    'Comparator', 'Iterable', 'Sequence', 'Regex', 'RegexOption',
    'SimpleDateFormat', 'Date', 'URL', 'JSONObject', 'JSONArray',
    # Android
    'Context', 'Intent', 'Uri', 'Bundle', 'Toast', 'Log', 'Build', 'Activity',
    'Application', 'View', 'ViewGroup', 'LinearLayout', 'ScrollView',
    'TextView', 'Button', 'Toast', 'TypedValue', 'Color', 'Typeface',
    'ClipData', 'ClipboardManager', 'SharedPreferences', 'FileProvider',
    'ComponentActivity', 'SavedStateHandle', 'Resources', 'Parcel',
    # Compose
    'Modifier', 'Color', 'Offset', 'Path', 'Stroke', 'PathEffect', 'Canvas',
    'Dp', 'TextAlign', 'FontFamily', 'ImeAction', 'KeyboardType',
    'RoundedCornerShape', 'Arrangement', 'Alignment', 'TextStyle',
    # 本文件/局部常见名
    'LazyColumn', 'Row', 'Column', 'Box', 'Spacer', 'Text', 'Card', 'Icon',
    'Button', 'OutlinedButton', 'TextButton', 'OutlinedTextField', 'Switch',
    'Scaffold', 'TopAppBar', 'MaterialTheme', 'SegmentedChoice',
}


# ============================================================
# [B] 成员调用：obj.method()
# ============================================================
def check_member_calls(toplevel, members):
    print()
    print("=" * 74)
    print("[B] 成员调用（重点对象：vm / prefs / 已知数据类）")
    print("=" * 74)

    # 变量名 -> 类型名 的粗略映射（从声明处推断）
    var_types = {}
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        for m in re.finditer(r'\bval\s+(\w+)\s*:\s*(\w+)', code):
            var_types.setdefault(m.group(1), set()).add(m.group(2))
        for m in re.finditer(r'\b(\w+)\s*:\s*(\w+)\s*\)', code):
            var_types.setdefault(m.group(1), set()).add(m.group(2))

    # 已知的具体对象名 -> 类型名
    #
    # ★ 'prefs' 不能直接映射成 AppPrefs：
    #   在 AppPrefs.kt 内部，prefs 字段的类型是 SharedPreferences
    #   （`private val prefs = context.applicationContext.getSharedPreferences(...)`），
    #   而在 CalcViewModel 里 prefs 才是 AppPrefs。
    #   一刀切会造成大量误报。
    known_objs = {
        'vm': 'CalcViewModel',
    }

    bad = 0
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')

        for obj_name, cls_name in known_objs.items():
            cls_members = members.get(cls_name)
            if not cls_members:
                continue
            for m in re.finditer(
                    r'(?<![\w.])' + re.escape(obj_name) + r'\.(\w+)', code):
                meth = m.group(1)
                if meth in cls_members:
                    continue
                line_no = code[:m.start()].count('\n') + 1
                err(f"{r}:{line_no} 调用了 {obj_name}.{meth}()，"
                    f"但 {cls_name} 中没有该成员",
                    [f"已定义: {sorted(cls_members.keys())}",
                     f"该行: {lines[line_no - 1].strip()[:70]}"])
                bad += 1

    if bad == 0:
        ok("已知对象的成员调用均有定义 ✓")


# ============================================================
# [C] 重复定义
# ============================================================
def check_duplicates(toplevel, members):
    print()
    print("=" * 74)
    print("[C] 重复定义")
    print("=" * 74)
    print("  历史上出现过 toggleAnnouncements() 定义两次 ——")
    print("  Kotlin 允许重载，但同名同参数会报 'conflicting overloads'")

    bad = 0

    # 同一个类内的成员函数：同名即疑似（可能是合法重载，但值得列出）
    for cls, ms in members.items():
        seen = {}
        for name, line in ms.items():
            seen.setdefault(name, []).append(line)
        for name, lines in seen.items():
            if len(lines) > 1:
                print(f"  [提示] {cls}.{name} 出现 {len(lines)} 次（行 {lines}）"
                      f" —— 若为合法重载可忽略")

    # 顶层重名（不同文件同名但同包 → 冲突）
    for name, locs in sorted(toplevel.items()):
        if len(locs) > 1:
            files = {loc[0] for loc in locs}
            if len(files) > 1:
                err(f"顶层符号 {name} 在多个文件定义: {sorted(files)}",
                    ["同包内顶层重名会导致 'redeclaration' 错误"])
                bad += 1

    if bad == 0:
        ok("无跨文件顶层重名 ✓")


# ============================================================
# [D] data class 字段使用
# ============================================================
def check_data_fields(dc_fields, members):
    print()
    print("=" * 74)
    print("[D] data class 字段使用")
    print("=" * 74)
    print("  ★ 可用成员 = 构造参数 + 类体内的计算属性(val x get()) + 成员函数")
    print("    只统计构造参数会把 `sol.code` 这类 get() 属性误报成未定义。")

    # 合并：构造参数 + 类体成员
    all_fields = {}
    for cls, fields in dc_fields.items():
        s = set(fields)
        s |= set(members.get(cls, {}).keys())
        all_fields[cls] = s

    # 变量名 -> 类型（从 val x: Type 与函数参数推断）
    var_types = {}
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        for m in re.finditer(r'(?:val|var)\s+(\w+)\s*:\s*([A-Z]\w+)', code):
            var_types.setdefault(m.group(1), set()).add(m.group(2))
        for m in re.finditer(r'\b(\w+)\s*:\s*([A-Z]\w+)\s*[,)]', code):
            var_types.setdefault(m.group(1), set()).add(m.group(2))

    # 强绑定：变量名 -> 类型名
    #
    # ★ 不能把 's' 纳入 —— 它在不同文件里是不同类型：
    #     CalcViewModel 里是 UiState，PulseCodec 里是 String。
    #   强行推断会制造大量误报（历史上真的误报过）。
    #   这里只保留**命名唯一、不会歧义**的变量。
    strong = {
        'sol': 'Solution',
        'fav': 'Favorite',
        'ann': 'Announcement',
    }

    bad = 0
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')

        for var_name, forced in strong.items():
            if forced:
                types = {forced}
            else:
                types = var_types.get(var_name, set()) & set(dc_fields.keys())
            for cls in types:
                fields = all_fields.get(cls, set())
                if not fields:
                    continue
                for m in re.finditer(
                        r'(?<![\w.])' + re.escape(var_name) + r'\.(\w+)', code):
                    fld = m.group(1)
                    if fld in fields:
                        continue
                    line_no = code[:m.start()].count('\n') + 1
                    err(f"{r}:{line_no} 访问了 {var_name}.{fld}，"
                        f"但 {cls} 没有该字段",
                        [f"可用字段: {sorted(fields)}",
                         f"该行: {lines[line_no - 1].strip()[:70]}"])
                    bad += 1

    if bad == 0:
        ok("data class 字段访问均有定义 ✓")


# ============================================================
# [E] 枚举条目
# ============================================================
def check_enum_entries(enum_entries):
    print()
    print("=" * 74)
    print("[E] 枚举条目使用")
    print("=" * 74)

    # ★ 同名枚举按**并集**处理。
    #   本项目中 `Level` 出现两次：
    #     Announcement.Level  (INFO/UPDATE/WARN)
    #     InversionResult.Level (GOOD/FAIR/WARN/BAD)
    #   用限定名区分需要解析调用处的作用域，
    #   成本高于收益。取并集不会误报（只会漏报），
    #   而误报曾导致大量无效排查。
    merged = {}
    for ename, entries in enum_entries.items():
        merged.setdefault(ename, set()).update(entries)

    bad = 0
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')
        for ename, entries in merged.items():
            if not entries:
                continue
            for m in re.finditer(
                    r'(?<![\w.])' + re.escape(ename) + r'\.([A-Z][A-Z0-9_]*)',
                    code):
                e = m.group(1)
                if e in entries:
                    continue
                line_no = code[:m.start()].count('\n') + 1
                err(f"{r}:{line_no} 使用了 {ename}.{e}，但各处 {ename} 均无此条目",
                    [f"已声明: {sorted(entries)}"])
                bad += 1

    if bad == 0:
        ok("枚举条目引用均有定义 ✓")


# ============================================================
# [F] runCatching 混合返回类型风险
# ============================================================
def check_runcatching_mixed():
    print()
    print("=" * 74)
    print("[F] runCatching 混合返回类型风险")
    print("=" * 74)
    print("  ★ runCatching 块内若既有 `return@runCatching <值>` 又有其他类型")
    print("    的返回表达式，Kotlin 会推导成公共父类型（常是 Serializable），")
    print("    导致后续解构/调用失败：")
    print("      \"Destructuring declaration initializer of type Serializable")
    print("       must have a 'component1()' function\"")
    print()
    print("    修法：用 error(\"...\") 抛异常，让 runCatching 只有一种返回类型。")
    print("    本项目真实踩过（CalcViewModel.invertSample）。")

    bad = 0
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')
        for i, line in enumerate(lines, 1):
            s = line.strip()
            if 'return@runCatching' not in s:
                continue
            arg = s.split('return@runCatching', 1)[1].strip()
            # 无参数 = 提前返回 Unit，不产生类型混合
            if not arg:
                continue
            err(f"{r}:{i} runCatching 内 `return@runCatching {arg[:30]}` 带返回值",
                ["不同分支返回不同类型会被推导成 Serializable，"
                 "解构时会报 component1() 缺失",
                 "建议改用 error(\"...\") 抛异常，由 runCatching 捕获"])
            bad += 1

    if bad == 0:
        ok("runCatching 返回值类型一致 ✓")


def main():
    print()
    print("#" * 74)
    print("#  未定义符号检查")
    print("#" * 74)
    print()

    toplevel, members, enum_entries, dc_fields = collect_definitions()

    print("  符号表统计")
    print(f"    顶层符号: {len(toplevel)}")
    print(f"    类/对象:  {len(members)}")
    print(f"    枚举:     {len(enum_entries)}")
    print(f"    data类:   {len(dc_fields)}")
    print()

    check_cross_file(toplevel, members, enum_entries)
    check_member_calls(toplevel, members)
    check_duplicates(toplevel, members)
    check_data_fields(dc_fields, members)
    check_enum_entries(enum_entries)
    check_runcatching_mixed()

    print()
    print("=" * 74)
    if issues:
        print(f"✗ 发现 {len(issues)} 个问题")
        print("=" * 74)
        return 1
    print("✓ 未发现未定义符号")
    print("=" * 74)
    return 0


if __name__ == '__main__':
    sys.exit(main())
