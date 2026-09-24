"""四指扫动判定器的单元测试（纯逻辑，不需要摄像头）。

这些测试是"算法对不对"的唯一验证手段——手机上没法自动测，所以在这里把
边界情况全部钉死，Kotlin 侧照搬逻辑并回放同一批测试向量（见 make_sweep_vectors.py）。

跑法：
    .venv\\Scripts\\python.exe -m unittest probe.test_detector_ref -v
"""
from __future__ import annotations

import math
import os
import random
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from probe.detector_ref import (  # noqa: E402
    COOLDOWN, IDLE, SETTLE, WATCH, SweepDetector, quadrant,
)

FPS = 30.0
DT = 1.0 / FPS


def feed(det: SweepDetector, frames, t0: float = 0.0):
    """frames: [(present, x, y, together[, extended]), ...]，按 30fps 打时间戳。返回触发序列。"""
    out = []
    t = t0
    for fr in frames:
        present, x, y, together = fr[0], fr[1], fr[2], fr[3]
        extended = fr[4] if len(fr) > 4 else 4
        t += DT
        e = det.update(t, present, x, y, together, extended)
        if e:
            out.append(e)
    return out, t


def move(x0, y0, x1, y1, n, together=1.0, present=True, extended=4):
    return [(present, x0 + (x1 - x0) * i / max(1, n - 1), y0 + (y1 - y0) * i / max(1, n - 1),
             together, extended) for i in range(n)]


def hold(x, y, n, together=1.0, present=True, extended=4):
    return [(present, x, y, together, extended) for _ in range(n)]


def arm(x=0.5, y=0.5, n=12, together=1.0, extended=4):
    """动作1：摆好起始姿势并停稳（让程序从 SETTLE 进入 WATCH）。

    12 帧 = 400ms > settle_ms(250ms)。**所有扫动序列都必须以它开头**，
    因为"手伸到位"这个动作本身也是一次位移，必须先被 SETTLE 吃掉。
    extended=4 = 四指都伸出来（姿势闸门的默认要求）。
    """
    return hold(x, y, n, together=together, extended=extended)


class TestQuadrant(unittest.TestCase):
    def test_boundaries(self):
        self.assertEqual(quadrant(0), "right")
        self.assertEqual(quadrant(44.9), "right")
        self.assertEqual(quadrant(45.0), "up")
        self.assertEqual(quadrant(90), "up")
        self.assertEqual(quadrant(134.9), "up")
        self.assertEqual(quadrant(135.0), "left")
        self.assertEqual(quadrant(180), "left")
        self.assertEqual(quadrant(225.0), "down")
        self.assertEqual(quadrant(270), "down")
        self.assertEqual(quadrant(314.9), "down")
        self.assertEqual(quadrant(315.0), "right")
        self.assertEqual(quadrant(359.9), "right")


class TestSweepDetection(unittest.TestCase):
    def test_four_directions(self):
        """四个方向各扫一次，方向必须都对。"""
        cases = [("up", (0.5, 0.5), (0.5, 0.32)),
                 ("down", (0.5, 0.5), (0.5, 0.68)),
                 ("left", (0.5, 0.5), (0.32, 0.5)),
                 ("right", (0.5, 0.5), (0.68, 0.5))]
        for want, p0, p1 in cases:
            with self.subTest(direction=want):
                det = SweepDetector()
                got, _ = feed(det, arm(*p0) + move(*p0, *p1, 6) + hold(*p1, 30))
                self.assertEqual(got, [want], f"{want} 方向判定错误，实际 {got}")

    def test_single_sweep_emits_exactly_once(self):
        """一次扫动只能触发一次，不能因为"继续举着"而重复触发。"""
        det = SweepDetector()
        got, _ = feed(det, arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.30, 6) + hold(0.5, 0.30, 90))
        self.assertEqual(got, ["up"])

    # ------------------------------------------------------- ★ 摆姿势不能算扫动

    def test_arrival_motion_does_not_trigger(self):
        """**用户实测的 bug**：手伸上来摆姿势本身也是一次位移，绝不能被当成扫动。

        这段是"从画面外伸进来、挪到起始位置"的大幅运动（0.4 屏高，很快），
        没有 SETTLE 的话它会顺着运动方向滑一下 —— 表现就是"手往哪伸就往哪滑"。
        """
        det = SweepDetector()
        arrival = move(0.5, 0.95, 0.5, 0.55, 6)      # 伸手到位：0.4 屏高、200ms
        got, _ = feed(det, arrival + hold(0.5, 0.55, 30))
        self.assertEqual(got, [], f"伸手到位被误判成扫动: {got}")
        self.assertEqual(det.state, WATCH, "停稳后应该已经进入 WATCH（姿势1锁定）")
        self.assertGreaterEqual(det.armed_count, 1)
        # 摆好姿势之后再扫，必须照常触发
        got2, _ = feed(det, move(0.5, 0.55, 0.5, 0.25, 6) + hold(0.5, 0.25, 20), t0=5.0)
        self.assertEqual(got2, ["up"], f"就绪后扫动应触发，实际 {got2}")

    def test_continuous_motion_never_arms(self):
        """手一直不停（一直以可感知的速度移动）就不该进入可判定状态——没有"姿势1"就没有动作1。

        注意速度要**高于**quiet_speed(0.35/s)：0.02 屏高/帧 = 0.6/s，程序才能认定"手在动"。
        （低于 0.35/s 时程序会认为算静止、于是正常进入 WATCH，但那种速度也累积不到阈值，
          所以两种情况下都不会误触发 —— 这正是死区 + SETTLE 两道保护的意义。）
        """
        det = SweepDetector()
        frames = [(True, 0.5, 0.9 - i * 0.02, 1.0) for i in range(120)]
        got, _ = feed(det, frames)
        self.assertEqual(got, [], f"持续移动被误判成扫动: {got}")
        self.assertEqual(det.state, SETTLE, "手一直在动，不应该进入 WATCH")

    # ---------------------------------------------------------------- 冷静期

    def test_cooldown_ignores_everything(self):
        """冷静期 = 清除上一轮的记忆：期间的大幅反向运动（收手）必须被完全忽略。"""
        det = SweepDetector()
        frames = (arm(0.5, 0.5)
                  + move(0.5, 0.5, 0.5, 0.30, 6)          # 动作2：扫动（触发）
                  + move(0.5, 0.30, 0.5, 0.72, 6)         # 收手（反向，落在冷静期内）
                  + hold(0.5, 0.72, 20))
        got, _ = feed(det, frames)
        self.assertEqual(got, ["up"], f"冷静期内不该产生第二个事件: {got}")

    def test_normal_return_within_cooldown(self):
        """正常的"扫出去 + 收回来"整段落在冷静期内 => 只触发一次。"""
        det = SweepDetector(cooldown_ms=600)
        frames = (arm(0.5, 0.5)
                  + move(0.5, 0.5, 0.5, 0.32, 6)
                  + move(0.5, 0.32, 0.5, 0.50, 12)        # 归位 400ms，落在 600ms 冷静期内
                  + hold(0.5, 0.50, 30))
        got, _ = feed(det, frames)
        self.assertEqual(got, ["up"], f"归位被误判成了 {got}")

    def test_return_slower_than_cooldown_is_caught_by_settle(self):
        """收手比冷静期还慢也不会误触发——因为冷静期之后还要 SETTLE（手没停就不测）。

        这一条是 SETTLE 带来的额外好处：它把"冷静期覆盖不到的那半截归位"也接住了。
        """
        det = SweepDetector(cooldown_ms=400)
        frames = (arm(0.5, 0.5)
                  + move(0.5, 0.5, 0.5, 0.20, 6)          # 大幅扫动（触发）
                  + move(0.5, 0.20, 0.5, 0.60, 30)        # 很慢的收手：1000ms
                  + hold(0.5, 0.60, 30))
        got, _ = feed(det, frames)
        self.assertEqual(got, ["up"], f"慢速收手被误判成了 {got}")

    def test_second_sweep_after_cooldown_and_settle(self):
        """冷静期结束 + 重新摆好姿势之后，必须能再扫一次。"""
        det = SweepDetector(cooldown_ms=500)
        frames = (arm(0.5, 0.5)
                  + move(0.5, 0.5, 0.5, 0.30, 6)          # up（触发）
                  + hold(0.5, 0.30, 30)                   # 等过冷静期 + SETTLE
                  + move(0.5, 0.30, 0.5, 0.70, 6)         # 再向下扫
                  + hold(0.5, 0.70, 20))
        got, _ = feed(det, frames)
        self.assertEqual(got, ["up", "down"], f"第二轮没触发: {got}")

    # ------------------------------------------- ★ 冷静期从"触发那一瞬间"开始算
    def test_cooldown_starts_at_trigger_instant_not_after_swipe(self):
        """★ 用户明确要求：冷静期在**触发滑动那一瞬间**开始，而不是等滑动做完。

        判据（都在判定器内部，与"合成滑动播多久"无关）：
          · 触发那一帧 `dbg_cooldown_left_ms` 就等于完整的 cooldown_ms；
          · 从触发帧起算，过了 cd-10ms 仍然处于冷静期（还在被忽略）；
          · 过了 cd 才离开冷静期（进入 SETTLE）。
        """
        cd = 1200.0        # 定稿值（v0.17.9 由 1300 改为 1200）
        det = SweepDetector(cooldown_ms=cd)
        # 摆好姿势 -> 扫出去（触发）。触发发生在位移刚越过阈值那一帧。
        feed(det, arm(0.5, 0.5))
        t_trig = None
        for i in range(6):                       # 6 帧扫完 0.18（阈值 0.14，约第 5 帧触发）
            e = det.update(0.4 + DT * (i + 1), True, 0.5, 0.5 - 0.03 * (i + 1), 1.0, 4)
            if e:
                t_trig = 0.4 + DT * (i + 1)
                self.assertEqual(det.state, COOLDOWN)
                self.assertAlmostEqual(det.dbg_cooldown_left_ms, cd, delta=1.0,
                                       msg="触发那一帧倒计时就必须是完整的冷静期")
                break
        self.assertIsNotNone(t_trig, "这段扫动没触发，测试本身失效了")

        # 触发后 0.8s：仍应在冷静期，倒计时约 500ms
        det.update(t_trig + 0.8, True, 0.5, 0.30, 1.0, 4)
        self.assertEqual(det.state, COOLDOWN, "触发后 0.8s 必须还在冷静期")
        self.assertAlmostEqual(det.dbg_cooldown_left_ms, cd - 800.0, delta=20.0)

        # 触发后 cd-10ms：仍然冷静期
        det.update(t_trig + (cd - 10.0) / 1000.0, True, 0.5, 0.30, 1.0, 4)
        self.assertEqual(det.state, COOLDOWN, "冷静期没走完就必须还在冷静期")

        # 触发后 cd+10ms：离开冷静期，进入 SETTLE（等于"新一轮"）
        det.update(t_trig + (cd + 10.0) / 1000.0, True, 0.5, 0.30, 1.0, 4)
        self.assertEqual(det.state, SETTLE, "冷静期走完应进入 SETTLE（新一轮）")
        self.assertEqual(det.dbg_cooldown_left_ms, 0.0)

    def test_cooldown_wipes_memory_of_previous_round(self):
        """★ 冷静期的语义 = **清空上一轮的记忆**：期间所有位移一点都不累积。

        做法：触发后立刻做一次"幅度远超阈值、方向完全干净"的反向运动，
        只要它落在冷静期内，就一次都不能触发；冷静期一结束、重新停稳后才算新一轮。
        """
        det = SweepDetector()      # 用定稿默认值（1200ms）
        frames = (arm(0.5, 0.5)
                  + move(0.5, 0.5, 0.5, 0.30, 6)          # 触发 up（触发瞬间即进冷静期）
                  + move(0.5, 0.30, 0.5, 0.90, 20)        # 触发后 0.13~0.8s：大幅反向运动
                  + hold(0.5, 0.90, 30))                  # 停稳（此时冷静期还没过）
        got, _ = feed(det, frames)
        self.assertEqual(got, ["up"], f"冷静期内的大幅反向运动被误判成 {got}")

    def test_defaults_are_the_final_confirmed_values(self):
        """定稿参数必须就是默认值（否则 App 里生效的又是一回事）。"""
        det = SweepDetector()
        self.assertEqual(det.enter_thr, 0.14)
        self.assertEqual(det.cooldown_ms, 1200.0)
        self.assertEqual(det.together_max, 2.0)
        self.assertEqual(det.min_extended_fingers, 3)
        self.assertEqual(det.axis_tol_deg, 30.0)

    # ---------------------------------------------------------------- 其他边界

    def test_slow_drift_never_triggers(self):
        """缓慢漂移（呼吸/坐姿变化）绝不能触发。"""
        det = SweepDetector(enter_thr=0.10, max_sweep_ms=600)
        got, _ = feed(det, arm(0.4, 0.6) + move(0.4, 0.6, 0.4, 0.35, 90))
        self.assertEqual(got, [], f"缓慢漂移被误触发成 {got}")

    def test_jitter_never_triggers(self):
        """手举着不动时的抖动不能触发。"""
        rng = random.Random(42)
        det = SweepDetector(enter_thr=0.10)
        frames = [(True, 0.5 + rng.uniform(-0.012, 0.012), 0.5 + rng.uniform(-0.012, 0.012), 1.0)
                  for _ in range(150)]
        got, _ = feed(det, frames)
        self.assertEqual(got, [], f"抖动被误触发成 {got}")

    def test_open_fingers_are_gated(self):
        """手指张开时不做判定（这是压误触发最划算的一道门控）。"""
        det = SweepDetector(together_max=1.35)
        got1, _ = feed(det, arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.28, 6, together=1.8)
                       + hold(0.5, 0.28, 15, together=1.8))
        self.assertEqual(got1, [], "手指张开时不该触发")
        got2, _ = feed(det, move(0.5, 0.28, 0.5, 0.06, 6, together=1.0) + hold(0.5, 0.06, 15),
                       t0=10.0)
        self.assertEqual(got2, ["up"], f"并拢后应能触发，实际 {got2}")

    # ------------------------------------------- ★ "四根手指"闸门（用户实测抓到）
    def test_one_finger_never_triggers(self):
        """★ 用户实测的 bug：只伸一根手指也能触发滑动。

        原因：并拢度只量"四个指尖靠得近不近"，不问那四根手指有没有伸出来；
        MediaPipe 单指时也会输出 21 点（其余是猜的，瘫在掌心），并拢度照样过关。
        """
        det = SweepDetector()
        got, _ = feed(det, arm(0.5, 0.5, extended=1)
                      + move(0.5, 0.5, 0.5, 0.28, 6, extended=1)
                      + hold(0.5, 0.28, 30, extended=1))
        self.assertEqual(got, [], f"单指不该触发，实际 {got}")
        self.assertNotEqual(det.state, WATCH, "单指时不该进入 WATCH（没有锁定姿势）")

    def test_one_and_two_fingers_gated_by_default(self):
        """定稿默认 ≥3 根：1 根、2 根都不触发（这才是这个闸门要解决的问题）。"""
        for n in (1, 2):
            with self.subTest(fingers=n):
                det = SweepDetector()
                got, _ = feed(det, arm(0.5, 0.5, extended=n)
                              + move(0.5, 0.5, 0.5, 0.28, 6, extended=n)
                              + hold(0.5, 0.28, 30, extended=n))
                self.assertEqual(got, [], f"{n} 根手指不该触发，实际 {got}")
                self.assertNotEqual(det.state, WATCH, f"{n} 根时不该锁定")

    def test_three_fingers_pass_by_default_but_can_be_made_strict(self):
        """定稿默认是 3 根：三指能触发；把要求提到 4 根就挡住（证明参数真的生效）。"""
        det = SweepDetector()
        got, _ = feed(det, arm(0.5, 0.5, extended=3)
                      + move(0.5, 0.5, 0.5, 0.28, 6, extended=3)
                      + hold(0.5, 0.28, 30, extended=3))
        self.assertEqual(got, ["up"], f"默认（≥3）三指应触发 up，实际 {got}")

        strict = SweepDetector(min_extended_fingers=4)
        got2, _ = feed(strict, arm(0.5, 0.5, extended=3)
                       + move(0.5, 0.5, 0.5, 0.28, 6, extended=3)
                       + hold(0.5, 0.28, 30, extended=3))
        self.assertEqual(got2, [], f"要求 4 根时三指不该触发，实际 {got2}")

    def test_min_extended_setting_actually_works(self):
        """把闸门放宽到 2 根，同一段双指动作就应该能触发（证明设置项真的生效）。"""
        det = SweepDetector(min_extended_fingers=2)
        got, _ = feed(det, arm(0.5, 0.5, extended=2)
                      + move(0.5, 0.5, 0.5, 0.28, 6, extended=2)
                      + hold(0.5, 0.28, 30, extended=2))
        self.assertEqual(got, ["up"], f"放宽到 2 根后应触发 up，实际 {got}")

    def test_recover_after_extending_fingers(self):
        """先单指（不锁定）→ 再把四指伸出来停稳 → 必须能正常触发。"""
        det = SweepDetector()
        got, _ = feed(det, arm(0.5, 0.5, extended=1) + arm(0.5, 0.5, extended=4)
                      + move(0.5, 0.5, 0.5, 0.28, 6) + hold(0.5, 0.28, 30))
        self.assertEqual(got, ["up"], f"改成四指后应触发 up，实际 {got}")

    def test_finger_curl_during_sweep_does_not_break_it(self):
        """★ 闸门只在"锁定那一刻"判：扫动中手指弯一下（运动模糊很常见）不能打断。

        如果放到 WATCH 里逐帧判，快速扫动时手指关键点会瞬时误判成蜷曲，
        锚点被反复重置 —— 表现出来就是"怎么扫都不触发"。
        """
        det = SweepDetector()
        got, _ = feed(det, arm(0.5, 0.5, extended=4)
                      + move(0.5, 0.5, 0.5, 0.28, 6, extended=2)
                      + hold(0.5, 0.28, 30, extended=2))
        self.assertEqual(got, ["up"], f"扫动中间手指弯了也必须触发 up，实际 {got}")

    def test_pose_gate_off_when_min_extended_zero(self):
        """min_extended_fingers=0 = 关掉这道闸门（老行为的逃生舱）。"""
        det = SweepDetector(min_extended_fingers=0)
        got, _ = feed(det, arm(0.5, 0.5, extended=0)
                      + move(0.5, 0.5, 0.5, 0.28, 6, extended=0)
                      + hold(0.5, 0.28, 30, extended=0))
        self.assertEqual(got, ["up"], f"关掉闸门后应恢复触发，实际 {got}")

    def test_hand_lost_resets(self):
        """手离开画面后重新出现，绝不能因为"位置跳变"而误触发。"""
        det = SweepDetector(lost_ms=300)
        frames = (arm(0.5, 0.5)
                  + move(0.5, 0.5, 0.5, 0.44, 6)            # 只扫了一半（没到阈值）
                  + hold(0.5, 0.44, 20, present=False)       # 手离开 > 300ms
                  + hold(0.5, 0.20, 30))                     # 出现在很远处并停稳
        got, _ = feed(det, frames)
        self.assertEqual(got, [], f"手重新出现时误触发成 {got}")
        self.assertEqual(det.state, WATCH)

    def test_below_threshold_does_not_trigger(self):
        det = SweepDetector(enter_thr=0.10)
        got, _ = feed(det, arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.42, 6) + hold(0.5, 0.42, 30))
        self.assertEqual(got, [], "未达阈值的移动不该触发")

    def test_angle_offset_calibration(self):
        """手机在支架上被转了一个角度时，用 angle_offset 把方向角掰回来。"""
        rot = 60.0
        ang = math.radians(90.0 + rot)
        p0, p1 = (0.5, 0.5), (0.5 + 0.16 * math.cos(ang), 0.5 - 0.16 * math.sin(ang))
        det = SweepDetector()
        got, _ = feed(det, arm(*p0) + move(*p0, *p1, 6) + hold(*p1, 20))
        self.assertEqual(got, ["left"], f"画面转了 {rot}° 时默认应判错（left），实际 {got}")
        det2 = SweepDetector(angle_offset=-rot)
        got2, _ = feed(det2, arm(*p0) + move(*p0, *p1, 6) + hold(*p1, 20))
        self.assertEqual(got2, ["up"], f"加 -{rot}° 偏移后应判对（up），实际 {got2}")

    def test_angle_offset_fixes_90_degree_frame_rotation(self):
        """**用户实测过的情况**：画面整体转了 90°，左右扫却触发上下滑。"""
        for rot in (90.0, -90.0):
            with self.subTest(frame_rotation=rot):
                ang = math.radians(90.0 + rot)
                p0 = (0.5, 0.5)
                p1 = (0.5 + 0.16 * math.cos(ang), 0.5 - 0.16 * math.sin(ang))
                det0 = SweepDetector()
                got0, _ = feed(det0, arm(*p0) + move(*p0, *p1, 6) + hold(*p1, 20))
                self.assertNotEqual(got0, ["up"], "转了 90° 时默认应该判错，否则这个测试没意义")
                det1 = SweepDetector(angle_offset=-rot)
                got1, _ = feed(det1, arm(*p0) + move(*p0, *p1, 6) + hold(*p1, 20))
                self.assertEqual(got1, ["up"], f"加 {-rot}° 偏移后应判对，实际 {got1}")

    def test_diagonal_sweep_is_rejected(self):
        """只判定上下左右，斜的不要（45° 斜扫不触发，随后正着扫照常触发）。"""
        det = SweepDetector()
        diag = move(0.4, 0.6, 0.4 + 0.18 * 0.7071, 0.6 - 0.18 * 0.7071, 6)
        got, _ = feed(det, arm(0.4, 0.6) + diag + hold(0.53, 0.47, 20))
        self.assertEqual(got, [], f"斜扫不该触发，实际 {got}")
        self.assertGreaterEqual(det.rejected_count, 1, "应该记到一次『扫歪了』")
        got2, _ = feed(det, move(0.53, 0.47, 0.53, 0.27, 6) + hold(0.53, 0.27, 20), t0=5.0)
        self.assertEqual(got2, ["up"], f"随后正着扫应该触发，实际 {got2}")

    def test_slightly_slanted_sweep_still_works(self):
        """稍微斜一点（偏 20°，在 ±30° 容差内）必须照常判定。"""
        det = SweepDetector()
        ang = math.radians(90 + 20)
        p1 = (0.5 + 0.20 * math.cos(ang), 0.5 - 0.20 * math.sin(ang))
        got, _ = feed(det, arm(0.5, 0.5) + move(0.5, 0.5, *p1, 6) + hold(*p1, 20))
        self.assertEqual(got, ["up"], f"偏 20° 应仍算 up，实际 {got}")

    def test_state_machine_progression(self):
        """状态机：IDLE -> SETTLE ->（停稳）-> WATCH ->（触发）-> COOLDOWN。"""
        det = SweepDetector()
        self.assertEqual(det.state, IDLE)
        det.update(0.1, True, 0.5, 0.5, 1.0)
        self.assertEqual(det.state, SETTLE)
        t = 0.1
        for _ in range(12):                      # 停稳 400ms
            t += DT
            det.update(t, True, 0.5, 0.5, 1.0)
        self.assertEqual(det.state, WATCH)
        t += DT
        det.update(t, True, 0.5, 0.30, 1.0)      # 一扫就触发
        self.assertEqual(det.state, COOLDOWN)


if __name__ == "__main__":
    unittest.main(verbosity=2)
