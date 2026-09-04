"""
颜色对比度检查（WCAG 2.1）。

================================================================================
为什么要查这个
================================================================================

用户反馈："浅色模式，有时候看不到字"。

根因是**硬编码颜色**：深底上醒目的亮青绿 #12B39B，
放到浅底（#FAF8FD）上对比度只有约 2.3:1，远低于 WCAG 要求的 4.5:1，
于是文字"消失"了。

这类问题**肉眼看代码发现不了** —— 两个颜色单看都挺正常，
只有算出对比度才知道够不够。

================================================================================
判据
================================================================================

  WCAG AA 正文文字        ≥ 4.5 : 1
  WCAG AA 大字号(≥18pt)   ≥ 3.0 : 1
  WCAG AA 非文字(图表线)  ≥ 3.0 : 1

本 App 的图表线、公告标题、参数标签都属于正文级别，统一按 4.5 要求；
纯装饰性的分隔线按 3.0。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THEME = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'kbs', 'ui',
                     'theme', 'Theme.kt')


def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_str):
    h = hex_str.strip()
    # 兼容三种写法：#RRGGBB / 0xAARRGGBB / RRGGBB
    if h.startswith('0x') or h.startswith('0X'):
        h = h[2:]
    h = h.lstrip('#')
    if len(h) == 8:          # AARRGGBB，去掉 Alpha
        h = h[2:]
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * srgb_to_lin(r) + 0.7152 * srgb_to_lin(g) + \
        0.0722 * srgb_to_lin(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def main():
    print("=" * 78)
    print("  颜色对比度检查（WCAG 2.1）")
    print("=" * 78)

    if not os.path.exists(THEME):
        print(f"  [错误] 找不到 {THEME}")
        return 1

    src = open(THEME, encoding='utf-8').read()

    # 从 Theme.kt 里抓出所有 `名字 = Color(0xFFxxxxxx)`
    colors = {}
    for m in re.finditer(r'(\w+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)', src):
        colors[m.group(1)] = m.group(2)

    # 变量别名，如 `val SurfaceCard = Color(...)` 之后又有
    # `val X = SurfaceCard`。不解析别名会让"通过变量引用的颜色"
    # 悄悄逃过检查 —— 深色图表的 target 就是这样漏掉的。
    for m in re.finditer(r'val (\w+)\s*=\s*(\w+)\s*$', src, re.M):
        if m.group(2) in colors and m.group(1) not in colors:
            colors[m.group(1)] = colors[m.group(2)]

    print(f"\n  从 Theme.kt 解析到 {len(colors)} 个颜色定义")

    # ---- 需要校验的配对 ----
    # 格式：(前景, 背景, 说明, 最低要求)
    pairs = [
        # ===== 深色主题 =====
        ('PearlTeal', 'SurfaceDark', '深色：强调文字 / 深底', 4.5),
        ('PearlTeal', 'SurfaceCard', '深色：强调文字 / 卡片', 4.5),
        ('TextPrimary', 'SurfaceDark', '深色：正文 / 深底', 4.5),
        ('TextSecondary', 'SurfaceDark', '深色：次要文字 / 深底', 4.5),
        ('TextSecondary', 'SurfaceCard', '深色：次要文字 / 卡片', 4.5),
        # ===== 浅色主题 =====
        ('PearlTealDarkOnLight', 'SurfaceLight', '浅色：强调文字 / 浅底', 4.5),
        ('PearlTealDarkOnLight', 'SurfaceCardLight',
         '浅色：强调文字 / 卡片', 4.5),
        ('TextPrimaryLight', 'SurfaceLight', '浅色：正文 / 浅底', 4.5),
        ('TextSecondaryLight', 'SurfaceLight', '浅色：次要文字 / 浅底', 4.5),
        ('TextSecondaryLight', 'SurfaceCardLight',
         '浅色：次要文字 / 卡片', 4.5),
    ]

    # 图表色从 ChartColors 里抓（形如 guide = Color(0xFF...)）
    def chart(name, dark=True):
        # 取 DarkChartColors / LightChartColors 块内的同名键
        block = 'DarkChartColors' if dark else 'LightChartColors'
        # ★ 定义形如 `private val DarkChartColors = ChartColors(...)`
        #   名字与 `(` 之间隔着 `= ChartColors`，
        #   直接写 `DarkChartColors\(` 会匹配不到。
        m = re.search(block + r'\s*=\s*ChartColors\((.*?)\n\)', src, re.S)
        if not m:
            return None
        mm = re.search(r'\b' + name + r'\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)',
                       m.group(1))
        if mm:
            return mm.group(1)
        # 变量引用，如 `target = NetherPurpleLight`
        # ★ 行尾可能有逗号：`target = NetherPurpleLight,`
        #   不加 `,?` 就匹配不上，深色图表的 target 会静默逃过检查。
        mv = re.search(r'\b' + name + r'\s*=\s*(\w+)\s*,?\s*$',
                       m.group(1), re.M)
        if mv:
            return colors.get(mv.group(1))
        return None

    for nm in ('guide', 'ground', 'peak', 'target'):
        for dark, bgname in ((True, 'SurfaceCard'), (False, 'SurfaceCardLight')):
            c = chart(nm, dark)
            if c:
                label = '深色' if dark else '浅色'
                pairs.append((f'__chart_{nm}_{"d" if dark else "l"}',
                              bgname, f'{label}：图表 {nm} / 卡片底', 3.0))
                colors[f'__chart_{nm}_{"d" if dark else "l"}'] = c

    print()
    print("=" * 78)
    print("  校验结果")
    print("=" * 78)
    print(f"\n  {'配对':<40} {'对比度':>8}  {'要求':>6}  判定")
    print("  " + "-" * 70)

    fails = []
    for fg, bg, desc, need in pairs:
        if fg not in colors or bg not in colors:
            print(f"  {desc:<40} {'—':>8}  {need:>6}  ⚠ 未找到定义")
            continue
        r = contrast(colors[fg], colors[bg])
        passed = r >= need
        mark = "✓" if passed else "✗"
        print(f"  {desc:<40} {r:>7.2f}:1  {need:>5}:1  {mark}")
        if not passed:
            fails.append((desc, r, need))

    print()
    print("=" * 78)
    if fails:
        print(f"✗ {len(fails)} 组对比度不足")
        print("=" * 78)
        for desc, r, need in fails:
            print(f"  · {desc}：{r:.2f}:1 < {need}:1")
        return 1

    print("✓ 所有关键配色均达到 WCAG AA")
    print("=" * 78)
    return 0


if __name__ == '__main__':
    sys.exit(main())
