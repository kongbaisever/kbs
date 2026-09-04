"""
变量「先声明后使用」检查。

================================================================================
为什么需要
================================================================================

Kotlin 的**局部变量**必须先声明后使用（与类成员不同）。
本项目真实踩过：

    LaunchedEffect(Unit) {
        importDraft = incoming          <- 用在这里（第 120 行）
    }
    ...
    var importDraft by remember { ... }  <- 声明在这里（第 202 行）

CI 报：
    e: CalcScreen.kt:120:13 Unresolved reference: importDraft
    e: CalcScreen.kt:121:13 Unresolved reference: importHint

括号配平、import 齐全、参数名都对 ——
只有"声明顺序"这一项能抓到。

================================================================================
难点：lambda 参数
================================================================================

    val pickFile = rememberLauncherForActivityResult(...) { uri: Uri? ->
        if (uri == null) ...          <- uri 是 lambda 参数，合法
    }

`uri` 出现在 `val pickFile` 的多行语句**内部**，
若把整条语句当成一行，就会误判成"声明前使用"。
必须先把 `{ name: Type ->` 这种 lambda 参数登记进去。
"""
import os
import re
import sys

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


def main():
    print("=" * 78)
    print("  变量「先声明后使用」检查")
    print("=" * 78)
    print("  ★ Kotlin 局部变量必须先声明后使用（类成员不受此限）。")

    bad = 0
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')

        # 收集函数体范围：从 `fun Name(` 到下一个顶层声明
        starts = []
        for i, l in enumerate(lines):
            if re.match(r'^(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|'
                        r'inline\s+|suspend\s+|override\s+|open\s+)*'
                        r'fun\s+\w+\s*\(', l):
                starts.append(i)
        if not starts:
            continue
        # 补一个末尾哨兵
        marks = starts + [len(lines)]
        # 顶层声明（无缩进）作为边界
        for i, l in enumerate(lines):
            if l and not l[0].isspace() and not l.startswith('}') \
                    and re.match(r'^(?:@\w+|fun |private |internal |public |'
                                 r'object |class |data class |const |val |var )', l):
                if i not in marks:
                    marks.append(i)
        marks = sorted(set(marks))

        for bi in range(len(marks) - 1):
            a, b = marks[bi], marks[bi + 1]
            if not any(a == st for st in starts):
                continue
            body = lines[a:b]

            # ★ 局部函数（嵌套 fun）自成作用域：
            #   它的参数和局部变量不被外层的"先声明后使用"约束。
            #   例如 `fun py(y: Double)` 里的 y、
            #        `fun shareFile(code: String)` 里的 code。
            #
            #   判据：找到缩进为 N 的 `fun xxx(`，
            #        一直到缩进同为 N 且内容是 `}` 的那一行，
            #        这整段都排除掉。
            skip = set()
            for idx, l in enumerate(body):
                fm = re.match(r'^([ \t]+)(?:private\s+|inline\s+|suspend\s+)*'
                              r'fun\s+\w+\s*\(', l)
                if not fm:
                    continue
                ind = len(fm.group(1))
                for j in range(idx, len(body)):
                    lj = body[j]
                    if lj.strip() and not lj.strip().startswith('//'):
                        cur = len(lj) - len(lj.lstrip())
                        if j > idx and cur <= ind and lj.strip().startswith('}'):
                            break
                    skip.add(j)
                skip.add(idx)

            decls = {}
            for idx, l in enumerate(body):
                if idx in skip:
                    continue
                # lambda 参数：`{ uri: Uri? ->` 或 `) { uri: Uri? ->`
                for lm in re.finditer(r'\{\s*([\w,\s:?.]+)\s*->', l):
                    for part in lm.group(1).split(','):
                        nm = part.split(':')[0].strip()
                        if re.fullmatch(r'\w+', nm):
                            decls.setdefault(nm, idx)
                # 普通声明
                m = re.match(r'\s*(?:var|val)\s+(\w+)\s+by\s+', l)
                if m:
                    decls.setdefault(m.group(1), idx)
                    continue
                m2 = re.match(r'\s*(?:var|val)\s+(\w+)\s*[=:]', l)
                if m2:
                    decls.setdefault(m2.group(1), idx)
                    continue
                # ★ for 循环变量：`for (i in points.indices)`
                #   它声明在使用**同一行**，必须一起登记，
                #   否则紧随其后的 `points[i]` 会被误判。
                fm2 = re.match(r'\s*for\s*\(\s*(\w+)\s+in\s', l)
                if fm2:
                    decls.setdefault(fm2.group(1), idx)

            for name, didx in sorted(decls.items(), key=lambda x: x[1]):
                for idx in range(didx):
                    if idx in skip:
                        continue
                    l = body[idx]
                    s = l.strip()
                    if not s or s.startswith('*') or s.startswith('//'):
                        continue
                    if re.match(r'\s*(?:var|val)\s+' + re.escape(name) + r'\b', l):
                        continue
                    if not re.search(r'(?<![\w.])' + re.escape(name) + r'\b', l):
                        continue
                    # 该行同时声明了这个名字（lambda 参数）
                    if re.search(r'\{\s*' + re.escape(name) + r'\s*:', l):
                        continue
                    line_no = a + idx + 1
                    print(f"\n  [错误] {r}:{line_no} "
                          f"变量「{name}」在使用之后才声明")
                    print(f"         声明于第 {a + didx + 1} 行")
                    print(f"         该行: {s[:70]}")
                    bad += 1
                    break

    print()
    print("=" * 78)
    if bad:
        print(f"✗ {bad} 处顺序错误")
        print("=" * 78)
        return 1
    print("✓ 所有局部变量均先声明后使用")
    print("=" * 78)
    return 0


if __name__ == '__main__':
    sys.exit(main())
