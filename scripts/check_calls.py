"""
穷举式调用点核查。

================================================================================
与 check_symbols.py 的区别
================================================================================

check_symbols.py 只检查**硬编码的少数对象**（vm / sol / fav / ann），
覆盖面很窄 —— 一个自定义对象上的方法调用写错了，它完全无感。

本脚本反过来做：**穷举源码中每一个 `标识符(` 调用点**，
逐个判定它属于哪一类，把"既不是标准库、也不是本项目定义、
也不是局部变量"的那些挑出来人工确认。

判定顺序（能归类的就放行，归不了类的才报错）：
  1. Kotlin 标准库函数
  2. Android / Compose API
  3. 本文件内的局部声明（val/lambda 参数/函数参数）
  4. 本文件顶部定义的函数
  5. 本项目其他文件定义的顶层函数
  6. 本文件 import 的符号
  7. 同名函数（在别的类里定义，通过 receiver 调用）

================================================================================
为什么需要"人工确认"这一档
================================================================================

receiver 的类型推断需要真编译器。比如
    prefs.edit().putString(...)
`edit()` 的返回类型是 SharedPreferences.Editor，
静态分析推不出来，只能靠白名单或人工看。
脚本把这类列出，由人判断。
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'scripts'))
from ktlex import strip_noise  # noqa: E402

SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java')


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
# Kotlin 标准库
# ============================================================
KOTLIN_STDLIB = set('''
toString toInt toLong toFloat toDouble toBoolean toChar toByte toShort
toIntOrNull toLongOrNull toFloatOrNull toDoubleOrNull toBooleanOrNull
toList toMutableList toSet toMutableSet toMap toTypedArray toIntArray
toDoubleArray toFloatArray toBooleanArray toCharArray
trim trimStart trimEnd trimIndent trimMargin
split replace replaceFirst replaceRange
startsWith endsWith contains containsAll contentEquals
isEmpty isNotEmpty isBlank isNotBlank isNullOrEmpty isNullOrBlank
lowercase uppercase capitalize decapitalize
padStart padEnd padTo take takeLast takeIf takeUnless takeWhile
drop dropLast dropWhile filter filterNot filterNotNull filterIsInstance
map mapNotNull mapIndexed flatMap flatten zip unzip
forEach forEachIndexed forEachLine onEach
sorted sortedBy sortedDescending sortedByDescending sortedWith
reversed asReversed shuffle shuffled distinct distinctBy
groupBy groupByTo partition chunked windowed associate associateBy
sum sumOf sumBy sumByDouble average count maxOrNull minOrNull
maxOf minOf maxByOrNull minByOrNull firstOrNull lastOrNull
first last single singleOrNull elementAt elementAtOrNull getOrNull
getOrElse getOrDefault component1 component2 component3
indexOf indexOfFirst indexOfLast lastIndexOf find findLast any all none
joinToString joinTo fold foldRight reduce reduceRight scan
plus minus times div rem unaryMinus not inc dec rangeTo
also let apply run with repeat lazy require requireNotNull check checkNotNull
error TODO println print readln readLine format
roundToInt roundToLong roundToDouble abs sign ceil floor sqrt hypot pow
max min coerceIn coerceAtMost coerceAtLeast
listOf mutableListOf setOf mutableSetOf mapOf mutableMapOf
arrayOf intArrayOf doubleArrayOf emptyList emptySet emptyMap
listOfNotNull buildList buildString buildMap
measureTimeMillis
floatArrayOf
set get
copy
String
setIntent addCategory addFlags
Pair Triple Regex RegexOption StringBuilder OptIn

# Android / org.json 常用方法（已逐个人工核对）
put optDouble optString optInt optBoolean optLong optJSONObject optJSONArray
has isNull getJSONArray getJSONObject keys length
addView removeAllViews setContentView findViewById
setPadding setTextSize setTextColor setBackgroundColor setText
setSingleLine setTypeface setGravity setMovementMethod setHorizontallyScrolling
invalidate requestFocus setSelection setOnLongClickListener
'''.split())

# ============================================================
# Android / Compose API（本项目实际用到的）
# ============================================================
ANDROID_COMPOSE = set('''
setText setContent getString getBoolean getInt getLong getFloat
putString putBoolean putInt putLong putFloat putStringSet
getStringSet edit apply commit remove contains
writeText readText exists delete mkdirs mkdir createNewFile
appendLine append clear setLength insert reverse
startActivity finish getExternalFilesDir getSystemService
setFlags addFlags parse getDefault
openConnection setRequestProperty setConnectTimeout setReadTimeout
connect disconnect getInputStream getOutputStream
collectAsStateWithLifecycle viewModel
fillMaxSize fillMaxWidth fillMaxHeight weight wrapContentWidth
wrapContentHeight wrapContentSize padding size height width
background clickable border clip alpha rotate scale offset
verticalScroll horizontalScroll rememberScrollState
animateScrollToItem
launchedEffect remember rememberSaveable mutableStateOf
getValue setValue collectAsState
roundToInt
getSerializableExtra putExtra getBooleanExtra getStringExtra
setOnClickListener addTextChangedListener
round
'''.split())

# Compose 组件名（首字母大写，但用法是函数调用）
COMPOSE_COMPONENTS = set('''
Text Button Icon IconButton OutlinedButton TextButton Card Checkbox Switch
Slider RadioButton LinearProgressIndicator CircularProgressIndicator
Surface Box Row Column Spacer LazyColumn LazyRow
Scaffold TopAppBar BottomAppBar FloatingActionButton Snackbar
OutlinedTextField TextField Divider HorizontalDivider VerticalDivider
DropdownMenu DropdownMenuItem Dialog AlertDialog
MaterialTheme Canvas DrawScope drawLine drawPath drawCircle
drawRect drawRoundRect drawText drawArc drawImage
withTransform scale rotate translate inset clipPath
PathEffect Style Stroke Join Cap
AnnotatedString SpanStyle ParagraphStyle
buildAnnotatedString withStyle append
Color ColorScheme Typography Shapes
dp sp
'''.split())

# 本项目自定义 Composable（按文件分布，下面会动态收集）
# 这里只列跨文件调用的
KNOWN_COMPOSE_FUNCS = set()


def collect_types():
    """收集本项目定义的所有类型名（含嵌套类、enum、object）"""
    types = set()
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        for m in re.finditer(
                r'^[ \t]*(?:private\s+|internal\s+|public\s+|sealed\s+|data\s+|enum\s+)*'
                r'(?:class|object|interface)\s+(\w+)', code, re.M):
            types.add(m.group(1))
    return types


def collect_defined():
    """收集本项目所有定义的函数名"""
    top = set()      # 顶层函数
    members = defaultdict(set)  # 类名 -> 成员集合
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        # 顶层 fun
        # ★ 必须允许 private/internal 等修饰符：
        #   `private fun runSolve(...)` 是顶层函数，
        #   若只匹配 `^fun` 会漏掉，然后被误报成"未定义"。
        for m in re.finditer(
                r'^(?:private\s+|internal\s+|public\s+|inline\s+|suspend\s+)*'
                r'fun\s+(?:<[^>]*>\s+)?(\w+)', code, re.M):
            top.add(m.group(1))
        # 成员 fun（任意缩进）
        for m in re.finditer(
                r'^[ \t]+(?:private\s+|internal\s+|public\s+|override\s+|'
                r'suspend\s+|inline\s+|open\s+|protected\s+|@\w+\s+)*'
                r'fun\s+(?:<[^>]*>\s+)?(\w+)', code, re.M):
            # 归属到最近的上方类声明
            members['__any__'].add(m.group(1))
        # 顶层 val/var（函数式属性）
        for m in re.finditer(r'^val\s+(\w+)', code, re.M):
            top.add(m.group(1))
        for m in re.finditer(r'^[ \t]+(?:private\s+|public\s+|internal\s+|override\s+|const\s+)?'
                             r'(?:val|var)\s+(\w+)', code, re.M):
            members['__any__'].add(m.group(1))
    return top, members['__any__']


def main():
    print("=" * 78)
    print("  穷举式调用点核查")
    print("=" * 78)

    top_defs, member_defs = collect_defined()
    project_types = collect_types()
    all_defs = top_defs | member_defs
    print(f"\n  本项目定义的符号: 顶层 {len(top_defs)}，成员/属性 {len(member_defs)}")

    unresolved = defaultdict(list)

    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')

        # 本文件的 import
        imported = set()
        for m in re.finditer(r'^import\s+([\w.]+)(?:\s+as\s+(\w+))?', code, re.M):
            imported.add(m.group(2) if m.group(2) else m.group(1).split('.')[-1])

        # 本文件的局部声明：
        #   val x = ...   /   fun f(...)   /   lambda { x -> }   /  参数 x: T
        local = set()
        for m in re.finditer(r'\b(?:val|var)\s+(\w+)\s*[=:]', code):
            local.add(m.group(1))
        for m in re.finditer(r'\b(\w+)\s*:\s*[\w<>?.,\s\[\]()]*\s*[,)=]', code):
            local.add(m.group(1))
        for m in re.finditer(r'\{\s*([\w,\s]+)\s*->', code):
            for nm in m.group(1).split(','):
                local.add(nm.strip())
        # data class 构造参数
        for m in re.finditer(r'\b(?:val|var)?\s*(\w+)\s*:\s*[\w.<>?]+,?\s*$',
                             code, re.M):
            local.add(m.group(1))

        for m in re.finditer(r'(?<![\w.])(\w+)\s*\(', code):
            name = m.group(1)
            if not name or name[0].isupper() and name in COMPOSE_COMPONENTS:
                continue

            # 分类放行
            if name in KOTLIN_STDLIB:
                continue
            if name in ANDROID_COMPOSE:
                continue
            if name in COMPOSE_COMPONENTS:
                continue
            if name in all_defs:
                continue
            if name in imported:
                continue
            if name in local:
                continue
            # ★ 枚举条目定义不是函数调用。
            #   `SUM_THEN_ABS(` `LIGHT(` 这类出现在 enum 体内，
            #   是全大写命名，按此特征跳过。
            if name.isupper() and len(name) > 1:
                continue
            # 单字母枚举条目（Direction 的 N/W/E/S）
            if len(name) == 1 and name.isupper():
                continue

            # 注解（@Suppress / @Composable ...）不是函数调用
            if name in ('Suppress', 'Composable', 'OptIn',
                        'Preview', 'Volatile'):
                continue

            # 关键字 / 控制流
            if name in ('if', 'when', 'while', 'for', 'return', 'catch',
                        'do', 'try', 'else', 'fun', 'class', 'object',
                        'interface', 'enum', 'val', 'var', 'const',
                        'when', 'throw', 'break', 'continue', 'this',
                        'super', 'true', 'false', 'null', 'new',
                        'by', 'is', 'in', 'as', 'as?', '!is', '!in'):
                continue
            # 首字母大写 —— 可能是 Compose 组件 / 构造函数。
            #
            # ★ 但不能一律放行：
            #   若一个**Composable 函数被整个删掉**（比如把
            #   ThemeChoiceRow 改名或删除），它就从符号表里消失了，
            #   "在不在项目里定义" 这个判据会直接跳过它 ——
            #   于是"函数缺失"这种最严重的错误反而检测不到。
            #
            #   正确做法：大写名字若满足以下全部条件，就高度可疑：
            #     · 不是 Compose / Android 已知组件
            #     · 没被 import
            #     · 项目里没有任何同名定义（类/对象/函数）
            #     · 不是局部声明
            if name[0].isupper():
                if name in COMPOSE_COMPONENTS:
                    continue
                if name in imported:
                    continue
                if name in all_defs:
                    continue
                if name in local:
                    continue
                if name in project_types:
                    continue

            line_no = code[:m.start()].count('\n') + 1
            unresolved[name].append(
                f"{r}:{line_no}  {lines[line_no - 1].strip()[:72]}")

    print()
    print("=" * 78)
    print("  需要人工确认的调用点")
    print("=" * 78)

    if not unresolved:
        print("\n  ✓ 无 —— 所有调用点都能归类\n")
        return 0

    # 按出现次数排序，高频的优先看
    for name in sorted(unresolved, key=lambda k: -len(unresolved[k])):
        locs = unresolved[name]
        print(f"\n  【{name}】  {len(locs)} 处")
        for loc in locs[:4]:
            print(f"      {loc}")
        if len(locs) > 4:
            print(f"      … 还有 {len(locs) - 4} 处")

    print()
    print("=" * 78)
    print(f"  共 {len(unresolved)} 个待确认符号")
    print("=" * 78)
    return 0


if __name__ == '__main__':
    sys.exit(main())
