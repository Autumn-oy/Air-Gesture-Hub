package com.airgesture.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接近光触发器的单测（用户提出的设计：手贴近传感器才连相机）。
 *
 * 这段逻辑写错会出两种相反的坏结果，而且都很难从外部看出来：
 *
 *   1. **判"贴近"的门槛写松** -> 远离时也判成贴近 -> 窗口永不过期 ->
 *      相机一直开着，等于整个省电设计白做。
 *      本机实测：接近光量程 5cm，**远离时读数值也是 5.00**
 *      （`dumpsys sensorservice` 里恒为 `5.00, 0.00, 0.00`）。
 *      所以绝不能写成 `value <= maxRange`。
 *
 *   2. **窗口不会续期** -> 连做两次手势要反复去贴近传感器，用起来很烦。
 */
class ProximityTriggerTest {

    // 本机实测量程
    private val maxRange = 5f

    // ------------------------------------------------------------ 贴近判定

    @Test
    fun reportsNearOnlyWhenStrictlyInsideTheRange() {
        assertTrue("1cm 应算贴近", ProximityTrigger.isNear(1.0f, maxRange))
        assertTrue("2.9cm 应算贴近", ProximityTrigger.isNear(2.9f, maxRange))
    }

    /**
     * ★ 最关键的一条：本机**远离时读数就是量程值 5.00**。
     *   如果把判据写成 `value <= maxRange`，那么"远离"会被判成"贴近"，
     *   窗口永不过期 -> 相机一直开 -> 省电设计完全失效。
     */
    @Test
    fun readingEqualtoMaxRangeMeansFAR_notNear() {
        assertFalse(
            "读数等于量程(=远离，本机实测 5.00) 必须判为不贴近",
            ProximityTrigger.isNear(maxRange, maxRange),
        )
        assertFalse("比量程还大也算远离", ProximityTrigger.isNear(9.9f, maxRange))
        assertFalse("0 之外的常见远离值", ProximityTrigger.isNear(5.0f, maxRange))
    }

    /** 有些机型的接近光是 0/1 二值，1 表示远离 —— 那种也要判对。 */
    @Test
    fun binaryProximitySensorsAreHandled() {
        // 量程 1，贴近报 0，远离报 1
        assertTrue(ProximityTrigger.isNear(0f, 1f))
        assertFalse("二值传感器的'远'必须判为不贴近", ProximityTrigger.isNear(1f, 1f))
    }

    @Test
    fun nanIsNeverNear() {
        assertFalse("读不到值时必须当'不贴近'，否则窗口会莫名其妙开着",
            ProximityTrigger.isNear(Float.NaN, maxRange))
    }

    // ------------------------------------------------------------ 开窗/续期

    @Test
    fun nearArmsTheWindow() {
        assertTrue(ProximityTrigger.shouldArm(currentlyArmed = false, near = true, handPresent = false))
        assertTrue("已经开着时再贴近也应续期", ProximityTrigger.shouldArm(true, true, false))
    }

    @Test
    fun handInsideTheWindowExtendsIt() {
        // 窗口内检测到手 -> 续期，连续做手势不用反复贴近
        assertTrue(ProximityTrigger.shouldArm(currentlyArmed = true, near = false, handPresent = true))
    }

    @Test
    fun nothingHappensWhenNotArmedAndNotNear() {
        assertFalse("没贴近、也没开窗 -> 什么都不做（不能开窗）",
            ProximityTrigger.shouldArm(currentlyArmed = false, near = false, handPresent = true))
        assertFalse("窗口关着时的『看到手』不该开窗（相机本来就没连）",
            ProximityTrigger.shouldArm(false, false, false))
    }

    /** 窗口开着、又没有手、又没有新贴近 -> 不续期，等它自然过期。 */
    @Test
    fun idleWindowIsNotExtended() {
        assertFalse(ProximityTrigger.shouldArm(currentlyArmed = true, near = false, handPresent = false))
    }

    // ------------------------------------------------------------ 过期

    @Test
    fun windowExpiresAfterItsLength() {
        val win = 30_000L
        val armedAt = 1_000L
        assertFalse("还差 1ms 不该过期", ProximityTrigger.isExpired(true, armedAt + win - 1, armedAt, win))
        assertTrue("刚好到点应过期", ProximityTrigger.isExpired(true, armedAt + win, armedAt, win))
        assertTrue("超时很久肯定过期", ProximityTrigger.isExpired(true, armedAt + win * 3, armedAt, win))
    }

    @Test
    fun anUnarmedWindowIsNeverExpired() {
        // 没开窗就无所谓过期 —— 否则会反复触发"解绑"逻辑
        assertFalse(ProximityTrigger.isExpired(false, 999_999L, 0L, 30_000L))
    }

    /**
     * 与 Prefs 里的常量保持一致：两处必须同时改。
     *
     * 历史：30 秒（周期探测方案，被用户否决）→ 20 秒（真机实测后定）→
     * 15 秒（2026-09-24 用户在真机上要求再缩短）。
     * 窗口越短占空比越低；窗口内的续期（每次检测到手）保证连续手势不被切断。
     */
    @Test
    fun windowLengthMatchesTheConfirmedFifteenSeconds() {
        assertEquals(15_000L, Prefs.PROXIMITY_WINDOW_MS)
    }
}
