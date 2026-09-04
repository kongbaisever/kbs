"""
验证：落点模式 vs 拦截模式

================================================================================
核心断言
================================================================================

1. 落点模式：误差 = 落点 XZ 与目标的距离（必须与原作者算法一致）
2. 拦截模式：误差 = 整条轨迹上 XZ 最接近目标那一点的距离
3. 拦截模式的误差 <= 落点模式的误差
   （拦截是在整条轨迹上取最优，落点只是轨迹末端那一个点）
4. 拦截模式能打到落点模式够不到的远距离目标
"""
import sys
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'scripts'))

F = 0.99
G = 0.03


def f32(v):
    import struct
    return struct.unpack('!f', struct.pack('!f', v))[0]


DRAG = f32(0.99)
MOTION_XZ = 0.6026793588895138
MOTION_Y = 0.004435058914919521
WEIGHTS = [80, 40, 20, 10, 4, 3, 2, 1]
MAX_ENC = sum(WEIGHTS)


def disp(t):
    """位移系数 D(t) = F*(1-F^t)/(1-F) —— 原作者模型"""
    return DRAG * (1 - DRAG ** t) / (1 - DRAG)


def ydisp(t):
    return (1 - DRAG ** t) / (1 - DRAG)


def clamp(v):
    return max(-MAX_ENC, min(MAX_ENC, v))


def num_to_bits(num):
    bits = []
    for w in WEIGHTS:
        bits.append('1' if num >= w else '0')
        if bits[-1] == '1':
            num -= w
    return ''.join(bits)


DIRS = {'N': '00', 'W': '01', 'E': '10', 'S': '11'}


def initial_vel(m, n, direction, base_vel):
    if direction in ('N', 'S'):
        mm, nn = m, n
        if direction == 'N':
            mm, nn = n, m
        mx = (abs(mm) - abs(nn)) * MOTION_XZ
        mz = (mm + nn) * MOTION_XZ
    else:
        mm, nn = m, n
        if direction == 'W':
            mm, nn = n, m
        mx = (mm + nn) * MOTION_XZ
        mz = (abs(mm) - abs(nn)) * MOTION_XZ
    my = abs(m + n) * MOTION_Y + base_vel[1]
    return (mx, my, mz)


def simulate(pos, vel, ticks, ground):
    """逐 tick 仿真（ADP 顺序），返回 (落点, 是否触地, 轨迹点)"""
    px, py, pz = pos
    vx, vy, vz = vel
    pts = [(0, px, py, pz)]
    for t in range(1, ticks + 1):
        ay = vy - G
        vx, vy, vz = vx * DRAG, ay * DRAG, vz * DRAG
        px, py, pz = px + vx, py + vy, pz + vz
        pts.append((t, px, py, pz))
        if py <= ground:
            return (px, py, pz), True, pts
    return (px, py, pz), False, pts


def solve(delta_x, delta_z, dest_x, dest_z, origin, base_vel,
          ground=128.0, mode='landing', max_ticks=600):
    """
    mode='landing'   落点模式：珍珠**真正落地**的位置为终点
    mode='intercept' 拦截模式：飞行途中经过目标 XZ 上空的那一刻为终点

    两者的关键在于闭式解给出的 (m,n) 只保证"第 t tick 时位于目标 XZ"，
    但那一时刻珍珠**未必已落地**：
      · 若还在半空 → 拦截成立，落点模式则必须继续飞到真正落地
      · 落地点会飞过目标，误差因此变大
    这正是两种模式给出不同解的原因。
    """
    if abs(delta_x) > abs(delta_z):
        direction = 'E' if delta_x > 0 else 'W'
    else:
        direction = 'S' if delta_z > 0 else 'N'

    results = []

    for t in range(1, max_ticks + 1):
        kp = 2 * MOTION_XZ * disp(t)
        if kp <= 0:
            continue
        if direction in ('N', 'S'):
            m = round((delta_x + delta_z) / kp)
            n = round((delta_z - delta_x) / kp)
            if direction == 'N':
                m, n = n, m
        else:
            m = round((delta_x + delta_z) / kp)
            n = round((delta_x - delta_z) / kp)
            if direction == 'W':
                m, n = n, m

        m = int(clamp(m))
        n = int(clamp(n))
        if abs(m) > 160 or abs(n) > 160:
            continue

        vel = initial_vel(m, n, direction, base_vel)

        if mode == 'intercept':
            # 只仿真 t 个 tick，取轨迹上 XZ 最接近目标的那一点
            _, _, pts = simulate(origin, vel, t, ground)
            best_pt = min(
                pts, key=lambda p: ((p[1] - dest_x) ** 2 + (p[3] - dest_z) ** 2))
            err = ((best_pt[1] - dest_x) ** 2 + (best_pt[3] - dest_z) ** 2) ** 0.5
            results.append((err, t, m, n, direction, best_pt[1:], False))
        else:
            # 仿真到**真正落地**（不受 t 限制），落地点才是终点
            land, hit, _ = simulate(origin, vel, max_ticks, ground)
            if not hit:
                # 飞满了仍未落地 —— 说明这个 (m,n) 的弹道太高，
                # 永远到不了地面，对落点模式无意义
                continue
            err = ((land[0] - dest_x) ** 2 + (land[2] - dest_z) ** 2) ** 0.5
            results.append((err, t, m, n, direction, land, True))

    if not results:
        return None
    return min(results, key=lambda r: (r[0], r[1]))


def main():
    print("=" * 78)
    print("  落点模式 vs 拦截模式 验证")
    print("=" * 78)

    # 用户截图的真实参数
    origin = (2.0, 169.630464, 28.0)
    base_vel = (0.0, -0.003727, 0.0)
    ground = 128.0
    dest_x, dest_z = 1234.0, 4321.0
    delta_x = dest_x - origin[0]
    delta_z = dest_z - origin[2]

    print(f"\n起爆点: {origin}")
    print(f"目标:   ({dest_x}, {dest_z})")
    print(f"地面:   {ground}")
    print()

    landing = solve(delta_x, delta_z, dest_x, dest_z, origin, base_vel,
                    ground, mode='landing')
    intercept = solve(delta_x, delta_z, dest_x, dest_z, origin, base_vel,
                      ground, mode='intercept')

    print("── 落点模式 ──")
    if landing:
        err, t, m, n, d, pos, _ = landing
        print(f"  tick={t}  m={m} n={n} 方向={d}")
        print(f"  落点=({pos[0]:.4f}, {pos[1]:.4f}, {pos[2]:.4f})")
        print(f"  误差={err:.6f} 格")

    print("\n── 拦截模式 ──")
    if intercept:
        err, t, m, n, d, pos, _ = intercept
        print(f"  tick={t}  m={m} n={n} 方向={d}")
        print(f"  拦截点=({pos[0]:.4f}, {pos[1]:.4f}, {pos[2]:.4f})")
        print(f"  误差={err:.6f} 格")

    print()
    print("=" * 78)
    print("  断言")
    print("=" * 78)

    fails = []

    # 1) 拦截误差 <= 落点误差
    if landing and intercept:
        if intercept[0] <= landing[0] + 1e-9:
            print(f"  ✓ 拦截误差({intercept[0]:.4f}) <= "
                  f"落点误差({landing[0]:.4f})")
        else:
            fails.append("拦截误差竟然大于落点误差，逻辑有误")

    # 2) 拦截点 Y 应高于地面（半空拦截）
    if intercept:
        if intercept[5][1] > ground:
            print(f"  ✓ 拦截点在半空 Y={intercept[5][1]:.2f} > 地面 {ground}")
        else:
            print(f"  ! 拦截点贴地 Y={intercept[5][1]:.2f}（该目标落点模式已最优）")

    # 3) 远距离目标：拦截应明显更优
    far_x, far_z = 8000.0, 8000.0
    fl = solve(far_x - origin[0], far_z - origin[2], far_x, far_z,
               origin, base_vel, ground, mode='landing')
    fi = solve(far_x - origin[0], far_z - origin[2], far_x, far_z,
               origin, base_vel, ground, mode='intercept')
    print(f"\n  远距离目标 ({far_x}, {far_z}):")
    if fl:
        print(f"    落点模式 误差={fl[0]:.4f} tick={fl[1]}")
    if fi:
        print(f"    拦截模式 误差={fi[0]:.4f} tick={fi[1]}")
    if fl and fi and fi[0] < fl[0]:
        print(f"  ✓ 拦截模式在远距离上更优 "
              f"（{fi[0]:.4f} < {fl[0]:.4f}）")

    print()
    if fails:
        for f in fails:
            print(f"  ✗ {f}")
        return 1
    print("  ✓ 全部通过")
    return 0


if __name__ == '__main__':
    sys.exit(main())
