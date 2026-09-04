# 珍珠炮码计算

Minecraft 末影珍珠炮（TNT 矢量炮）的 Android 解算工具。

输入目标坐标与起爆瞬时状态，输出可直接照做的炮码与 TNT 阵列图。

- **包名**：`kbs.pearl`
- **版本**：4.0.0
- **兼容**：Android 9 – 14（minSdk 28 / targetSdk 34）
- **UI**：Jetpack Compose（Google 开源 UI 工具包）

---

## 快速开始

```bash
sh build.sh            # debug APK
sh build.sh release    # release APK（需先配置签名环境变量）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ 若安装时提示"卸载旧版本"，**请先卸载**。
> applicationId 从单段 `kbs` 改为合法的 `kbs.pearl`，
> 系统会视为不同应用。

---

## 核心架构

```
kbs.core          ← 纯 Kotlin，零 Android 依赖（可 JVM 单测）
├── model/        Vec3 / VersionProfile / CannonSpec / Solution / Trajectory
├── physics/      Integrator（逐 tick 仿真） / PulseModel（脉冲矢量 + 闭式解）
├── codec/        PulseCodec（脉冲 ↔ 炮码 + 阵列图）
└── solver/       PearlSolver（两阶段解算） / SampleInversion（采样点反推）

kbs.data          AppPrefs / Favorites / Announcements
kbs.ui            CalcScreen / CalcViewModel / Components / TrajectoryChart
kbs.util          PasteParser（智能粘贴） / CrashHandler（崩溃捕获）
```

### 两阶段解算

| 阶段 | 做什么 | 为什么 |
|---|---|---|
| **① 闭式解** | 对每个候选飞行 tick，用解析公式直接反解 (m, n) | 极快，毫秒级遍历数百 tick |
| **② 仿真验证** | 把候选交给 `Integrator` 逐 tick 仿真，得到**真实**落点 | 解析公式与游戏结果存在浮点与碰撞终止差异 |

> Minecraft Wiki 明确提示：闭式公式与游戏结果**并非完全等同**。
> 只用闭式解会给出"理论最优但实机打不中"的配置。

**轨迹只在最终 Top N 上补算**（`refine`）—— 搜索阶段不采样，保证速度。

---

## 物理模型

### 版本化运动（1.21.2 是分水岭）

| 版本 | 顺序 |
|---|---|
| **1.21.2+** (ADP) | 加速度 → 阻力 → 位移 |
| **1.11–1.21.1** (Legacy) | 位移 → 阻力 → 加速度 |

两者对应不同的闭式解，**不能通过替换 g/f 相互复用**。

原作者 Python 采用 ADP 顺序，因此默认选 ADP。

### 阻力必须是 float32 精度

```kotlin
val DEFAULT_DRAG: Double = 0.99f.toDouble()
```

Minecraft 内部该值以单精度参与运算。原作者用
`np.float64(np.float32(0.99))` 复现，Kotlin 的 `0.99f.toDouble()` 完全等价。

### Y 动量：两种模式

```kotlin
SUM_THEN_ABS:  abs(m + n)   // 原作者，旧炮已实机验证
ABS_THEN_SUM:  |m| + |n|    // 矢量，符合对称炮体几何
```

真实物理是**逐颗 TNT 的矢量和**：`v = Σ (magnitude_i × 方向_i)`。
`|m|+|n|` 在两侧 TNT 均位于珍珠下方、方向分量同号时成立；
`|m+n|` 会让异号组合相互抵消。两者在 m、n 异号时结果不同。

### 0.602679 不是通用常数

`motionPerPulseXZ / Y` 是**特定炮体在特定版本下的拟合标定值**。

真实单颗 TNT 冲量由爆炸距离、暴露度（exposure）、单位方向向量、
爆炸威力、击退抗性共同决定。换炮必须重新标定。

### 160 是编码上限，不是物理上限

八个权重 `[80,40,20,10,4,3,2,1]` 全亮 = 160。
超出的解无法用炮码表示，**会被静默截断**导致打不中，
因此解算器强制钳制在 ±160。

---

## 功能

| 功能 | 说明 |
|---|---|
| **解算** | 输出多组解，按误差升序，第一条即最优 |
| **飞行剖面图** | 高度—距离曲线，看平射/抛射、峰值、是否撞顶 |
| **炮码阵列图** | 直接显示哪几个权重位要摆，不用数 18 位 0/1 |
| **采样点反推** | 只有飞行途中的调试数据？反推起爆瞬时状态 |
| **智能粘贴** | 整段调试文本粘进来自动识别（支持中文冒号、等号、裸数字） |
| **坐标换算** | 下界 ↔ 主世界 1:8，可一键设为目标 |
| **常用目标** | 收藏后点击即填坐标并解算 |
| **排序偏好** | 精度优先 / 省 TNT / 飞行短 |
| **公告** | 远程 JSON + 多镜像 + 缓存 + 内置兜底，可关闭可恢复 |
| **崩溃日志页** | 纯原生 View，主界面崩了也能看到堆栈 |

---

## 公告系统

远程地址（按顺序尝试）：

```
https://raw.githubusercontent.com/kongbai9288/kbs-/main/announcements.json
https://raw.fastgit.org/...
https://cdn.jsdelivr.net/gh/...
```

更新公告只需改仓库里的 `announcements.json` 并 push，**无需发版**。

格式：

```json
[
  {
    "id": "唯一标识（必须稳定）",
    "title": "标题",
    "content": "正文",
    "url": "https://...（可为空）",
    "level": "info | update | warn"
  }
]
```

> ⚠️ `id` 必须唯一且**稳定**。用户关闭某条公告后，手机里记的就是这个 id；
> 改了 id 会让已关闭的公告重新弹出。

---

## 维护约定

> 这些约定写在文件里，而不是只留在对话记录中 ——
> 避免上下文被截断后规则丢失。

### 版本号

**不因小改动递增。** 修 bug、调 UI、补注释、改脚本都不改版本。

仅在**重大改变**时才递增：

- 物理模型 / 算法变更（影响计算结果）
- 架构重构
- 新增或移除功能模块
- applicationId 变更

当前版本：**4.0.0**

### 改写源码时不能碰 import 行

历史事故：做「全限定名 → 简名」清理时，脚本没跳过 import 行，
把 6 个路径一起截断了：

```kotlin
import androidx.compose.animation.AnimatedVisibility
// 被改成 ↓（非法，直接 Unresolved reference）
import AnimatedVisibility
```

受影响的还有 `expandVertically` / `fadeIn` / `fadeOut` /
`shrinkVertically` / `Switch`。

**括号依然配平**，所以括号检查全绿 —— 只有真编译才暴露。
现在 `check.py [2b]` 专门查这一项。

### 禁止用正则批量改写源码

历史事故：用 `re.sub` 带 `re.S` 改写 `MainScreen.kt`，
`.*?` 跨行贪婪匹配，把 `Row(...)` 之后的整段内容吞进替换组，
导致块调用被改成命名参数 lambda：

```kotlin
Row(...), on = { ... })        // 错误：本应是 Row(...) { ... }
if (s.x.isNotEmpty(), on = {   // 错误：本应是 if (...) {
```

**括号依然配平**，所以括号平衡检查全绿 —— 只有真编译才暴露。

正确做法：一处一处做**精确字符串替换**，替换前后都读取确认。

### 修改代码前必须重新读取

不依赖"记得文件长什么样"，一律以**磁盘上的当前内容**为准。

编辑工具要求 `old_string` 与文件内容**精确匹配且唯一**，
这本身就是防错位的机制：记忆与磁盘不一致时会直接失败，
而不是悄悄写出错代码。

### 每个检查器都要做反向测试

检查器既能**误报**（照着"修复"反而制造 bug），
也能**误放行**（真错误溜过）。两类缺陷都真实发生过，见 README 末尾。

---

## 验证体系

```bash
python3 scripts/check.py          # 工程检查（9 项）
python3 scripts/test_checker.py   # 检查器反向注入测试（7 项）
python3 scripts/verify_core.py    # 与原作者算法逐位比对
python3 scripts/verify_full.py    # 多场景 + 反推 + 编码 + 版本差异
```

### 为什么每个检查器都要做反向测试

本项目迭代中两类缺陷**都真实发生过**：

| 类型 | 实例 | 后果 |
|---|---|---|
| **误报** | 把 `Modifier.weight` 报成"缺少 import" | 照着补 → 命中 internal 声明 → 编译失败 |
| **误放行** | 把 `Context` 放进"无需 import"白名单 | 缺 import 也通过 → CI 报 Unresolved reference |

因此 `test_checker.py` 会**故意注入已知错误**，验证检查器必须报警；
恢复后必须安静。7 项全部通过才算检查器可用。

### 算法一致性

`verify_core.py` 把原作者 Python **逐字符搬运**成黄金基准，
再与新架构比对。当前结果：

```
最优 tick=48  m=121  n=67  方向=S
落点=(1235.062291725014, 171.04834309239936, 4320.883534153753)
误差=1.068657102336829
炮码=0011 0110 11 1100 0001
✓ 完全一致 —— 逐位对齐
```

`verify_full.py` 覆盖 8 个场景（四个象限、近中远距离）、
采样反推自洽性（误差 < 1e-12）、编码往返（0–160 全覆盖无缺口）、
Legacy/ADP 差异（确认未被静默复用同一公式）。

---

## 已知限制

- **沙盒环境无法运行真实 Kotlin 编译器**，`scripts/check.py` 是结构化检查 +
  反向测试，**不等价于真编译**。最终以 CI 的 `assembleDebug` 为准。
- 单颗 TNT 冲量用固定标定值，未实现完整爆炸矢量模型
  （距离 / exposure / 遮挡）。换炮需重新标定。
- 未实现碰撞检测（方块阻挡、珍珠传送）。

---

## 数据来源

- Minecraft Wiki：Entity / Projectile（1.21.2 运动顺序变更）
- Minecraft Wiki：Explosion（爆炸对实体的速度影响）
- Minecraft Wiki：Tutorial 360 degree ender pearl cannon
- [PearlCalculatorCore](https://github.com/LegendsOfSky/PearlCalculatorCore)
- [PearlCalculatorBlazor](https://github.com/whats2000/PearlCalculatorBlazor)
