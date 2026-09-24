package com.airgesture.sweep

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 从 MediaPipe 的 21 个手部关键点里算出判定需要的两个量。
 *
 * 刻意**不依赖 MediaPipe 的类型**（只收 FloatArray），这样它也能跑纯 JVM 单测；
 * MediaPipe 结果的转换放在服务里做。
 *
 * 21 点编号（MediaPipe 约定）：
 *   0 手腕 / 1-4 拇指 / 5-8 食指 / 9-12 中指 / 13-16 无名指 / 17-20 小指
 *   每根手指：MCP(根) -> PIP -> DIP -> TIP(尖)
 */
object HandFeatures {
    /** 四指指尖。 */
    val FOUR_TIPS = intArrayOf(8, 12, 16, 20)

    /** 四指掌指关节（根部）。 */
    val FOUR_MCPS = intArrayOf(5, 9, 13, 17)

    /** 构成手掌的五个点。 */
    val PALM = intArrayOf(0, 5, 9, 13, 17)

    /**
     * 四指指尖质心，返回 [x, y]，**单位＝画面高度的比例**。
     *
     * x 之所以要乘 aspect：MediaPipe 的 x 按画面宽度归一化、y 按高度归一化，
     * 不折算的话 4:3 画面里"横向 0.1"和"纵向 0.1"代表不同的实际距离，
     * 阈值和方向角都会歪。这一条在 Windows 版踩过同类坑（坐标系没对齐就全错）。
     */
    fun ftipCentroid(x: FloatArray, y: FloatArray, aspect: Double): DoubleArray {
        var sx = 0.0
        var sy = 0.0
        for (i in FOUR_TIPS) {
            sx += x[i] * aspect
            sy += y[i].toDouble()
        }
        val n = FOUR_TIPS.size
        return doubleArrayOf(sx / n, sy / n)
    }

    /** 掌心中心，单位同上（标定与调试用）。 */
    fun palmCenter(x: FloatArray, y: FloatArray, aspect: Double): DoubleArray {
        var sx = 0.0
        var sy = 0.0
        for (i in PALM) {
            sx += x[i] * aspect
            sy += y[i].toDouble()
        }
        val n = PALM.size
        return doubleArrayOf(sx / n, sy / n)
    }

    /**
     * 四指并拢度：指尖横向张角 / 掌指关节横向张角。
     *
     * 掌指关节间距由骨骼决定是固定的；手指张开时指尖会散开，并拢时回到与 MCP 相当。
     *   ≈0.8~1.1 = 四指并拢（本项目要求的手型）
     *   >1.4     = 手指分开（张开手掌）
     * 这个比值与手部旋转无关（横向是相对手指方向取的垂直轴），所以很稳。
     *
     * 与 Windows 版 `gesture_mouse/gestures.py: fingers_together_ratio` **同一算法**，
     * 直接用原始归一化坐标（不做 aspect 折算），保持两端数值可比。
     */
    fun togetherRatio(x: FloatArray, y: FloatArray): Double {
        var tipX = 0.0
        var tipY = 0.0
        var mcpX = 0.0
        var mcpY = 0.0
        for (i in FOUR_TIPS) {
            tipX += x[i]
            tipY += y[i]
        }
        for (i in FOUR_MCPS) {
            mcpX += x[i]
            mcpY += y[i]
        }
        val n = FOUR_TIPS.size.toDouble()
        val dx = tipX / n - mcpX / n
        val dy = tipY / n - mcpY / n
        val len = hypot(dx, dy)
        if (len < 1e-9) return 1.0
        val px = -dy / len
        val py = dx / len

        var tipMin = Double.MAX_VALUE
        var tipMax = -Double.MAX_VALUE
        for (i in FOUR_TIPS) {
            val p = x[i] * px + y[i] * py
            if (p < tipMin) tipMin = p
            if (p > tipMax) tipMax = p
        }
        var mcpMin = Double.MAX_VALUE
        var mcpMax = -Double.MAX_VALUE
        for (i in FOUR_MCPS) {
            val p = x[i] * px + y[i] * py
            if (p < mcpMin) mcpMin = p
            if (p > mcpMax) mcpMax = p
        }
        val mcpWidth = mcpMax - mcpMin
        if (mcpWidth < 1e-6) return 1.0
        return (tipMax - tipMin) / mcpWidth
    }

    /** 手大致在画面内（避免只露半个手时质心乱跳）。 */
    fun handInsideFrame(x: FloatArray, y: FloatArray, margin: Double = 0.02): Boolean {
        for (i in 0 until x.size) {
            if (x[i] < -margin || x[i] > 1.0 + margin) return false
            if (y[i] < -margin || y[i] > 1.0 + margin) return false
        }
        return true
    }

    // ==================================================================
    // 手指伸展判定 —— "四根手指"
    // ==================================================================
    //
    // ★ 为什么必须有这一块（用户实测抓出来的 bug）：
    //   `togetherRatio` 只量"四个指尖靠得近不近"，**完全不问那四根手指有没有伸出来**。
    //   而 MediaPipe 即使你只伸一根手指，也照样输出 21 个关键点 —— 其余手指是**猜**出来的
    //   （通常瘫在掌心附近）。于是"单指"的四个指尖投影依然很集中，并拢度照样过关，
    //   一根手指就能触发滑动。这是"并拢度"这个判据本身的信息缺口，只能靠新判据补。
    //
    // 算法与 Windows 版 `gesture_mouse/gestures.py` 的 `finger_state()` / `is_extended()`
    // **同一套**（含阈值默认值），那边是用真实手部数据标定过的，见 tests/test_real_hand_data.py：
    //     握拳     食指 pip≈68°  reach≈0.6   <- 应判"曲"
    //     指向镜头  食指 pip≈156° reach≈1.2   <- 应判"伸"
    //     比 V     食指 pip≈161° reach≈1.4
    //     张开     食指 pip≈175° reach≈1.4
    // 两类之间差 60~90°，阈值取在中间，容错很大。

    /** 每根手指的 (MCP 根, PIP 近端指节, DIP 远端指节, TIP 指尖) 编号。 */
    val FINGER_JOINTS: List<Pair<String, IntArray>> = listOf(
        "index" to intArrayOf(5, 6, 7, 8),
        "middle" to intArrayOf(9, 10, 11, 12),
        "ring" to intArrayOf(13, 14, 15, 16),
        "pinky" to intArrayOf(17, 18, 19, 20),
    )

    /**
     * 伸展判定的阈值。默认值直接沿用 Windows 版 `config.json` 里实测标定的那三个
     * （`pip_min_deg` / `dip_min_deg` / `reach_margin`），两端一致才好互相印证。
     */
    data class ExtendParams(
        val pipMinDeg: Double = 135.0,
        val dipMinDeg: Double = 115.0,
        val reachMargin: Double = 1.1,
    )

    /** 单根手指的判定结果（角度都是**关节内角**，伸直 = 180°）。 */
    data class FingerState(
        val name: String,
        val extended: Boolean,
        val pipAngle: Double,
        val dipAngle: Double,
        val reachRatio: Double,
    ) {
        /** 给设置页/日志看的一行，例如 `index 伸 pip=172 dip=168 reach=1.42`。 */
        fun brief(): String = "$name ${if (extended) "伸" else "曲"} " +
            "pip=${pipAngle.toInt()} dip=${dipAngle.toInt()} reach=${"%.2f".format(reachRatio)}"
    }

    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-9 || nb < 1e-9) return 0.0
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private fun sub(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun dist(a: DoubleArray, b: DoubleArray): Double =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) +
            (a[2] - b[2]) * (a[2] - b[2]))

    /**
     * 判断一根手指是否伸直。三个**互相独立**的证据同时成立才算"伸"：
     *   1. PIP 内角 —— 最主要判据（手指弯了这里会明显变小）
     *   2. DIP 内角 —— 辅助
     *   3. 距离比 —— 伸直时"指尖到手腕"必然大于"近端指节到手腕"（折回来时反向）
     *
     * 之所以要三个一起看：单看角度会被"手指指向镜头"的透视缩短骗到，
     * 单看距离会被手的整体尺度变化影响。三个一起把关，误判率很低。
     *
     * [lm] 是 21 个关键点，每个是 (x, y, z) —— 与 Windows 版一样**带 z**，
     * 因为 z 参与夹角计算，去掉它数值就和那边对不上了。
     */
    fun fingerState(lm: Array<DoubleArray>, name: String, joints: IntArray,
                    p: ExtendParams = ExtendParams()): FingerState {
        val m = joints[0]
        val pi = joints[1]
        val di = joints[2]
        val ti = joints[3]
        val wrist = lm[0]

        // 相邻两节在伸直时同向 => 关节内角 = 180° - 两向量夹角
        val pipAngle = 180.0 - angleDeg(sub(lm[pi], lm[m]), sub(lm[di], lm[pi]))
        val dipAngle = 180.0 - angleDeg(sub(lm[di], lm[pi]), sub(lm[ti], lm[di]))

        val tipReach = dist(lm[ti], wrist)
        val pipReach = dist(lm[pi], wrist)
        val reachRatio = tipReach / pipReach.coerceAtLeast(1e-9)

        val extended = pipAngle >= p.pipMinDeg &&
            dipAngle >= p.dipMinDeg &&
            reachRatio > p.reachMargin
        return FingerState(name, extended, pipAngle, dipAngle, reachRatio)
    }

    /** 四根手指（食指/中指/无名指/小指）逐根判定。拇指刻意不参与（手型里它不表态）。 */
    fun fingerStates(lm: Array<DoubleArray>, p: ExtendParams = ExtendParams()): List<FingerState> =
        FINGER_JOINTS.map { (name, joints) -> fingerState(lm, name, joints, p) }

    /** 伸出的手指数（0~4）。 */
    fun extendedCount(lm: Array<DoubleArray>, p: ExtendParams = ExtendParams()): Int =
        fingerStates(lm, p).count { it.extended }

    /** 一句话描述四根手指的状态，给悬浮窗/诊断用，例如 `4/4` 或 `1/4(中指曲,无名指曲,小指曲)`。 */
    fun describeFingers(states: List<FingerState>): String {
        val n = states.count { it.extended }
        if (n == states.size) return "$n/${states.size}"
        val curled = states.filter { !it.extended }.joinToString(",") { "${CN_FINGER[it.name] ?: it.name}曲" }
        return "$n/${states.size}($curled)"
    }

    private val CN_FINGER = mapOf(
        "index" to "食指", "middle" to "中指", "ring" to "无名指", "pinky" to "小指",
    )
}
