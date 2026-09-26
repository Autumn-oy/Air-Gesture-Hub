package com.airgesture.sweep

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 四指扫动判定器 —— Kotlin 版，与 `probe/detector_ref.py` **逐行对应**。
 *
 * 这个类**刻意不 import 任何 Android API**，所以能跑纯 JVM 单元测试；
 * Python 侧的单测 + 生成的测试向量会在这里回放，保证两端行为一致。
 *
 * ==================== 用户确认的规格："两个动作触发一次判定" ====================
 * ```
 * 动作1  摆好起始姿势（手伸到位、四指并拢停住）
 * 弹窗「请滑动」                    <- 动作1做完的出发信号
 * 动作2  扫动（姿势1 → 姿势2）       -> 结合两个姿势的方向 -> 注入一次滑动
 * 冷静期                            -> 收手/归位整段被忘掉
 * 新一轮
 *
 * IDLE ──检测到手──► SETTLE
 * SETTLE（★姿势锁定）:
 *     锚点一路跟手，**完全不累积位移**；
 *     手停稳(quietSpeed 以下) 持续 settleMs -> WATCH（姿势1锁定，可以开始测动作2了）
 *     手丢失 > lostMs -> IDLE
 * WATCH:
 *     四指不并拢            -> 锚点跟手
 *     位移 < deadBand       -> 锚点跟手
 *     累积超过 maxSweepMs   -> 重新锚点（慢漂移保护）
 *     位移 >= enterThr:
 *         偏离正轴超过 axisTolDeg -> 拒绝（rejectedCount++），重新锚点
 *         否则                    -> 发事件 -> COOLDOWN
 * COOLDOWN（冷静期）:
 *     全程跟手：上一轮的动作一点都不累积，**记忆就此清空**
 *     过了 cooldownMs -> SETTLE（新一轮同样要先"摆好姿势"）
 * ```
 *
 * ★ **SETTLE 解决的是一个实测 bug**：用户把手伸上来/摆姿势本身也是一次位移，
 *   没有 SETTLE 的话"伸手到位"会被当成一次扫动 —— 手往哪伸就往哪滑一下。
 *   SETTLE 要求"手先停稳"才进入测量状态，到位动作自然被忽略。
 *
 * ★ **冷静期与 SETTLE 是互补的，都需要**：
 *   · 冷静期盖住"这一轮自己的动作"——用户在顶点会停住(实测约 0.7s)再收手，
 *     只靠停稳判定会在顶点就重新武装，接着的收手就被当成反向扫动。
 *   · SETTLE 盖住"不属于任何一次扫动的移动"——伸手到位、换中立位、整理姿势。
 */
class SweepDetector(
    /** 触发位移阈值（画面高度比例）。定稿 0.14。 */
    var enterThr: Double = 0.14,
    var deadBand: Double = 0.02,
    /** 四指并拢度上限。定稿 **2.0** = 不再用并拢度筛人（张开手掌约 1.4~1.8）。 */
    var togetherMax: Double = 2.0,
    /**
     * ★冷静期：触发一次滑动后，这么久之内**不识别任何动作**。
     * 语义是"清除上一轮的记忆"——上一轮最后那个归位动作永远不会被当成手势。
     * ★计时起点是**触发那一帧**，不是"合成滑动播放完"之后。
     *
     * 默认值与 `probe/detector_ref.py` 保持一致：**1000ms**（v2.3.2 定稿；沿革 1300→1200→1100→1000）。
     * 生产值由 `Prefs.DEFAULT_COOLDOWN_MS` 提供，这里只是让两套实现的默认值不打架。
     */
    var cooldownMs: Double = 1000.0,
    /** ★姿势锁定：手要停稳这么久，才认为"动作1（摆好姿势）完成"、开始测动作2。 */
    var settleMs: Double = 250.0,
    /** 多慢算"停稳"（画面高度比例/秒）。 */
    var quietSpeed: Double = 0.35,
    var lostMs: Double = 300.0,
    var maxSweepMs: Double = 900.0,
    var angleOffsetDeg: Double = 0.0,
    /**
     * 只认正轴：方向角偏离上/下/左/右超过这个角度就不判定（视为"扫歪了"）。
     * 需求是"只判定上下左右，不要斜的"——不拒绝的话，斜扫会被硬塞进某个轴，用户会觉得方向乱飘。
     */
    var axisTolDeg: Double = 30.0,
    /**
     * ★"四根手指"闸门：动作1（摆好姿势）锁定时，四指里至少要伸出这么多根。
     *
     * 为什么要有它：并拢度（togetherMax）只量"四个指尖靠得近不近"，
     * **不问那四根手指有没有真的伸出来**。MediaPipe 在你只伸一根手指时也会输出
     * 21 个关键点（其余手指是猜的，通常瘫在掌心附近），于是单指的四个指尖依然集中、
     * 并拢度照样过关 —— 一根手指就能触发滑动。这个闸门补的就是这个缺口。
     *
     * 默认 3（定稿）：单指/双指必须挡住才是这个闸门要解决的问题；四根全伸直对少数手型偏严。
     */
    var minExtendedFingers: Int = 3,
) {
    enum class State { IDLE, SETTLE, WATCH, COOLDOWN }

    var state: State = State.IDLE
        private set

    /** 触发的 (时间, 方向) 序列，便于测试和诊断。有上限，不会无限增长。 */
    val events = mutableListOf<Pair<Double, String>>()

    private var anchorX = 0.0
    private var anchorY = 0.0
    private var hasAnchor = false
    private var anchorT = 0.0
    private var lastSeen = -1e9
    private var cooldownT = 0.0
    private var quietSince = Double.NaN

    private var prevX = 0.0
    private var prevY = 0.0
    private var prevT = 0.0
    private var hasPrev = false

    // 调试用：悬浮窗会显示这几个数
    var dbgDisp = 0.0
        private set
    var dbgAngle = Double.NaN
        private set
    var dbgGated = false
        private set
    var dbgSpeed = 0.0
        private set
    /** 最近一帧四指伸出的根数（悬浮窗显示，用来判断"是不是手指没伸出来"）。 */
    var dbgExtended = 0
        private set
    /** 姿势闸门是否通过（四指根数够）。false 时"手停稳也不会锁定"。 */
    var dbgPoseOk = false
        private set
    /**
     * 冷静期还剩多少毫秒（不在冷静期时为 0）。
     *
     * ★ 存在的意义：把"**触发那一瞬间**就进入冷静期"这件事在真机上**看得见**。
     *   计时起点是判定触发的那一帧（`state = COOLDOWN`），不是"合成滑动播放完"之后 ——
     *   悬浮窗上这个数字从 1500 开始往下掉，就是最好的证明。
     */
    var dbgCooldownLeftMs = 0.0
        private set
    /** 累计"扫歪了"的次数（UI 据此提示）。 */
    var rejectedCount = 0
        private set
    /** 累计"姿势锁定"的次数（UI 据此弹「请滑动」）。 */
    var armedCount = 0
        private set

    private fun reanchor(t: Double, x: Double, y: Double) {
        anchorX = x
        anchorY = y
        anchorT = t
        hasAnchor = true
    }

    private fun reset() {
        state = State.IDLE
        hasAnchor = false
        quietSince = Double.NaN
        dbgDisp = 0.0
        dbgAngle = Double.NaN
        dbgCooldownLeftMs = 0.0
    }

    /**
     * 喂一帧。返回 'up'/'down'/'left'/'right' 或 null。
     * [t] 单位秒且单调递增；[x]/[y] 是四指指尖质心（单位＝画面高度比例，x 已按宽高比折算）；
     * [together] 是四指并拢度；[extended] 是四指里伸出的根数（0~4，见 HandFeatures）。
     */
    fun update(t: Double, present: Boolean, x: Double, y: Double, together: Double,
               extended: Int = 4): String? {
        // 速度估计（SETTLE 的"停稳"判据要用）
        if (present && hasPrev) {
            val dt = t - prevT
            if (dt > 1e-6) dbgSpeed = hypot(x - prevX, y - prevY) / dt
        } else if (!present) {
            dbgSpeed = 0.0
        }
        if (present) {
            prevX = x
            prevY = y
            prevT = t
            hasPrev = true
        } else {
            hasPrev = false
        }

        if (!present) {
            if (state != State.IDLE && (t - lastSeen) * 1000.0 > lostMs) {
                reset()   // 手离开 -> 回 IDLE，绝不留一个会让屏幕乱滚的状态
            }
            return null
        }

        lastSeen = t
        dbgExtended = extended
        dbgPoseOk = extended >= minExtendedFingers
        if (state == State.IDLE) {
            state = State.SETTLE
            quietSince = Double.NaN
        }

        // ---- SETTLE：等"动作1（摆好姿势）"完成——手停稳 + 四指伸出来 ----
        if (state == State.SETTLE) {
            reanchor(t, x, y)          // 全程跟手：到位动作一点都不累积
            if (dbgSpeed < quietSpeed) {
                if (quietSince.isNaN()) quietSince = t
                // ★"手指根数"闸门放在**这里**，而不是 WATCH 里逐帧判：
                //   · 摆姿势时手是静止的，关键点干净，判得准；
                //   · 扫动过程中手指弯一点很正常（还有运动模糊），逐帧判会把真正的扫动打断、
                //     锚点被反复重置，结果是"怎么扫都不触发"。
                //   这正好对上用户的两段式流程：动作1 = 摆好四指并拢的姿势并停稳（锁定），
                //   动作2 = 扫动（只量方向）。
                if (dbgPoseOk && (t - quietSince) * 1000.0 >= settleMs) {
                    state = State.WATCH   // 姿势1锁定，可以开始测动作2了
                    quietSince = Double.NaN
                    armedCount++
                }
            } else {
                quietSince = Double.NaN
            }
            return null
        }

        // ---- COOLDOWN（冷静期）：全程跟手，上一轮的动作一点都不累积 ----
        if (state == State.COOLDOWN) {
            reanchor(t, x, y)
            val left = cooldownMs - (t - cooldownT) * 1000.0
            dbgCooldownLeftMs = left.coerceAtLeast(0.0)
            if (left <= 0.0) {
                state = State.SETTLE   // 新一轮同样要先"摆好姿势"
                quietSince = Double.NaN
                dbgCooldownLeftMs = 0.0
            }
            return null
        }

        // ---- WATCH：测量动作2 ----
        dbgGated = together > togetherMax
        if (dbgGated) {
            reanchor(t, x, y)
            return null
        }

        if (!hasAnchor) {
            reanchor(t, x, y)
            return null
        }

        val dx = x - anchorX
        val dy = y - anchorY
        dbgDisp = hypot(dx, dy)
        dbgAngle = normalize360(Math.toDegrees(atan2(-dy, dx)))

        // 死区：没在动就持续刷新锚点（这是"停顿后不漏扫"的关键）
        if (dbgDisp < deadBand) {
            reanchor(t, x, y)
            return null
        }

        // 慢漂移保护：位移必须在 maxSweepMs 之内完成，否则重新锚点
        if ((t - anchorT) * 1000.0 > maxSweepMs) {
            reanchor(t, x, y)
            return null
        }

        if (dbgDisp >= enterThr) {
            val effective = normalize360(dbgAngle + angleOffsetDeg)
            // 只认正轴：斜着扫不判定，重新锚点等一次干净的扫动
            if (awayFromAxisDeg(effective) > axisTolDeg) {
                rejectedCount++
                reanchor(t, x, y)
                return null
            }
            val dir = quadrant(effective)
            events.add(t to dir)
            if (events.size > MAX_EVENTS) {
                repeat(events.size - MAX_EVENTS) { events.removeAt(0) }
            }
            // ★★ 冷静期从**这一刻**开始 —— 就是"位移越过阈值、决定要滑动"的那一帧，
            //    不是"合成滑动播放完"之后。所以本帧之后的所有动作（扫动的后半截、
            //    顶点停顿、收手归位）全都落在这个窗口里，一次都不会被累积。
            state = State.COOLDOWN
            cooldownT = t
            dbgCooldownLeftMs = cooldownMs
            reanchor(t, x, y)
            return dir
        }

        return null
    }

    companion object {
        val DIRECTIONS = listOf("up", "down", "left", "right")

        /** 诊断用事件日志的上限（防止无限增长）。 */
        private const val MAX_EVENTS = 200

        fun normalize360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

        /** 方向角离最近的正轴（0/90/180/270）差多少度。0 = 正好，45 = 完全斜着。 */
        fun awayFromAxisDeg(angleDeg: Double): Double {
            val a = normalize360(angleDeg) % 90.0
            return minOf(a, 90.0 - a)
        }

        /** 方向角 -> 四方向。0°=画面右, 90°=上, 180°=左, 270°=下（角度是"上为正"的）。 */
        fun quadrant(angleDeg: Double): String {
            val a = normalize360(angleDeg)
            return when {
                a < 45.0 || a >= 315.0 -> "right"
                a < 135.0 -> "up"
                a < 225.0 -> "left"
                else -> "down"
            }
        }
    }
}
