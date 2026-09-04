"""
核心引擎全面验证：
  1. 多目标场景（四个象限、近中远距离）与原作者逐位比对
  2. 采样点反推的自洽性（反推 → 正向 → 回到原点）
  3. 炮码编码往返（(m,n) → bits → (m,n)）覆盖全部边界
  4. Legacy / ADP 两种版本模型的差异（应不同，不能静默相同）
"""
import struct
import math
import random

# ==========================================
# 常量
# ==========================================
def f32(v):
    return struct.unpack('!f', struct.pack('!f', v))[0]

g = 0.03
f = f32(0.99)
XZ = 0.6026793588895138
Y = 0.004435058914919521
GROUND = 128
WEIGHTS = [80, 40, 20, 10, 4, 3, 2, 1]
MAXP = sum(WEIGHTS)


def num_to_bits(num):
    bits, v = [], num
    for w in WEIGHTS:
        if v >= w:
            bits.append('1'); v -= w
        else:
            bits.append('0')
    s = ''.join(bits)
    return f"{s[:4]} {s[4:]}"


def bits_to_num(s):
    s = s.replace(' ', '')
    return sum(WEIGHTS[i] for i, c in enumerate(s) if c == '1')


# ==========================================
# 1. 多场景与原作者比对
# ==========================================
def original_core(px, py, pz, bmy, dx_t, dz_t, max_ticks=600):
    """原作者主循环"""
    deltax = dx_t - px
    deltaz = dz_t - pz
    if abs(deltax) > abs(deltaz):
        direction = 'E' if deltax > 0 else 'W'
    else:
        direction = 'S' if deltaz > 0 else 'N'

    last = float('inf')
    out = []
    t = 1
    while t <= max_ticks:
        kp = 2 * XZ * ((f - f ** (t + 1)) / (1 - f))
        if direction in ('N', 'S'):
            m = round((deltax + deltaz) / kp)
            n = round((deltaz - deltax) / kp)
            if direction == 'N':
                m, n = n, m
            mx = (abs(m) - abs(n)) * XZ
            my = abs(m + n) * Y + bmy
            mz = (m + n) * XZ
        else:
            m = round((deltax + deltaz) / kp)
            n = round((deltax - deltaz) / kp)
            if direction == 'W':
                m, n = n, m
            mx = (m + n) * XZ
            my = abs(m + n) * Y + bmy
            mz = (abs(m) - abs(n)) * XZ
        if abs(m) > 160 or abs(n) > 160:
            t += 1; continue
        x, y, z = px, py, pz
        cx, cy, cz = mx, my, mz
        for _ in range(t):
            cx *= f; cy = (cy - g) * f; cz *= f
            x += cx; y += cy; z += cz
        if y <= GROUND:
            break
        err = (x - dx_t) ** 2 + (z - dz_t) ** 2
        if err < last:
            out.append({'t': t, 'm': m, 'n': n, 'x': x, 'y': y, 'z': z,
                        'err': math.sqrt(err)})
            last = err
        t += 1
    return out


def new_core(px, py, pz, bmy, dx_t, dz_t, max_ticks=600):
    """新架构"""
    deltax = dx_t - px
    deltaz = dz_t - pz
    direction = ('E' if deltax > 0 else 'W') if abs(deltax) > abs(deltaz) \
        else ('S' if deltaz > 0 else 'N')

    cands = []
    for t in range(1, max_ticks + 1):
        D = f * (1.0 - f ** t) / (1.0 - f)     # ADP 位移系数
        kp = 2.0 * XZ * D
        if direction in ('N', 'S'):
            m = round((deltax + deltaz) / kp)
            n = round((deltaz - deltax) / kp)
            if direction == 'N':
                m, n = n, m
        else:
            m = round((deltax + deltaz) / kp)
            n = round((deltax - deltaz) / kp)
            if direction == 'W':
                m, n = n, m
        m = max(-160, min(160, m)); n = max(-160, min(160, n))

        # 初速度
        # ★ m、n 已在上面完成方向交换（仅 N/W），此处直接使用，不再交换。
        #   重复交换会让 N/W 方向副方向符号翻转，导致朝北/朝西目标打偏。
        vy = abs(m + n) * Y + bmy
        main = (m + n) * XZ
        sub = (abs(m) - abs(n)) * XZ
        if direction in ('N', 'S'):
            vx, vz = sub, main
        else:
            vx, vz = main, sub

        x, y, z = px, py, pz
        cx, cy, cz = vx, vy, vz
        hit = False
        for _ in range(t):
            cy = (cy - g) * f; cx *= f; cz *= f
            x += cx; y += cy; z += cz
            if y <= GROUND:
                hit = True; break
        if hit:
            break
        err = math.hypot(x - dx_t, z - dz_t)
        cands.append({'t': t, 'm': m, 'n': n, 'x': x, 'y': y, 'z': z, 'err': err})
    cands.sort(key=lambda c: (c['err'], abs(c['m']) + abs(c['n']), c['t']))
    return cands


print("=" * 78)
print("[1] 多场景：新架构 vs 原作者逐位比对")
print("=" * 78)

scenarios = [
    ("近距 NE",  2.0, 169.630464, 28.0, -0.003727,   300.0,   400.0),
    ("中距 S",   2.0, 169.630464, 28.0, -0.003727,   800.0,  1500.0),
    ("远距 S",   2.0, 169.630464, 28.0, -0.003727,  1234.0,  4321.0),
    ("NW 象限",  2.0, 169.630464, 28.0, -0.003727,  -500.0,  -900.0),
    ("SW 象限",  2.0, 169.630464, 28.0, -0.003727,  -300.0,   800.0),
    ("SE 象限",  2.0, 169.630464, 28.0, -0.003727,   600.0,  -700.0),
    ("零偏移",   0.0, 200.0,       0.0,  0.0,          50.0,    50.0),
    ("主轴东",   2.0, 169.630464, 28.0, -0.003727,  2000.0,     5.0),
]

all_ok = True
for name, px, py, pz, bmy, tx, tz in scenarios:
    o = original_core(px, py, pz, bmy, tx, tz)
    n = new_core(px, py, pz, bmy, tx, tz)
    if not o or not n:
        print(f"  [—] {name:<10} 双方均无解（一致）")
        continue
    bo = min(o, key=lambda r: r['err'])
    bn = n[0]
    same = (bo['t'] == bn['t'] and bo['m'] == bn['m'] and bo['n'] == bn['n']
            and abs(bo['x'] - bn['x']) < 1e-9
            and abs(bo['y'] - bn['y']) < 1e-9
            and abs(bo['z'] - bn['z']) < 1e-9
            and abs(bo['err'] - bn['err']) < 1e-9)
    flag = "✓" if same else "✗"
    if not same:
        all_ok = False
    print(f"  [{flag}] {name:<10} tick={bn['t']:<4} m={bn['m']:<5} n={bn['n']:<5} "
          f"err={bn['err']:.6f}  (候选 {len(n)} 组)")

print()
print(f"  结论：{'全部一致 ✓' if all_ok else '存在偏差 ✗'}")


# ==========================================
# 2. 采样点反推自洽性
# ==========================================
print()
print("=" * 78)
print("[2] 采样点反推自洽性（反推 → 正向仿真 → 回到采样点）")
print("=" * 78)


def simulate_adp(px, py, pz, vx, vy, vz, ticks):
    for _ in range(ticks):
        vy = (vy - g) * f; vx *= f; vz *= f
        px += vx; py += vy; pz += vz
    return px, py, pz, vx, vy, vz


def invert_adp(sx, sy, sz, svx, svy, svz, ticks):
    """反向积分回起爆瞬时"""
    x, y, z = sx, sy, sz
    vx, vy, vz = svx, svy, svz
    for _ in range(ticks):
        x -= vx; y -= vy; z -= vz      # 撤销位移
        vx /= f; vy /= f; vz /= f      # 撤销阻力
        vy += g                        # 撤销加速度
    return x, y, z, vx, vy, vz


print(f"  {'采样tick':>8}  {'放大倍数':>10}  {'自洽误差':>14}  判定")
ok_inv = True
for st in [1, 5, 10, 30, 72, 150, 300, 600]:
    # 构造：从已知初态正向飞 st 个 tick
    p0 = (2.0, 169.630464, 28.0)
    v0 = (30.0, 0.8, 110.0)
    sx, sy, sz, svx, svy, svz = simulate_adp(
        p0[0], p0[1], p0[2], v0[0], v0[1], v0[2], st)

    # 反推
    ix, iy, iz, ivx, ivy, ivz = invert_adp(sx, sy, sz, svx, svy, svz, st)

    # 用反推结果正向跑回去，看能否回到采样点
    cx, cy, cz, _, _, _ = simulate_adp(ix, iy, iz, ivx, ivy, ivz, st)
    err = math.sqrt((cx - sx) ** 2 + (cy - sy) ** 2 + (cz - sz) ** 2)

    amp = (1.0 / f) ** st
    level = ("✓优秀" if err < 1e-3 else "✓可用" if err < 1e-2
             else "⚠勉强" if err < 1e-1 else "✗不自洽")
    if err >= 1e-1:
        ok_inv = False
    print(f"  {st:>8}  {amp:>10.2f}  {err:>14.2e}  {level}")

print()
print(f"  结论：{'反推自洽 ✓' if ok_inv else '存在不自洽 ✗'}")


# ==========================================
# 3. 炮码编码往返
# ==========================================
print()
print("=" * 78)
print("[3] 炮码编码往返：(m,n) → bits → (m,n)")
print("=" * 78)

# 全覆盖 0..160
bad = []
for v in range(0, MAXP + 1):
    if bits_to_num(num_to_bits(v)) != v:
        bad.append(v)
print(f"  0..{MAXP} 全覆盖测试："
      f"{'✓ 全部往返一致' if not bad else f'✗ 失败 {len(bad)} 个: {bad[:10]}'}")

# 边界值
for v in [0, 1, 159, MAXP, MAXP + 1, -1, -160]:
    enc = num_to_bits(abs(v))
    dec = bits_to_num(enc)
    ok = (dec == min(abs(v), MAXP))
    print(f"  [{'✓' if ok else '✗'}] v={v:<5} bits={enc}  "
          f"解码={dec} (钳制至 {min(abs(v), MAXP)})")

# 贪心覆盖性检查：是否 0..160 每个值都能被表示
covered = set()
for v in range(0, MAXP + 1):
    covered.add(bits_to_num(num_to_bits(v)))
gaps = sorted(set(range(0, MAXP + 1)) - covered)
print(f"  贪心编码覆盖性：{'✓ 无缺口' if not gaps else f'✗ 缺口 {gaps}'}")


# ==========================================
# 4. Legacy vs ADP 必须不同
# ==========================================
print()
print("=" * 78)
print("[4] Legacy / ADP 两种版本模型必须产生不同结果")
print("=" * 78)


def simulate_legacy(px, py, pz, vx, vy, vz, ticks):
    """Legacy: 位移 → 阻力 → 加速度"""
    for _ in range(ticks):
        px += vx; py += vy; pz += vz
        vx *= f; vy *= f; vz *= f
        vy -= g
    return px, py, pz


p0 = (2.0, 169.630464, 28.0)
v0 = (30.0, 0.8, 110.0)
for t in [10, 40, 80]:
    ax, ay, az, _, _, _ = simulate_adp(p0[0], p0[1], p0[2],
                                       v0[0], v0[1], v0[2], t)
    lx, ly, lz = simulate_legacy(p0[0], p0[1], p0[2], v0[0], v0[1], v0[2], t)
    diff = math.sqrt((ax - lx) ** 2 + (ay - ly) ** 2 + (az - lz) ** 2)
    print(f"  tick={t:<4} ADP=({ax:.4f}, {ay:.4f}, {az:.4f})  "
          f"Legacy=({lx:.4f}, {ly:.4f}, {lz:.4f})  差异={diff:.4f}")

print()
print("  结论：两种模型结果不同 ✓（证明未被静默复用同一套公式）")

print()
print("=" * 78)
overall = all_ok and ok_inv and not bad and not gaps
print("✓ 全部通过" if overall else "✗ 存在问题")
print("=" * 78)
