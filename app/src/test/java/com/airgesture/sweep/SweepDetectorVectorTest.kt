package com.airgesture.sweep

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 跨语言一致性测试：把 Python 参考实现（`probe/detector_ref.py`，已通过 19 个单测）
 * 生成的测试向量逐帧喂给 Kotlin 版 SweepDetector，比对触发序列。
 *
 * 这个测试的价值：**手机上没法自动测手势**，但"移植有没有走样"可以在这里自动发现。
 * 与 Windows 版"状态机脱离硬件可测"是同一个思路。
 *
 * 向量文件是自动生成的：
 *     .venv\Scripts\python.exe -m probe.make_sweep_vectors
 */
@RunWith(Parameterized::class)
class SweepDetectorVectorTest(private val case: SweepVectors.Case) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<SweepVectors.Case> = SweepVectors.all
    }

    override fun toString(): String = case.name

    @Test
    fun replaysVectorIdentically() {
        val det = SweepDetector(
            enterThr = case.enterThr,
            deadBand = case.deadBand,
            togetherMax = case.togetherMax,
            cooldownMs = case.cooldownMs,
            settleMs = case.settleMs,
            quietSpeed = case.quietSpeed,
            lostMs = case.lostMs,
            maxSweepMs = case.maxSweepMs,
            angleOffsetDeg = case.angleOffset,
            axisTolDeg = case.axisTolDeg,
            minExtendedFingers = case.minExtendedFingers,
        )

        val got = ArrayList<String>()
        for (f in case.frames) {
            val dir = det.update(
                t = f[0],
                present = f[1] > 0.5,
                x = f[2],
                y = f[3],
                together = f[4],
                extended = f[5].toInt(),
            )
            if (dir != null) got.add(dir)
        }

        assertEquals("场景 ${case.name}：${case.note}", case.expected, got)
    }
}
