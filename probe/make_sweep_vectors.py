"""生成跨语言测试向量：让 Kotlin 侧的单测回放与 Python 参考实现**完全相同**的输入。

为什么需要：Kotlin 版没法在这台机器上"跑起来验证逻辑"，但可以跑单元测试（纯 JVM）。
所以把 Python 侧验证过的场景导出，Kotlin 单测逐帧喂给 SweepDetector 并比对期望事件——
这样"移植有没有走样"就能自动发现，而不是靠肉眼看代码。

跑法：
    .venv\\Scripts\\python.exe -m probe.make_sweep_vectors
输出：
    android/app/src/test/resources/sweep_vectors.json     （给人看/调试）
    android/app/src/test/java/com/airgesture/sweep/SweepVectors.kt （给 Kotlin 单测回放）
"""
from __future__ import annotations

import json
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from probe.detector_ref import SweepDetector  # noqa: E402

OUT = os.path.join(ROOT, "android", "app", "src", "test", "resources", "sweep_vectors.json")
FPS = 30.0
DT = 1.0 / FPS

CASES: list[dict] = []


def move(x0, y0, x1, y1, n, together=1.0, present=True, extended=4):
    return [(present, x0 + (x1 - x0) * i / max(1, n - 1),
             y0 + (y1 - y0) * i / max(1, n - 1), together, extended) for i in range(n)]


def hold(x, y, n, together=1.0, present=True, extended=4):
    return [(present, x, y, together, extended) for _ in range(n)]


def arm(x=0.5, y=0.5, n=12, together=1.0, extended=4):
    """动作1：摆好起始姿势并停稳（让程序从 SETTLE 进入 WATCH）。

    12 帧 = 400ms > settle_ms(250ms)。**所有扫动序列都必须以它开头**，
    因为"手伸到位"这个动作本身也是一次位移，必须先被 SETTLE 吃掉。
    extended=4 是"四指都伸出来"（姿势闸门的默认要求）。
    """
    return hold(x, y, n, together=together, extended=extended)


def add(name: str, params: dict, frames, note: str = "") -> None:
    """跑一遍参考实现，把（输入帧, 参数, 期望输出）整包记下来。"""
    det = SweepDetector(**params)
    expected = []
    t = 0.0
    timeline = []
    for fr in frames:
        present, x, y, together = fr[0], fr[1], fr[2], fr[3]
        extended = fr[4] if len(fr) > 4 else 4      # 允许写 4 元组（默认四指都伸出）
        t += DT
        e = det.update(t, present, x, y, together, extended)
        timeline.append([round(t, 6), 1.0 if present else 0.0,
                         round(x, 6), round(y, 6), round(together, 4), float(extended)])
        if e:
            expected.append(e)
    # 参数从**构造出来的判定器**上回读，保证 Kotlin 侧拿到的是实际生效值（含默认值）
    effective = {
        "enter_thr": det.enter_thr,
        "dead_band": det.dead_band,
        "together_max": det.together_max,
        "cooldown_ms": det.cooldown_ms,
        "settle_ms": det.settle_ms,
        "quiet_speed": det.quiet_speed,
        "lost_ms": det.lost_ms,
        "max_sweep_ms": det.max_sweep_ms,
        "angle_offset": det.angle_offset,
        "axis_tol_deg": det.axis_tol_deg,
        "min_extended_fingers": det.min_extended_fingers,
    }
    CASES.append({"name": name, "note": note, "params": effective,
                  "frames": timeline, "expected": expected})


def ang_traj(deg, dist, n=6, x0=0.5, y0=0.5):
    """按指定方向角走 dist（画面高度比例）。角度是"上为正"的。"""
    a = math.radians(deg)
    return move(x0, y0, x0 + dist * math.cos(a), y0 - dist * math.sin(a), n)


# ---- 1. 四个方向 ----
for name, p1 in (("up", (0.5, 0.32)), ("down", (0.5, 0.68)),
                 ("left", (0.32, 0.5)), ("right", (0.68, 0.5))):
    add(f"four_directions_{name}", {}, arm(0.5, 0.5) + move(0.5, 0.5, *p1, 6) + hold(*p1, 30),
        f"四个方向：{name}")

# ---- 2. ★ SETTLE：伸手到位不能算扫动（用户实测的 bug）----
add("arrival_motion_no_trigger", {},
    move(0.5, 0.95, 0.5, 0.55, 6) + hold(0.5, 0.55, 30),
    "伸手到位（0.4 屏高）不能被当成扫动；停稳后就绪")

add("arrival_then_sweep", {},
    move(0.5, 0.95, 0.5, 0.55, 6) + hold(0.5, 0.55, 30)
    + move(0.5, 0.55, 0.5, 0.25, 6) + hold(0.5, 0.25, 20),
    "伸手到位后摆好姿势再扫，必须触发 up")

# 手一直不停 -> 永远进不了 WATCH（没有"动作1"就没有动作2）
add("continuous_motion_never_arms", {},
    [(True, 0.5, 0.9 - i * 0.02, 1.0) for i in range(120)],
    "持续移动（0.6/s，高于停稳阈值）不能触发也不能进入就绪")

# ---- 3b. ★ 冷静期从"触发那一瞬间"起算，定稿 1200ms（v0.17.9 由 1300 改） ----
# 触发后约 0.8s 就想反向再扫一次（姿势也已停稳）：仍在 1200ms 窗口内，必须被忽略。
add("cooldown_blocks_early_second_sweep", {},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.30, 6)      # 越过阈值 -> 触发 up（冷静期从此起算）
    + hold(0.5, 0.30, 18)                              # 到触发后约 0.6s
    + move(0.5, 0.30, 0.5, 0.60, 6)                    # 触发后约 0.7~0.9s：反向运动
    + hold(0.5, 0.60, 40),
    "定稿 1200ms：触发后约 0.8s 的反向扫动必须被完全忽略 -> 只触发一次")

# 同一段动作，只是等到冷静期过了再扫：必须正常触发（证明不是"卡死不再识别"）
# 时间账：触发 ≈ 第 0 帧；hold(0.30,30) 到 0.9s，再 hold 到 1.2s 已过冷静期，
#         之后重新停稳 250ms 进入 WATCH，反向扫动才触发。
add("cooldown_allows_sweep_after_expiry", {},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.30, 6) + hold(0.5, 0.30, 30)
    + hold(0.5, 0.30, 20)                              # 到触发后约 1.5s，冷静期已过
    + move(0.5, 0.30, 0.5, 0.62, 6) + hold(0.5, 0.62, 20),
    "过了 1200ms 冷静期并重新停稳后，第二次扫动必须触发（up, down）")

# ---- 3. 冷静期 = 清除上一轮的记忆 ----
# 触发后 200ms 就来一次又大又干净的反向运动（模拟归位），冷静期内必须完全忘掉
add("cooldown_forgets_previous_round", {},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.30, 6)
    + move(0.5, 0.30, 0.5, 0.72, 6) + hold(0.5, 0.72, 30),
    "冷静期内的大幅反向运动（归位）必须被完全忽略")

# 正常的"扫出去 + 收回来"，整段落在冷静期内
add("normal_return_within_cooldown", {"cooldown_ms": 600},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.32, 6)
    + move(0.5, 0.32, 0.5, 0.50, 12) + hold(0.5, 0.50, 30),
    "归位 400ms 落在 600ms 冷静期内 -> 只触发一次")

# 冷静期结束 + 重新摆好姿势之后必须能再扫
add("second_sweep_after_cooldown", {"cooldown_ms": 500},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.30, 6) + hold(0.5, 0.30, 30)
    + move(0.5, 0.30, 0.5, 0.70, 6) + hold(0.5, 0.70, 30),
    "冷静期+姿势锁定之后第二次扫动必须触发（期望 up, down）")

# 收手比冷静期还慢也不会误触发 —— 冷静期之后还有 SETTLE 接住
add("slow_return_caught_by_settle", {"cooldown_ms": 400},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.20, 6)
    + move(0.5, 0.20, 0.5, 0.60, 30) + hold(0.5, 0.60, 30),
    "1000ms 的慢收手：冷静期盖不住，但 SETTLE 接住了 -> 只触发一次")

# ---- 4. 举着不动不能重复触发 ----
add("hold_does_not_repeat", {},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.30, 6) + hold(0.5, 0.30, 90),
    "扫到底后继续举着，只能触发一次")

# ---- 5. 缓慢漂移不能触发（max_sweep_ms 保护）----
add("slow_drift_no_trigger", {"enter_thr": 0.10, "max_sweep_ms": 600},
    arm(0.4, 0.6) + move(0.4, 0.6, 0.4, 0.35, 90),
    "3 秒漂移 0.25（远超阈值）绝不能触发")

# ---- 6. 抖动不能触发 ----
rng = random.Random(42)
add("jitter_no_trigger", {"enter_thr": 0.10},
    [(True, 0.5 + rng.uniform(-0.012, 0.012), 0.5 + rng.uniform(-0.012, 0.012), 1.0)
     for _ in range(150)],
    "手举着不动的抖动不能触发")

# ---- 7. 手指张开时门控 ----
add("open_fingers_gated", {"together_max": 1.35},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.28, 6, together=1.8)
    + hold(0.5, 0.28, 15, together=1.8)
    + move(0.5, 0.28, 0.5, 0.06, 6, together=1.0) + hold(0.5, 0.06, 15),
    "张开手指的移动不算，并拢后同一只手必须能触发（期望 up）")

# ---- 7b. ★"四根手指"闸门（用户实测：一根手指也能触发）----
# 只伸一根手指：就算它停得再稳、扫得再干净，也**不能**触发任何滑动。
add("one_finger_never_arms", {},
    arm(0.5, 0.5, extended=1) + move(0.5, 0.5, 0.5, 0.28, 6, extended=1)
    + hold(0.5, 0.28, 30, extended=1),
    "单指（1/4 伸出）无论怎么扫都不能触发 —— 这是用户实测抓到的 bug")

# 两指/三指同理
add("two_fingers_never_arms", {},
    arm(0.5, 0.5, extended=2) + move(0.5, 0.5, 0.5, 0.28, 6, extended=2)
    + hold(0.5, 0.28, 30, extended=2),
    "两指（2/4）不能触发")

add("three_fingers_gated_at_min4", {"min_extended_fingers": 4},
    arm(0.5, 0.5, extended=3) + move(0.5, 0.5, 0.5, 0.28, 6, extended=3)
    + hold(0.5, 0.28, 30, extended=3),
    "把要求设到 4 根时，三指（3/4）不触发")

# 定稿默认就是 3 根：同一段三指动作在**默认参数**下必须触发
add("three_fingers_ok_by_default", {},
    arm(0.5, 0.5, extended=3) + move(0.5, 0.5, 0.5, 0.28, 6, extended=3)
    + hold(0.5, 0.28, 30, extended=3),
    "定稿默认（≥3 根）：三指的同一段动作就能触发 up")

# 同一段动作，把要求放宽到 3 根就应该能触发（证明设置项真的生效）
add("three_fingers_ok_when_min3", {"min_extended_fingers": 3},
    arm(0.5, 0.5, extended=3) + move(0.5, 0.5, 0.5, 0.28, 6, extended=3)
    + hold(0.5, 0.28, 30, extended=3),
    "闸门设为 3 根时，三指的同一段动作能触发 up")

# 先单指（不锁定）-> 再把四指伸出来停稳 -> 必须能正常触发
add("four_fingers_recover_after_one", {},
    arm(0.5, 0.5, extended=1) + arm(0.5, 0.5, extended=4)
    + move(0.5, 0.5, 0.5, 0.28, 6) + hold(0.5, 0.28, 30),
    "先单指（不锁定），改成四指停稳后必须能触发 up")

# ★ 闸门只在"锁定那一刻"看：扫动过程中手指弯一下（运动模糊很常见）不能打断
add("finger_curl_mid_sweep_still_triggers", {},
    arm(0.5, 0.5, extended=4)
    + move(0.5, 0.5, 0.5, 0.28, 6, extended=2) + hold(0.5, 0.28, 30, extended=2),
    "锁定后扫动中手指弯成 2/4，仍然必须触发 up（闸门只在锁定时判）")

# ---- 8. 手丢失后重新出现不能因位置跳变误触发 ----
add("hand_lost_resets_anchor", {"lost_ms": 300},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.44, 6)
    + hold(0.5, 0.44, 20, present=False) + hold(0.5, 0.20, 30),
    "手离开再出现，不能因锚点残留而误触发")

# ---- 9. 未达阈值 ----
add("below_threshold", {"enter_thr": 0.10},
    arm(0.5, 0.5) + move(0.5, 0.5, 0.5, 0.42, 6) + hold(0.5, 0.42, 30),
    "位移 0.08 < 阈值 0.10，不能触发")

# ---- 10. 朝向标定（支架角度 / 画面整体旋转）----
add("angle_offset_calibration", {"angle_offset": -60.0},
    arm(0.5, 0.5) + ang_traj(150.0, 0.16) + hold(0.5 - 0.16 * math.cos(math.radians(30)),
                                                 0.5 - 0.16 * math.sin(math.radians(30)), 20),
    "画面整体转 60° 时，-60° 偏移把 150° 掰回 up")

add("angle_offset_90_frame", {"angle_offset": -90.0},
    arm(0.5, 0.5) + ang_traj(180.0, 0.16) + hold(0.5 - 0.16, 0.5, 20),
    "画面转 90° 时：手指向上扫出现在 180°，-90° 偏移把它掰回 up")

# ---- 11. 只认正轴 ----
add("diagonal_rejected", {},
    arm(0.4, 0.6) + move(0.4, 0.6, 0.4 + 0.18 * 0.7071, 0.6 - 0.18 * 0.7071, 6)
    + hold(0.53, 0.47, 20)
    + move(0.53, 0.47, 0.53, 0.27, 6) + hold(0.53, 0.27, 20),
    "45° 斜扫不触发；随后正着向上扫触发 up")

add("slightly_slanted_ok", {},
    arm(0.5, 0.5) + ang_traj(110.0, 0.20)
    + hold(0.5 + 0.20 * math.cos(math.radians(110)),
           0.5 - 0.20 * math.sin(math.radians(110)), 20),
    "偏 20° 的向上扫仍应判成 up")

# ---- 11. 混合脚本：扫出去 -> 冷静期内赶紧归位 -> 停稳过冷静期 -> 再扫 ----
# 注意这条序列要按**新模型**来写：归位必须落在冷静期内（否则那半截归位就是新一轮扫动，
# 这正是"已知代价"那条测试描述的行为）。所以是"6 帧扫出去 + 10 帧收回来"。
center = (0.5, 0.5)
plan = [((0.50, 0.32), "up"), ((0.68, 0.50), "right"), ((0.50, 0.68), "down"),
        ((0.32, 0.50), "left"), ((0.50, 0.32), "up"), ((0.68, 0.50), "right")]
seq = arm(center[0], center[1])
for target, _tgt in plan:
    seq += move(center[0], center[1], target[0], target[1], 6)     # 动作2：扫出去（触发）
    seq += move(target[0], target[1], center[0], center[1], 10)    # 收手（落在冷静期内）
    seq += hold(center[0], center[1], 30)                          # 摆好姿势，等过冷静期+SETTLE
add("mixed_sequence", {"cooldown_ms": 500}, seq,
    "6 次扫动（含摆姿势/收手）：up/right/down/left/up/right")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as fh:
    json.dump({"fps": FPS, "cases": CASES}, fh, ensure_ascii=False, indent=1)

# ---- 同时产出 Kotlin 版向量（Kotlin 单测直接回放这批输入，不需要解析 JSON）----
KT = os.path.join(ROOT, "android", "app", "src", "test", "java", "com", "airgesture", "sweep",
                  "SweepVectors.kt")


def kt_num(v: float) -> str:
    return f"{v:.6f}"


kt: list[str] = []
kt.append("package com.airgesture.sweep")
kt.append("")
kt.append("// 本文件由 probe/make_sweep_vectors.py 自动生成，请勿手改。")
kt.append("// 数据源：probe/detector_ref.py（Python 参考实现，已通过单元测试）。")
kt.append("// 目的：让 Kotlin 侧回放与 Python 侧**完全相同**的输入，验证移植没有走样。")
kt.append("")
kt.append("object SweepVectors {")
kt.append("")
kt.append("    data class Case(")
kt.append("        val name: String,")
kt.append("        val note: String,")
kt.append("        val enterThr: Double,")
kt.append("        val deadBand: Double,")
kt.append("        val togetherMax: Double,")
kt.append("        val cooldownMs: Double,")
kt.append("        val settleMs: Double,")
kt.append("        val quietSpeed: Double,")
kt.append("        val lostMs: Double,")
kt.append("        val maxSweepMs: Double,")
kt.append("        val angleOffset: Double,")
kt.append("        val axisTolDeg: Double,")
kt.append("        val minExtendedFingers: Int,")
kt.append("        /** 每帧： [t(秒), present(1/0), x, y, together, extended(伸出的手指数)] */")
kt.append("        val frames: Array<DoubleArray>,")
kt.append("        val expected: List<String>,")
kt.append("    )")
kt.append("")
# ★ 为什么要拆成几个函数拼起来：所有向量合在一个 `val all = listOf(...)` 里会形成
#   一个巨大的 <clinit>，JVM 的单个方法上限是 64KB 字节码 —— 向量一多就报
#   "Method too large: SweepVectors.<clinit>"（加手指向量那次就撞上了）。
#   按"帧数预算"分组的普通函数各自成方法，就不会撞这个上限。
parts: list[list[dict]] = []
cur: list[dict] = []
budget = 0
for c in CASES:
    if cur and budget + len(c["frames"]) > 320:
        parts.append(cur)
        cur, budget = [], 0
    cur.append(c)
    budget += len(c["frames"])
if cur:
    parts.append(cur)


def emit_case(c: dict) -> list[str]:
    p = c["params"]
    out = ["        Case("]
    out.append(f'            name = "{c["name"]}",')
    out.append(f'            note = "{c["note"]}",')
    out.append(f'            enterThr = {kt_num(p["enter_thr"])},')
    out.append(f'            deadBand = {kt_num(p["dead_band"])},')
    out.append(f'            togetherMax = {kt_num(p["together_max"])},')
    out.append(f'            cooldownMs = {kt_num(p["cooldown_ms"])},')
    out.append(f'            settleMs = {kt_num(p["settle_ms"])},')
    out.append(f'            quietSpeed = {kt_num(p["quiet_speed"])},')
    out.append(f'            lostMs = {kt_num(p["lost_ms"])},')
    out.append(f'            maxSweepMs = {kt_num(p["max_sweep_ms"])},')
    out.append(f'            angleOffset = {kt_num(p["angle_offset"])},')
    out.append(f'            axisTolDeg = {kt_num(p["axis_tol_deg"])},')
    out.append(f'            minExtendedFingers = {int(p["min_extended_fingers"])},')
    out.append("            frames = arrayOf(")
    for fr in c["frames"]:
        out.append("                doubleArrayOf(" +
                   ", ".join(kt_num(float(v)) for v in fr) + "),")
    out.append("            ),")
    if c["expected"]:
        exp = ", ".join(f'"{d}"' for d in c["expected"])
        out.append(f"            expected = listOf({exp}),")
    else:
        out.append("            expected = emptyList(),")
    out.append("        ),")
    return out


for i, part in enumerate(parts, start=1):
    kt.append(f"    // 第 {i} 组：" + " / ".join(c["name"] for c in part))
    kt.append(f"    private fun part{i}(): List<Case> = listOf(")
    for c in part:
        kt.extend(emit_case(c))
    kt.append("    )")
    kt.append("")
kt.append("    val all: List<Case> = " +
          " + ".join(f"part{i}()" for i in range(1, len(parts) + 1)))
kt.append("}")
kt.append("")

with open(KT, "w", encoding="utf-8") as fh:
    fh.write("\n".join(kt))

print(f"[ok] 生成 {len(CASES)} 个测试向量")
print(f"     JSON   -> {OUT}")
print(f"     Kotlin -> {KT}")
for c in CASES:
    print(f"     {c['name']:<32} {len(c['frames']):>4} 帧  期望 {c['expected']}")
