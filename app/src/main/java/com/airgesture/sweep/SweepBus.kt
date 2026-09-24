package com.airgesture.sweep

/**
 * 悬浮窗要显示的状态快照。
 *
 * v0.16.0 简化：MainActivity 不再订阅状态（诊断信息已删），所以这里只剩一个数据类 ——
 * 服务直接把 Status 交给 OverlayController，不再走"总线"那一层。
 */
object SweepBus {

    data class Status(
        val serviceReady: Boolean = false,
        val handPresent: Boolean = false,
        val gated: Boolean = false,
        val state: String = "IDLE",
        val fps: Double = 0.0,
        val disp: Double = 0.0,
        val angle: Double = Double.NaN,
        val together: Double = Double.NaN,
        /** 四指伸出根数的可读描述，例如 "4/4" 或 "1/4(中指曲,无名指曲,小指曲)"。 */
        val fingerInfo: String = "—",
        /** 四指伸出的根数（0~4）。 */
        val extended: Int = 0,
        /** 冷静期还剩多少毫秒（0 = 不在冷静期）。计时起点是**触发那一瞬间**。 */
        val cooldownLeftMs: Double = 0.0,
        val lastDir: String? = null,
        val message: String = "",
        val injectInfo: String = "",
        // ---- 三路诊断计数：用来区分"管道断了"和"阈值太高" ----
        /** 送进推理的帧数 */
        val framesIn: Long = 0,
        /** 推理回调次数（若 framesIn 涨而这个不涨 => 推理管道断了） */
        val resultsOut: Long = 0,
        /** 回调里检测到手的次数（若 resultsOut 涨而这个不涨 => 模型没认出你的手） */
        val handsSeen: Long = 0,
        /** 实际生效的委托与图像通道 */
        val modelInfo: String = "",
        /**
         * 最近几次手势的方向（最多 [GESTURE_HISTORY_MAX] 个，新的在后）。
         *
         * ★ 为什么需要它：悬浮窗上的"上次方向"是**瞬时**的 —— 它只反映最后一次触发，
         *   而截图是瞬时的，很难在恰当的时机抓到某一方向。更糟的是它无法回答
         *   "四个方向是不是都对"这种需要累积观察的问题（本会话就因为方向反了返工过一次）。
         *   累积历史让**一张截图**就能看出全部方向，不依赖用户复述或抓拍时机。
         */
        val gestureHistory: String = "",
    ) {
        companion object {
            /** 历史里保留几次手势。6 个足够覆盖"四个方向各一次 + 两次重复"。 */
            const val GESTURE_HISTORY_MAX = 6
        }
    }

    fun directionCn(dir: String?): String = when (dir) {
        "up" -> "上"
        "down" -> "下"
        "left" -> "左"
        "right" -> "右"
        else -> "—"
    }
}
