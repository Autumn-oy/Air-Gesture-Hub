"""四指扫动判定器 —— 参考实现（纯逻辑，不依赖摄像头/安卓/第三方库）。

================================================================================
这个文件是干什么的
================================================================================
手机上的 Kotlin 版没法在这台机器上跑，所以把**最终判定逻辑**先在这里实现一遍、
用单元测试把边界情况钉死，再原样翻译成 `SweepDetector.kt`。
`probe/make_sweep_vectors.py` 会用这里的实现生成测试向量，Kotlin 侧回放同一批向量，
保证两端行为一致。

================================================================================
判定逻辑（用户确认的规格："两个动作触发一次判定"）
================================================================================
    动作1  摆好起始姿势（把手伸到位、四指并拢停住）
    弹窗「请滑动」                    <- 动作1做完的出发信号
    动作2  扫动（姿势1 → 姿势2）       <- 结合两个姿势的方向 -> 注入一次滑动
    冷静期                            <- 收手/归位整段被忘掉
    新一轮

    IDLE ──检测到手──► SETTLE
    SETTLE（★姿势锁定）:
        锚点一路跟手，**完全不累积位移**；
        手停稳(quiet_speed 以下) 持续 settle_ms **且四指伸出根数够** -> WATCH
        手丢失 > lost_ms -> IDLE
    WATCH:
        四指不并拢            -> 锚点跟手
        位移 < dead_band       -> 锚点跟手
        累积超过 max_sweep_ms  -> 重新锚点（慢漂移保护）
        位移 >= enter_thr:
            偏离正轴超过 axis_tol_deg -> 拒绝（rejected_count++），重新锚点
            否则                      -> 发事件 -> COOLDOWN
    COOLDOWN（冷静期）:
        全程跟手：上一轮的动作一点都不累积，**记忆就此清空**
        过了 cooldown_ms -> SETTLE（新一轮同样要先"摆好姿势"）

    ★ SETTLE 解决的是这个真 bug：用户把手伸上来/摆姿势本身也是一次位移，
      没有 SETTLE 的话，**"伸手到位"会被当成一次扫动**，手往哪伸就往哪滑一下。
      SETTLE 要求"手先停稳"才进入测量状态，这段到位动作就自然被忽略了。

    ★ 冷静期 与 SETTLE 是互补的两件事，都需要：
      · 冷静期盖住"这一轮自己的动作"——用户在顶点会停住(实测约 0.7s)再收手，
        只靠停稳判定会在顶点就重新武装，接着的收手就被当成反向扫动。
      · SETTLE 盖住"不属于任何一次扫动的移动"——伸手到位、换中立位、整理姿势。

    ★ "四指伸出根数"闸门（min_extended_fingers）解决的是另一个真 bug：
      together_max 只量"四个指尖靠得近不近"，**不问那四根手指有没有真的伸出来**。
      MediaPipe 在你只伸一根手指时也会输出 21 个关键点（其余手指是猜的，通常瘫在掌心），
      于是单指的四个指尖依然集中、并拢度照样过关 —— 一根手指就能触发滑动。
      闸门放在 SETTLE 的锁定条件里（而不是 WATCH 里逐帧判）：摆姿势时手是静止的，
      关键点干净判得准；扫动中手指弯一点很正常，逐帧判会把真正的扫动打断。

参数（★ v0.16.0 起**已定稿**：下面的默认值就是 App 里生效的值）：
    enter_thr      触发位移阈值（画面高度比例）—— 0.14
    dead_band      死区：位移小于它就认为"没在动"，锚点持续刷新
    together_max   四指并拢度上限，超过就算"手指张开"、不参与判定 —— 定稿 2.0（等于不筛，留字段）
    cooldown_ms    ★冷静期：触发后这么久内不识别任何动作 —— 定稿 1200，**从触发那一帧起算**
    settle_ms      ★姿势锁定：手要停稳这么久，才认为"动作1完成"、开始测动作2
    quiet_speed    多慢算"停稳"（画面高度比例/秒）
    lost_ms        手丢失多久算离开（回到 IDLE）
    max_sweep_ms   位移必须在这么久之内完成，否则视为"慢漂移"并重新锚点
    angle_offset   方向角整体偏移（修画面整体旋转）—— 手机在支架上被转了 90° 时用
    axis_tol_deg   只认正轴：偏离上/下/左/右超过这个角度不判定（"扫歪了"）
    min_extended_fingers  ★锁定时四指里至少要伸出这么多根 —— 定稿 3（单指/双指挡住）
"""
from __future__ import annotations

import math

IDLE, SETTLE, WATCH, COOLDOWN = "IDLE", "SETTLE", "WATCH", "COOLDOWN"
DIRECTIONS = ("up", "down", "left", "right")

MAX_EVENTS = 200      # 诊断日志上限，不该无限增长


def away_from_axis_deg(angle_deg: float) -> float:
    """方向角离最近的正轴（0/90/180/270）差多少度。0 = 正好，45 = 完全斜着。"""
    a = angle_deg % 90.0
    return min(a, 90.0 - a)


def quadrant(angle_deg: float) -> str:
    """方向角 -> 四方向。0°=画面右, 90°=上, 180°=左, 270°=下（角度是"上为正"的）。"""
    a = angle_deg % 360.0
    if a < 45.0 or a >= 315.0:
        return "right"
    if a < 135.0:
        return "up"
    if a < 225.0:
        return "left"
    return "down"


class SweepDetector:
    def __init__(self,
                 enter_thr: float = 0.14,
                 dead_band: float = 0.02,
                 together_max: float = 2.0,
                 cooldown_ms: float = 1200.0,
                 settle_ms: float = 250.0,
                 quiet_speed: float = 0.35,
                 lost_ms: float = 300.0,
                 max_sweep_ms: float = 900.0,
                 angle_offset: float = 0.0,
                 axis_tol_deg: float = 30.0,
                 min_extended_fingers: int = 3) -> None:
        self.enter_thr = float(enter_thr)
        self.dead_band = float(dead_band)
        self.together_max = float(together_max)
        self.cooldown_ms = float(cooldown_ms)
        self.settle_ms = float(settle_ms)
        self.quiet_speed = float(quiet_speed)
        self.lost_ms = float(lost_ms)
        self.max_sweep_ms = float(max_sweep_ms)
        self.angle_offset = float(angle_offset)
        self.axis_tol_deg = float(axis_tol_deg)
        self.min_extended_fingers = int(min_extended_fingers)

        self.state = IDLE
        self.events: list[tuple[float, str]] = []
        self.rejected_count = 0
        self.armed_count = 0          # "姿势锁定"的次数（UI 据此弹「请滑动」）
        self._anchor: tuple[float, float] | None = None
        self._anchor_t = 0.0
        self._last_seen = -1e9
        self._cooldown_t = 0.0
        self._quiet_since: float | None = None
        self._prev: tuple[float, float, float] | None = None

        # 调试用（悬浮窗会显示这几个数）
        self.dbg_disp = 0.0
        self.dbg_angle = float("nan")
        self.dbg_gated = False
        self.dbg_speed = 0.0
        self.dbg_extended = 0
        self.dbg_pose_ok = False
        # 冷静期还剩多少毫秒（不在冷静期时为 0）——把"触发那一瞬间就进入冷静期"变得可见
        self.dbg_cooldown_left_ms = 0.0

    # -- 内部 ---------------------------------------------------------------
    def _reanchor(self, t: float, x: float, y: float) -> None:
        self._anchor = (x, y)
        self._anchor_t = t

    def _reset(self) -> None:
        self.state = IDLE
        self._anchor = None
        self._quiet_since = None
        self.dbg_disp = 0.0
        self.dbg_angle = float("nan")
        self.dbg_cooldown_left_ms = 0.0

    # -- 主入口 -------------------------------------------------------------
    def update(self, t: float, present: bool, x: float = 0.0, y: float = 0.0,
               together: float = 1.0, extended: int = 4) -> str | None:
        """喂一帧，返回触发方向（'up'/'down'/'left'/'right'）或 None。

        t 单位秒（单调递增）；x/y 是四指指尖质心（单位＝画面高度比例）。
        extended 是四指里伸出的根数（0~4，见 HandFeatures.kt / gestures.py 的 finger_state）。
        """
        # 速度估计（SETTLE 的"停稳"判据要用）
        if present and self._prev is not None:
            dt = t - self._prev[2]
            if dt > 1e-6:
                self.dbg_speed = math.hypot(x - self._prev[0], y - self._prev[1]) / dt
        elif not present:
            self.dbg_speed = 0.0
        self._prev = (x, y, t) if present else None

        if not present:
            if self.state != IDLE and (t - self._last_seen) * 1000.0 > self.lost_ms:
                self._reset()          # 手离开 -> 回 IDLE，绝不留会让屏幕乱滚的状态
            return None

        self._last_seen = t
        self.dbg_extended = int(extended)
        self.dbg_pose_ok = int(extended) >= self.min_extended_fingers
        if self.state == IDLE:
            self.state = SETTLE
            self._quiet_since = None

        # ---- SETTLE：等"动作1（摆好姿势）"完成——手停稳 + 四指伸出来 ----
        if self.state == SETTLE:
            self._reanchor(t, x, y)          # 全程跟手：到位动作一点都不累积
            if self.dbg_speed < self.quiet_speed:
                if self._quiet_since is None:
                    self._quiet_since = t
                # ★"手指根数"闸门放在**这里**，而不是 WATCH 里逐帧判：
                #   摆姿势时手是静止的，关键点干净，判得准；扫动中手指弯一点很正常
                #   （还有运动模糊），逐帧判会反复重置锚点，变成"怎么扫都不触发"。
                if self.dbg_pose_ok and (t - self._quiet_since) * 1000.0 >= self.settle_ms:
                    self.state = WATCH       # 姿势1锁定，可以开始测动作2了
                    self._quiet_since = None
                    self.armed_count += 1
            else:
                self._quiet_since = None
            return None

        # ---- COOLDOWN（冷静期）：全程跟手，上一轮的动作一点都不累积 ----
        if self.state == COOLDOWN:
            self._reanchor(t, x, y)
            left = self.cooldown_ms - (t - self._cooldown_t) * 1000.0
            self.dbg_cooldown_left_ms = max(0.0, left)
            if left <= 0.0:
                self.state = SETTLE          # 新一轮同样要先"摆好姿势"
                self._quiet_since = None
                self.dbg_cooldown_left_ms = 0.0
            return None

        # ---- WATCH：测量动作2 ----
        self.dbg_gated = bool(together > self.together_max)
        if self.dbg_gated:
            self._reanchor(t, x, y)
            return None

        if self._anchor is None:
            self._reanchor(t, x, y)
            return None

        dx = x - self._anchor[0]
        dy = y - self._anchor[1]
        self.dbg_disp = math.hypot(dx, dy)
        self.dbg_angle = math.degrees(math.atan2(-dy, dx)) % 360.0

        # 死区：静止时持续刷新锚点（这是"停顿后不漏扫"的关键）
        if self.dbg_disp < self.dead_band:
            self._reanchor(t, x, y)
            return None

        # 慢漂移保护：位移必须在 max_sweep_ms 之内完成，否则重新锚点
        if (t - self._anchor_t) * 1000.0 > self.max_sweep_ms:
            self._reanchor(t, x, y)
            return None

        if self.dbg_disp >= self.enter_thr:
            effective = (self.dbg_angle + self.angle_offset) % 360.0
            # 只认正轴：斜着扫不判定，重新锚点等一次干净的扫动
            if away_from_axis_deg(effective) > self.axis_tol_deg:
                self.rejected_count += 1
                self._reanchor(t, x, y)
                return None
            direction = quadrant(effective)
            self.events.append((t, direction))
            if len(self.events) > MAX_EVENTS:
                del self.events[:-MAX_EVENTS]
            # ★★ 冷静期从**这一刻**开始 —— 就是"位移越过阈值、决定要滑动"的那一帧，
            #    不是"合成滑动播放完"之后。所以本帧之后的所有动作（扫动的后半截、
            #    顶点停顿、收手归位）全落在这个窗口里，一次都不会被累积。
            self.state = COOLDOWN
            self._cooldown_t = t
            self.dbg_cooldown_left_ms = self.cooldown_ms
            self._reanchor(t, x, y)
            return direction

        return None
