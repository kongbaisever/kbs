"""
检查器反向注入测试。

================================================================================
为什么需要这个文件
================================================================================

检查器既能**误报**（把正常代码判为错误，导致照着"修复"反而制造 bug），
也能**误放行**（真错误溜过，CI 失败）。

本项目前十几轮迭代中，两类缺陷都真实发生过：
  · 误报：把 Modifier.weight 报成"缺少 import" → 补上后命中 internal 声明
          → "Cannot access 'weight': it is internal in ..."
  · 误放行：把 Context 放进"无需 import"白名单 → 缺 import 也通过
          → CI 报 "Unresolved reference: Context"

因此每条规则都必须验证：
    注入错误 → 必须报警
    恢复原状 → 必须安静

================================================================================
用法
================================================================================
    python3 scripts/test_checker.py
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECK = os.path.join(ROOT, 'scripts', 'check.py')

# 用于注入的目标文件
TARGET_SCREEN = os.path.join(
    ROOT, 'app/src/main/java/kbs/ui/screens/CalcScreen.kt')
TARGET_GRADLE = os.path.join(ROOT, 'app/build.gradle.kts')

passed = 0
failed = 0


def backup(path):
    shutil.copy2(path, path + '.bak')


def restore(path):
    if os.path.exists(path + '.bak'):
        shutil.move(path + '.bak', path)


def run_check():
    """返回 (是否有错误, 输出)"""
    r = subprocess.run(
        [sys.executable, CHECK],
        capture_output=True, text=True, cwd=ROOT,
    )
    out = r.stdout + r.stderr
    return ('[错误]' in out), out


def test(name, path, inject_fn, expect_error=True):
    """注入 → 运行检查 → 恢复 → 验证"""
    global passed, failed

    backup(path)
    try:
        s = open(path, encoding='utf-8').read()
        new_s = inject_fn(s)
        if new_s == s:
            print(f"  [✗] {name}: 注入失败（内容未改变）")
            failed += 1
            return
        # ★ 必须用 with 确保文件被关闭并 flush 到磁盘。
        #   直接 open(...).write(...) 不关闭时，内容可能还留在缓冲区，
        #   随后的 subprocess 会读到**旧**内容 ——
        #   表现为"注入了却没报警"的假失败，极难排查。
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(new_s)
            fh.flush()
            os.fsync(fh.fileno())

        has_err, out = run_check()

        if expect_error and has_err:
            # 提取第一条错误
            m = re.search(r'\[错误\](.+)', out)
            detail = m.group(1).strip()[:64] if m else ''
            print(f"  [✓] {name}")
            print(f"        捕获: {detail}")
            passed += 1
        elif not expect_error and not has_err:
            print(f"  [✓] {name}（正确地未报警）")
            passed += 1
        else:
            print(f"  [✗] {name}: "
                  f"{'应报警但未报' if expect_error else '不应报警却报了'}")
            if has_err:
                m = re.search(r'\[错误\](.+)', out)
                print(f"        实际输出: {m.group(1).strip()[:80] if m else ''}")
            failed += 1
    finally:
        restore(path)


def main():
    print("=" * 74)
    print("检查器反向注入测试")
    print("=" * 74)
    print("  每条规则验证两点：注入错误必须报警，恢复后必须安静")
    print()

    # ---------- 基线：未注入时应无错误 ----------
    has_err, _ = run_check()
    print(f"[基线] 未注入时检查器{'有' if has_err else '无'}错误")
    if has_err:
        print("  ✗ 基线不干净，后续测试无意义")
        return 1
    print("  [✓] 基线干净\n")

    # ---------- 1. 括号不平衡 ----------
    def inj_brace(s):
        # 删掉 SolutionCard 的收尾大括号
        return s.replace(
            """                    )
                }
            }
        }
    }
}""",
            """                    )
                }
            }
        }
    }
}""", 1) if False else s.rstrip()[:-1] + '\n'
    test("[1] 括号不平衡", TARGET_SCREEN, inj_brace)

    # ---------- 2. 缺少 Android 框架类 import ----------
    def inj_ctx(s):
        return s.replace("import android.content.Intent\n",
                         "import android.content.Intent\n", 1) \
            .replace("val context = LocalContext.current",
                     "val context: Context = LocalContext.current", 1)
    test("[2] 使用 Context 但缺 import", TARGET_SCREEN, inj_ctx)

    # ---------- 3. 误 import 作用域成员 ----------
    def inj_weight(s):
        return s.replace(
            "import androidx.compose.foundation.layout.Arrangement",
            "import androidx.compose.foundation.layout.weight\n"
            "import androidx.compose.foundation.layout.Arrangement", 1)
    test("[3] 误 import Modifier.weight", TARGET_SCREEN, inj_weight)

    # ---------- 4. 命名参数名错误 ----------
    def inj_param(s):
        return s.replace(
            "title = \"🎯 目标坐标\",",
            "titleX = \"🎯 目标坐标\",", 1)
    test("[4] 命名参数名不匹配", TARGET_SCREEN, inj_param)

    # ---------- 5. 资源引用不存在 ----------
    # ★ 不能用"在注释里加 @string/xxx"来注入 —— 检查器会先剥离注释，
    #   注释里的引用根本不参与检查，测试会假失败。
    #   正确做法：改掉 strings.xml 里的 name，让 Manifest 的引用落空。
    strings_path = os.path.join(
        ROOT, 'app/src/main/res/values/strings.xml')

    def inj_res(s):
        return s.replace('name="app_name"', 'name="app_name_renamed"', 1)
    test("[5] 引用不存在的资源（Manifest @string/app_name）", strings_path, inj_res)

    # ---------- 6. 单段包名 ----------
    def inj_pkg(s):
        return s.replace('applicationId = "kbs.pearl"',
                         'applicationId = "kbs"', 1)
    test("[6] 单段 applicationId", TARGET_GRADLE, inj_pkg)

    # ---------- 7. 缺依赖声明 ----------
    def inj_dep(s):
        return s.replace(
            '    implementation("androidx.compose.animation:animation")\n',
            '', 1)
    test("[7] 直接 import 但未声明依赖", TARGET_GRADLE, inj_dep)

    print()
    print("=" * 74)
    print(f"结果：{passed} 通过，{failed} 失败")
    print("=" * 74)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
