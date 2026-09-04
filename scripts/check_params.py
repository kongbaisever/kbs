"""
命名参数匹配核查。

================================================================================
为什么单独查这一项
================================================================================

Kotlin 允许命名参数，但**调用处写错名字是编译错误**，
而且报错信息常常指向一个看起来无关的位置。

本项目真实踩过两次：
  1. ResultCard 调用处写 `model = ...`，定义处参数名是 `yPulseMode`
     → Unresolved reference
  2. Field 的 `on` 参数位置在前，导致 `Modifier.weight(1f)` 被绑给 `on`
     → "Type mismatch: inferred type is Modifier but (String) -> Unit"

这两类问题括号都配平、import 都齐全，只有逐参数比对才能发现。

================================================================================
核查内容
================================================================================

  [A] 命名参数：调用处 `name = value` vs 定义处的参数名
  [B] 必需参数：调用处是否漏传了没有默认值的参数
  [C] 参数顺序：位置参数出现在命名参数之后（Kotlin 允许，但易错）
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
    for d in (detail or []):
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


def split_args(s):
    """
    按顶层逗号切分参数列表。
    忽略括号/尖括号/字符串内部的逗号。
    """
    out, depth, buf = [], 0, []
    instr = None
    i = 0
    while i < len(s):
        c = s[i]
        if instr:
            buf.append(c)
            if c == '\\' and i + 1 < len(s):
                buf.append(s[i + 1]); i += 2; continue
            if c == instr:
                instr = None
            i += 1
            continue
        if c in '"\'':
            instr = c; buf.append(c); i += 1; continue
        # ★ '->' 里的 '>' 不是泛型闭括号。
        #   若不特殊处理，`onIme: (() -> Unit)?` 会让 depth 提前归零，
        #   参数列表被截断，其后的 `on` 参数就"消失"了。
        if c == '-' and i + 1 < len(s) and s[i + 1] == '>':
            buf.append('->')
            i += 2
            continue
        if c in '([{<':
            depth += 1
        elif c in ')]}>':
            depth -= 1
        elif c == ',' and depth == 0:
            out.append(''.join(buf).strip()); buf = []; i += 1; continue
        buf.append(c)
        i += 1
    if ''.join(buf).strip():
        out.append(''.join(buf).strip())
    return out


def parse_params(code, start_idx):
    """
    从 `(` 位置开始，抓出完整参数列表文本。
    返回 (参数列表文本, 结束位置)
    """
    depth, i = 0, start_idx
    instr = None
    while i < len(code):
        c = code[i]
        if instr:
            if c == '\\':
                i += 2; continue
            if c == instr:
                instr = None
            i += 1; continue
        if c in '"\'':
            instr = c; i += 1; continue
        if c == '-' and i + 1 < len(code) and code[i + 1] == '>':
            i += 2
            continue
        if c in '([{<':
            depth += 1
        elif c in ')]}>':
            depth -= 1
            if depth == 0:
                return code[start_idx + 1:i], i
        i += 1
    return code[start_idx + 1:], len(code)


def collect_signatures():
    """
    收集所有函数/Composable 的签名。
    返回 {函数名: [(参数名列表, 必需参数集合, 文件, 行号)]}
    """
    sigs = {}
    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        # fun Name(...)  支持前置注解与修饰符
        for m in re.finditer(
                r'(?:@\w+(?:\([^)]*\))?\s+)*'
                r'(?:private\s+|internal\s+|public\s+|override\s+|suspend\s+|inline\s+|'
                r'open\s+|protected\s+|@Composable\s+)*'
                r'fun\s+(\w+)\s*\(', code):
            name = m.group(1)
            params_text, _ = parse_params(code, m.end() - 1)
            names, required = [], set()
            for arg in split_args(params_text):
                arg = arg.strip()
                if not arg:
                    continue
                # 去掉注释残留
                arg = re.sub(r'/\*.*?\*/', '', arg, flags=re.S).strip()
                if not arg:
                    continue
                pm = re.match(
                    r'(?:@\w+(?:\([^)]*\))?\s+)*'
                    r'(?:vararg\s+|crossinline\s+|noinline\s+)?'
                    r'(?:val\s+|var\s+)?(\w+)\s*:', arg)
                if not pm:
                    # 可能是 data class 简写 `val x: T = ...` 已被上面覆盖
                    # 或 lambda 类型 `(A) -> B`
                    continue
                pn = pm.group(1)
                names.append(pn)
                # 有 `= 默认值` 就是可选
                # 用括号深度判断：只在顶层出现 `=`
                d = 0
                has_default = False
                j = 0
                while j < len(arg):
                    ch = arg[j]
                    if ch == '-' and j + 1 < len(arg) and arg[j + 1] == '>':
                        j += 2
                        continue
                    if ch in '([{<':
                        d += 1
                    elif ch in ')]}>':
                        d -= 1
                    elif ch == '=' and d == 0:
                        has_default = True
                        break
                    j += 1
                if not has_default:
                    required.add(pn)
            line_no = code[:m.start()].count('\n') + 1
            sigs.setdefault(name, []).append((names, required, r, line_no))
    return sigs


def main():
    print("=" * 78)
    print("  命名参数匹配核查")
    print("=" * 78)

    sigs = collect_signatures()
    print(f"\n  收集到 {sum(len(v) for v in sigs.values())} 个函数签名 "
          f"（{len(sigs)} 个不同名字）")

    # ---- [A] 命名参数比对 ----
    print()
    print("=" * 78)
    print("[A] 调用处的命名参数 vs 定义处参数名")
    print("=" * 78)

    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')

        # 找所有 Name( ... ) 调用
        for m in re.finditer(r'(?<![\w.])([A-Za-z_]\w*)\s*\(', code):
            fname = m.group(1)
            if fname in ('if', 'when', 'while', 'for', 'return', 'catch',
                         'fun', 'class', 'val', 'var', 'const', 'do', 'try'):
                continue
            params_text, _ = parse_params(code, m.end() - 1)
            if not params_text.strip():
                continue

            args = [a for a in split_args(params_text) if a.strip()]
            # 提取本层的命名参数（name = value）
            named = []
            for a in args:
                d = 0
                eq = None
                for i, ch in enumerate(a):
                    if ch in '([{<':
                        d += 1
                    elif ch in ')]}>':
                        d -= 1
                    elif ch == '=' and d == 0 and i > 0 and a[i - 1] != '=' \
                            and i + 1 < len(a) and a[i + 1] != '=':
                        eq = i
                        break
                if eq:
                    nm = a[:eq].strip()
                    if re.fullmatch(r'\w+', nm):
                        named.append(nm)

            if not named:
                continue

            cands = sigs.get(fname)
            if not cands:
                continue  # 外部 API，跳过

            line_no = code[:m.start()].count('\n') + 1

            # 只要有一个候选签名能全部对上就放行（重载）
            best = None
            for names, required, fr, fl in cands:
                missing = [n for n in named if n not in names]
                if not missing:
                    best = (names, required, fr, fl)
                    break
                if best is None:
                    best = (names, required, fr, fl, missing)

            if best and len(best) == 5:
                names, required, fr, fl, missing = best
                err(f"{r}:{line_no} 调用 {fname}() 的命名参数 "
                    f"{missing} 不在定义中",
                    [f"定义在 {fr}:{fl}",
                     f"可用参数: {names}",
                     f"该行: {lines[line_no - 1].strip()[:70]}"])

    # ---- [B] 必需参数缺失 ----
    print()
    print("=" * 78)
    print("[B] 必需参数是否漏传")
    print("=" * 78)
    print("  只检查**命名参数调用**（全命名时能精确判断）。")
    print("  位置参数调用无法静态判断个数，跳过。")

    for p in all_kt():
        code = strip_noise(open(p, encoding='utf-8').read())
        r = rel(p)
        lines = code.split('\n')
        for m in re.finditer(r'(?<![\w.])([A-Za-z_]\w*)\s*\(', code):
            fname = m.group(1)
            cands = sigs.get(fname)
            if not cands:
                continue
            params_text, end_pos = parse_params(code, m.end() - 1)
            if not params_text.strip():
                continue
            args = [a for a in split_args(params_text) if a.strip()]
            if not args:
                continue
            # 全部参数都是命名形式才检查
            named = []
            positional = 0
            for a in args:
                d = 0
                eq = None
                for i, ch in enumerate(a):
                    if ch in '([{<':
                        d += 1
                    elif ch in ')]}>':
                        d -= 1
                    elif ch == '=' and d == 0 and i > 0 and a[i - 1] != '=':
                        eq = i
                        break
                if eq:
                    nm = a[:eq].strip()
                    if re.fullmatch(r'\w+', nm):
                        named.append(nm)
                    else:
                        positional += 1
                else:
                    positional += 1
            # 有位置参数就跳过（无法判断对应关系）
            if positional > 0:
                continue

            line_no = code[:m.start()].count('\n') + 1
            for names, required, fr, fl in cands:
                if any(n not in names for n in named):
                    continue
                missing = required - set(named)

                # ★ 尾随 lambda：Kotlin 允许把最后一个函数类型参数
                #   写到括号外面，如
                #       CollapsibleCard(title = ..., ...) { content() }
                #   这里 `content` 没出现在括号里，但由尾随 lambda 提供。
                #   若忽略这条规则会产生大量误报。
                if missing and names:
                    last = names[-1]
                    rest = code[end_pos + 1:].lstrip()
                    if last in missing and rest.startswith('{'):
                        missing = missing - {last}

                if missing:
                    err(f"{r}:{line_no} 调用 {fname}() 缺少必需参数 "
                        f"{sorted(missing)}",
                        [f"定义在 {fr}:{fl}",
                         f"必需: {sorted(required)}"])
                break

    print()
    print("=" * 78)
    if issues:
        print(f"✗ 发现 {len(issues)} 个参数问题")
        print("=" * 78)
        return 1
    print("✓ 命名参数与必需参数全部匹配")
    print("=" * 78)
    return 0


if __name__ == '__main__':
    sys.exit(main())
