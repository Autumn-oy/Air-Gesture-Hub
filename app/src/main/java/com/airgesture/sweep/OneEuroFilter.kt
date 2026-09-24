package com.airgesture.sweep

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro 滤波器（自适应低通）——从 Windows 版 `gesture_mouse/filters.py` 移植。
 *
 * 论文：Casiez, Roussel, Vogel (CHI 2012)。
 * 作用：手慢速微调时强力去抖，手快速扫动时几乎不延迟——隔空手势手感的关键。
 */
class LowPass {
    private var y: Double? = null

    fun apply(x: Double, alpha: Double): Double {
        val prev = y
        val out = if (prev == null) x else alpha * x + (1.0 - alpha) * prev
        y = out
        return out
    }

    fun reset() {
        y = null
    }
}

class OneEuro(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.0,
    var dCutoff: Double = 1.0,
) {
    private val xFilter = LowPass()
    private val dxFilter = LowPass()
    private var lastT: Double? = null
    private var lastRaw: Double? = null

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun apply(x: Double, t: Double): Double {
        val lt = lastT
        if (lt == null) {
            lastT = t
            lastRaw = x
            return xFilter.apply(x, 1.0)
        }
        var dt = t - lt
        if (dt <= 0.0) dt = 1e-3
        lastT = t

        val dx = (x - (lastRaw ?: x)) / dt
        lastRaw = x
        val dxHat = dxFilter.apply(dx, alpha(dCutoff, dt))
        val cutoff = minCutoff + beta * abs(dxHat)
        return xFilter.apply(x, alpha(cutoff, dt))
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastT = null
        lastRaw = null
    }
}

/** 二维版本：判定器实际用的是这个（对四指指尖质心的 x/y 同时滤波）。 */
class OneEuro2D(minCutoff: Double = 1.0, beta: Double = 0.0, dCutoff: Double = 1.0) {
    private val fx = OneEuro(minCutoff, beta, dCutoff)
    private val fy = OneEuro(minCutoff, beta, dCutoff)

    fun apply(x: Double, y: Double, t: Double): Pair<Double, Double> =
        fx.apply(x, t) to fy.apply(y, t)

    fun reset() {
        fx.reset()
        fy.reset()
    }

    companion object {
        /** 默认参数：与 Windows 版 config.json 的 control.filter 同源（min_cutoff=0.7）。 */
        fun forHand(): OneEuro2D = OneEuro2D(minCutoff = 0.7, beta = 0.02, dCutoff = 1.0)
    }
}
