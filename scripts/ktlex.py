"""
Kotlin 源码词法剥离 —— 移除注释与字符串字面量内容，保留行号。

★ 为什么需要独立模块：

之前每个检查脚本各自实现 strip_noise()，顺序都是：
    1) 去块注释  /* ... */
    2) 去行注释  // ...
    3) 去字符串  "..."
这个顺序是**错的**，会造成静默的内容吞噬：

    val url = "https://github.com/kongbai/kbs-"   ← 字符串里的 //

第 2 步的 `//.*$` 会把 `//github.com/kongbai/kbs-"` 整个删掉，
留下 `val url = "https:` —— **引号不再配对**。
于是第 3 步的字符串正则会跨越后续多行去配对下一个引号，
把中间的正常代码（如 `content = `）一起吞进"字符串"里。

后果：
  - 被吞掉区域里的真实代码不再参与检查（漏报）
  - 行号虽保留但内容错位，报错定位失准
  - symbol_audit 把字符串残留词当成未定义符号（误报）

正确做法：**单次扫描的状态机**，遍历时同时识别
字符串 / 字符字面量 / 行注释 / 块注释，互不干扰。
"""


def strip_noise(text):
    """
    剥离 Kotlin 源码中的注释与字符串内容。

    返回的字符串：
      - 行数与原文**完全一致**（用等量换行替换被移除的片段）
      - 注释位置变为空白
      - 字符串字面量内容被清空，只保留一对引号占位

    这样后续的所有行号定位都是准确的。
    """
    out = []
    i = 0
    n = len(text)
    # 状态：'code' | 'line_comment' | 'block_comment' | 'string' | 'char'
    state = 'code'
    block_depth = 0

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ''

        if state == 'code':
            # 进入行注释（字符串外的 //）
            if ch == '/' and nxt == '/':
                state = 'line_comment'
                out.append('  ')
                i += 2
                continue
            # 进入块注释
            if ch == '/' and nxt == '*':
                state = 'block_comment'
                block_depth = 1
                out.append('  ')
                i += 2
                continue
            # 进入字符串
            if ch == '"':
                state = 'string'
                out.append('"')
                i += 1
                continue
            # 进入字符字面量
            if ch == "'":
                state = 'char'
                out.append("'")
                i += 1
                continue
            out.append(ch)
            i += 1
            continue

        if state == 'line_comment':
            # 到行尾结束（保留换行）
            if ch == '\n':
                state = 'code'
                out.append('\n')
            else:
                out.append(' ')
            i += 1
            continue

        if state == 'block_comment':
            # 支持 Kotlin 的嵌套块注释
            if ch == '/' and nxt == '*':
                block_depth += 1
                out.append('  ')
                i += 2
                continue
            if ch == '*' and nxt == '/':
                block_depth -= 1
                out.append('  ')
                i += 2
                if block_depth <= 0:
                    state = 'code'
                continue
            out.append('\n' if ch == '\n' else ' ')
            i += 1
            continue

        if state == 'string':
            if ch == '\\':
                # 转义序列：原样占位两个字符
                out.append('  ')
                i += 2
                continue
            if ch == '"':
                state = 'code'
                out.append('"')
                i += 1
                continue
            # 字符串内容清空，换行保留
            out.append('\n' if ch == '\n' else ' ')
            i += 1
            continue

        if state == 'char':
            if ch == '\\':
                out.append('  ')
                i += 2
                continue
            if ch == "'":
                state = 'code'
                out.append("'")
                i += 1
                continue
            out.append(' ' if ch != '\n' else '\n')
            i += 1
            continue

    return ''.join(out)


def strip_comments_only(text):
    """
    只剥离注释，保留字符串内容。
    用于括号平衡检查 —— 字符串模板 ${...} 自身是配对的，无需剥离。
    """
    out = []
    i = 0
    n = len(text)
    state = 'code'
    block_depth = 0

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ''

        if state == 'code':
            if ch == '/' and nxt == '/':
                state = 'line_comment'
                out.append('  ')
                i += 2
                continue
            if ch == '/' and nxt == '*':
                state = 'block_comment'
                block_depth = 1
                out.append('  ')
                i += 2
                continue
            if ch == '"':
                state = 'string'
                out.append(ch)
                i += 1
                continue
            if ch == "'":
                state = 'char'
                out.append(ch)
                i += 1
                continue
            out.append(ch)
            i += 1
            continue

        if state == 'line_comment':
            if ch == '\n':
                state = 'code'
                out.append('\n')
            else:
                out.append(' ')
            i += 1
            continue

        if state == 'block_comment':
            if ch == '/' and nxt == '*':
                block_depth += 1
                out.append('  ')
                i += 2
                continue
            if ch == '*' and nxt == '/':
                block_depth -= 1
                out.append('  ')
                i += 2
                if block_depth <= 0:
                    state = 'code'
                continue
            out.append('\n' if ch == '\n' else ' ')
            i += 1
            continue

        if state in ('string', 'char'):
            quote = '"' if state == 'string' else "'"
            if ch == '\\':
                out.append(ch + (text[i + 1] if i + 1 < n else ''))
                i += 2
                continue
            out.append(ch)
            if ch == quote:
                state = 'code'
            i += 1
            continue

    return ''.join(out)


def split_args(src):
    """
    按顶层逗号分割参数列表。

    ★ lambda 的 `->` 里的 `>` 不能当泛型括号计数！
      `onToggleChart: () -> Unit` 中：
        `(` → +1, `)` → -1, 然后 `->` 的 `>` 若也 -1 会让计数变成 **-1**，
        此后所有逗号都因 d != 0 而不再分割
        → ResultCard 的 11 个参数只解析出 5 个，检查形同虚设。

      解法：分割前先保护 `->` 与裸比较符 `>`，只保留真正的泛型 `<...>`。
    """
    guarded = src.replace('->', '--')
    guarded = re.sub(r'(?<![=\w<>])\s*>\s*(?![>\w])', ' ~ ', guarded)

    parts = []
    depth = 0
    cur = ''
    for ch in guarded:
        if ch in '([{<':
            depth += 1
        elif ch in ')]}>':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return parts


import re  # noqa: E402  （split_args 用到，放在末尾避免影响上方逻辑阅读）
