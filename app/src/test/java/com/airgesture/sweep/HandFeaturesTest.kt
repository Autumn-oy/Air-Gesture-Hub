package com.airgesture.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "四根手指"判定的跨语言一致性测试。
 *
 * 这些向量由 `probe/make_finger_vectors.py` 生成，期望值来自 Windows 版
 * `gesture_mouse/gestures.py` 的 `finger_state()`（用真实手部数据标定过的那套）。
 * 这里回放**同一批 21 点**，比对"伸/曲"判定和三个中间量（PIP/DIP 内角、reach 比值）。
 *
 * 为什么这个测试重要：用户实测"一根手指也能触发滑动"——根因就是原来的判据只量并拢度、
 * 不问手指有没有伸出来。修复的关键就是这套夹角计算，而它最容易被移植写错
 * （三向量点积、内角 = 180° - 夹角、reach 是"指尖到手腕 / 近端指节到手腕"）。
 *
 * 重新生成向量：
 *     .venv\Scripts\python.exe -m probe.make_finger_vectors
 */
class HandFeaturesTest {

    @Test
    fun fingerVectorsMatchWindowsReference() {
        for (case in FingerVectors.all) {
            val states = HandFeatures.fingerStates(case.landmarks)

            assertEquals(
                "场景 ${case.name}：伸出的根数不对（${case.note}）",
                case.count, states.count { it.extended },
            )

            case.expected.forEachIndexed { i, exp ->
                val got = states[i]
                assertEquals("${case.name}/${exp.name} 的伸/曲判定", exp.extended, got.extended)
                assertEquals("${case.name}/${exp.name} PIP 内角", exp.pip, got.pipAngle, 0.05)
                assertEquals("${case.name}/${exp.name} DIP 内角", exp.dip, got.dipAngle, 0.05)
                assertEquals("${case.name}/${exp.name} reach 比值", exp.reach, got.reachRatio, 0.002)
            }
        }
    }

    /** 单指/握拳在默认要求（4 根）下必须不达标 —— 这就是用户报的那个 bug。 */
    @Test
    fun oneFingerIsNotEnoughForTheDefaultGate() {
        val oneFinger = FingerVectors.all.first { it.name == "one_finger_index" }
        assertEquals(1, HandFeatures.extendedCount(oneFinger.landmarks))

        val fist = FingerVectors.all.first { it.name == "fist" }
        assertEquals(0, HandFeatures.extendedCount(fist.landmarks))

        val four = FingerVectors.all.first { it.name == "four_extended" }
        assertEquals(4, HandFeatures.extendedCount(four.landmarks))
    }

    /** 判定必须与手部旋转无关（手斜着伸出来也算四指伸直）。 */
    @Test
    fun rotationDoesNotChangeTheVerdict() {
        val base = FingerVectors.all.first { it.name == "four_extended" }
        for (name in listOf("four_extended_rotated_90", "four_extended_rotated_minus_60")) {
            val rotated = FingerVectors.all.first { it.name == name }
            assertEquals(
                "转过的四指手也必须判成 4/4",
                HandFeatures.extendedCount(base.landmarks),
                HandFeatures.extendedCount(rotated.landmarks),
            )
        }
    }

    /** 悬浮窗上的读数是给人看的，格式别退化。 */
    @Test
    fun describeFingersIsReadable() {
        val four = FingerVectors.all.first { it.name == "four_extended" }
        assertEquals("4/4", HandFeatures.describeFingers(HandFeatures.fingerStates(four.landmarks)))

        val one = FingerVectors.all.first { it.name == "one_finger_index" }
        val text = HandFeatures.describeFingers(HandFeatures.fingerStates(one.landmarks))
        assertTrue("单指时应显示 1/4 并列出蜷着的手指，实际 $text", text.startsWith("1/4("))
        assertTrue("应点名中指/无名指/小指，实际 $text",
            text.contains("中指") && text.contains("无名指") && text.contains("小指"))
    }
}
