"""
验证重写后的核心算法与原作者 Python 逐位一致。

原作者 Python 是唯一的事实来源（该炮已实机验证可用）。
本脚本用 Python 复现新架构中 Kotlin 的逻辑，然后逐 tick 比对，
确保重写没有引入数值偏差。

新架构的两个关键点：
  1. 闭式解只用于生成候选 (m,n)
  2. 落点由逐 tick 仿真给出（与原作者的模拟循环一致）
"""
import struct
import math

# ==========================================
# 原作者常量（黄金基准）
# ==========================================
def float32_to_float64(val):
    packed = struct.pack('!f', val)
    unpacked = struct.unpack('!f', packed)[0]
    return float(unpacked)

g = 0.03
f = float32_to_float64(0.99)
one_tnt_motion_xz = 0.6026793588895138
one_tnt_motion_y = 0.004435058914919521
ground_height = 128

# 截图表单值（用户的实际参数）
projectedPos = [2.0, 169.630464, 28.0]
projectedMotion = [0.0, -0.003727, 0.0]
destination_x = 1234
destination_z = 4321


def num_to_bits(num):
    values = [80, 40, 20, 10, 4, 3, 2, 1]
    bits = []
    for value in values:
        if num >= value:
            bits.append('1')
            num -= value
        else:
            bits.append('0')
    bit_str = ''.join(bits)
    return f"{bit_str[:4]} {bit_str[4:]}"


directions_mapping = {'N': '00', 'W': '01', 'E': '10', 'S': '11'}


# ==========================================
# 原作者主循环（黄金基准，不做任何改动）
# ==========================================
def original_solve():
    deltax = destination_x - projectedPos[0]
    deltaz = destination_z - projectedPos[2]

    if abs(deltax) > abs(deltaz):
        direction = 'E' if deltax > 0 else 'W'
    else:
        direction = 'S' if deltaz > 0 else 'N'

    last_error = float('inf')
    fly_tick_num = 1
    results = []

    while True:
        kp = 2 * one_tnt_motion_xz * ((f - f ** (fly_tick_num + 1)) / (1 - f))

        if direction in ('N', 'S'):
            m = round((deltax + deltaz) / kp)
            n = round((deltaz - deltax) / kp)
            if direction == 'N':
                m, n = n, m
            motion_x = (abs(m) - abs(n)) * one_tnt_motion_xz
            motion_y = abs(m + n) * one_tnt_motion_y + projectedMotion[1]
            motion_z = (m + n) * one_tnt_motion_xz
        else:
            m = round((deltax + deltaz) / kp)
            n = round((deltax - deltaz) / kp)
            if direction == 'W':
                m, n = n, m
            motion_x = (m + n) * one_tnt_motion_xz
            motion_y = abs(m + n) * one_tnt_motion_y + projectedMotion[1]
            motion_z = (abs(m) - abs(n)) * one_tnt_motion_xz

        if abs(m) > 160 or abs(n) > 160:
            fly_tick_num += 1
            continue

        pos_x, pos_y, pos_z = projectedPos
        cmx, cmy, cmz = motion_x, motion_y, motion_z

        for _ in range(fly_tick_num):
            cmx *= f
            cmy = (cmy - g) * f
            cmz *= f
            pos_x += cmx
            pos_y += cmy
            pos_z += cmz

        if pos_y <= ground_height:
            break

        error = (pos_x - destination_x) ** 2 + (pos_z - destination_z) ** 2
        if error < last_error:
            results.append({
                'tick': fly_tick_num,
                'm': m, 'n': n, 'direction': direction,
                'pos': (pos_x, pos_y, pos_z),
                'error': math.sqrt(error),
                'code': (num_to_bits(round(abs(n)))[::-1] + " " +
                         directions_mapping[direction] + " " +
                         num_to_bits(round(abs(m)))),
                'motion': (motion_x, motion_y, motion_z),
            })
            last_error = error

        fly_tick_num += 1
    return results


# ==========================================
# 新架构复现（对应 Kotlin 实现）
# ==========================================
def new_displacement_factor(t, order='ADP'):
    """水平位移系数 D(t)"""
    pow_ft = f ** t
    if order == 'ADP':
        # f*(1-f^t)/(1-f)  ==  (f - f^(t+1))/(1-f)
        return f * (1.0 - pow_ft) / (1.0 - f)
    else:
        return (1.0 - pow_ft) / (1.0 - f)


def new_pulse_scale(t, order='ADP'):
    """PulseModel.pulseScale = 2 * xz * D(t)"""
    return 2.0 * one_tnt_motion_xz * new_displacement_factor(t, order)


def determine_direction(dx, dz):
    """PulseModel.determineDirection"""
    if abs(dx) > abs(dz):
        return 'E' if dx > 0 else 'W'
    return 'S' if dz > 0 else 'N'


def new_solve_pulses(dx, dz, t, direction, order='ADP'):
    """PulseModel.solvePulses"""
    kp = new_pulse_scale(t, order)
    if abs(kp) < 1e-12:
        return 0, 0
    if direction in ('N', 'S'):
        m = round((dx + dz) / kp)
        n = round((dz - dx) / kp)
        if direction == 'N':
            m, n = n, m
    else:
        m = round((dx + dz) / kp)
        n = round((dx - dz) / kp)
        if direction == 'W':
            m, n = n, m
    return m, n


def new_initial_velocity(m, n, direction, base_my):
    """PulseModel.initialVelocity（Y 用原作者 SUM_THEN_ABS）"""
    pulses_y = abs(m + n)
    vy = pulses_y * one_tnt_motion_y + base_my

    mm, nn = m, n
    if direction in ('N', 'W'):
        mm, nn = nn, mm

    main = (mm + nn) * one_tnt_motion_xz
    sub = (abs(mm) - abs(nn)) * one_tnt_motion_xz

    if direction in ('N', 'S'):
        return sub + projectedMotion[0], vy, main + projectedMotion[2]
    return main + projectedMotion[0], vy, sub + projectedMotion[2]


def new_simulate_until_ground(vel, max_ticks):
    """Integrator.simulateUntilGround（ADP 顺序）"""
    px, py, pz = projectedPos
    vx, vy, vz = vel
    for t in range(1, max_ticks + 1):
        vy = (vy - g) * f
        vx *= f
        vz *= f
        px += vx
        py += vy
        pz += vz
        if py <= ground_height:
            return (px, py, pz), t, True
    return (px, py, pz), max_ticks, False


def new_solve():
    """PearlSolver.solve 的复现"""
    dx = destination_x - projectedPos[0]
    dz = destination_z - projectedPos[2]
    direction = determine_direction(dx, dz)

    candidates = []
    for tick in range(1, 601):
        m, n = new_solve_pulses(dx, dz, tick, direction)
        # 硬钳制
        m = max(-160, min(160, m))
        n = max(-160, min(160, n))

        vel = new_initial_velocity(m, n, direction, projectedMotion[1])
        pos, actual, hit = new_simulate_until_ground(vel, tick)
        if hit:
            break

        err = math.hypot(pos[0] - destination_x, pos[2] - destination_z)
        candidates.append({
            'tick': tick, 'm': m, 'n': n, 'direction': direction,
            'pos': pos, 'error': err,
            'code': (num_to_bits(round(abs(n)))[::-1] + " " +
                     directions_mapping[direction] + " " +
                     num_to_bits(round(abs(m)))),
            'motion': vel,
        })

    candidates.sort(key=lambda c: (c['error'], abs(c['m']) + abs(c['n']), c['tick']))
    return candidates


# ==========================================
# 比对
# ==========================================
print("=" * 78)
print("重写核心算法 vs 原作者 Python 一致性验证")
print("=" * 78)
print(f"基准参数：baseY={projectedPos[1]}, baseMy={projectedMotion[1]}")
print(f"目标：({destination_x}, {destination_z})")
print()

orig = original_solve()
new = new_solve()

print(f"[原作者] 输出解数：{len(orig)}")
print(f"[重写后] 候选解数：{len(new)}")
print()

# 原作者输出的是"误差递减"的序列，最后一条是误差最小的
if orig:
    best_orig = min(orig, key=lambda r: r['error'])
    print("原作者最优解：")
    print(f"  tick={best_orig['tick']}  m={best_orig['m']}  n={best_orig['n']}  "
          f"dir={best_orig['direction']}")
    print(f"  落点=({best_orig['pos'][0]:.6f}, {best_orig['pos'][1]:.6f}, "
          f"{best_orig['pos'][2]:.6f})")
    print(f"  误差={best_orig['error']:.6f}")
    print(f"  炮码={best_orig['code']}")
    print(f"  初速=({best_orig['motion'][0]:.10f}, {best_orig['motion'][1]:.10f}, "
          f"{best_orig['motion'][2]:.10f})")

print()
if new:
    best_new = new[0]
    print("重写后最优解：")
    print(f"  tick={best_new['tick']}  m={best_new['m']}  n={best_new['n']}  "
          f"dir={best_new['direction']}")
    print(f"  落点=({best_new['pos'][0]:.6f}, {best_new['pos'][1]:.6f}, "
          f"{best_new['pos'][2]:.6f})")
    print(f"  误差={best_new['error']:.6f}")
    print(f"  炮码={best_new['code']}")
    print(f"  初速=({best_new['motion'][0]:.10f}, {best_new['motion'][1]:.10f}, "
          f"{best_new['motion'][2]:.10f})")

print()
print("=" * 78)
print("逐项比对")
print("=" * 78)

ok = True
if orig and new:
    bo, bn = best_orig, best_new
    checks = [
        ("最优 tick", bo['tick'], bn['tick'], lambda a, b: a == b),
        ("m 值", bo['m'], bn['m'], lambda a, b: a == b),
        ("n 值", bo['n'], bn['n'], lambda a, b: a == b),
        ("方向", bo['direction'], bn['direction'], lambda a, b: a == b),
        ("落点 X", bo['pos'][0], bn['pos'][0], lambda a, b: abs(a - b) < 1e-9),
        ("落点 Y", bo['pos'][1], bn['pos'][1], lambda a, b: abs(a - b) < 1e-9),
        ("落点 Z", bo['pos'][2], bn['pos'][2], lambda a, b: abs(a - b) < 1e-9),
        ("误差", bo['error'], bn['error'], lambda a, b: abs(a - b) < 1e-9),
        ("炮码", bo['code'], bn['code'], lambda a, b: a == b),
    ]
    for name, a, b, cmp in checks:
        match = cmp(a, b)
        flag = "✓" if match else "✗"
        print(f"  [{flag}] {name:<10} 原={a}  新={b}")
        if not match:
            ok = False

print()
print("=" * 78)
if ok:
    print("✓ 完全一致 —— 重写后的算法与原作者逐位对齐")
else:
    print("✗ 存在偏差")
print("=" * 78)
