package com.airgesture.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 屏幕方向（横竖屏）→ 扫动方向的映射测试（v2.3.0 横屏支持）。
 *
 * 这一段逻辑的价值在于"**手机上没法自动测手势，但映射关系可以**"：
 * 只要把"屏幕上朝 T 扫动"翻译成 MediaPipe 会给出的缓冲区位移，
 * 就能在纯 JVM 上把"四个屏幕旋转 × 四个方向"全部回放一遍。
 *
 * 翻译公式的来历（推导见 [ScreenOrientation] 顶部 KDoc）：
 *
 *     R = 帧旋转 = (sensorOrientation + 屏幕旋转) % 360        // CameraX 对前置摄像头
 *     缓冲区位移 ∝ ( −cos(R−T), +sin(R−T) )
 *
 * ⚠️ 诚实说明：这条公式与"MediaPipe 回调坐标在未旋转的缓冲区坐标系里"这一结论
 * 是**推导 + 与项目历史实测互证**得到的（README 7.4 记录「实测角 = 90° − 真实角」，
 * 与公式在竖屏下一致）。它钉住的是**代码内部一致性**，不能替代真机验收：
 * 真机上还要确认"手往上扫 = 屏幕上滑"，尤其是两个横屏方向（见交接文档）。
 */
class ScreenOrientationTest {

    /** 本机（华为 P40 Pro）前置摄像头 sensorOrientation；锚点 90° 与整条推导都建立在它之上。 */
    private val sensorOrientation = 270

    /** 自然竖屏下实测有效的方向角锚点（= `Prefs.DEFAULT_ANGLE_OFFSET`）。 */
    private val anchor = 90.0

    private val bufferW = 640.0
    private val bufferH = 480.0

    // ================================================================ 纯函数

    /**
     * ★ 最重要的一条：**竖屏偏移必须还是 90°**。
     *
     * 旧版本用的是写死的 90°（`Prefs.DEFAULT_ANGLE_OFFSET`），而 v2.3.0 改成
     * "锚点 + 屏幕旋转"。这条断言保证 `ROTATION_0` 下算出来仍是 90° ——
     * 也就是**用户已经验收过的竖屏手感一个比特都不会变**。
     */
    @Test
    fun portraitOffsetIsStillTheVerifiedNinetyDegrees() {
        assertEquals(90.0, ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_0, anchor), 1e-9)
        // 生产代码用的锚点就是 Prefs 里那个默认值（const，编译期内联，不需要 Android 运行时）
        assertEquals(
            90.0,
            ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_0, Prefs.DEFAULT_ANGLE_OFFSET.toDouble()),
            1e-9,
        )
    }

    @Test
    fun offsetFollowsScreenRotation() {
        assertEquals(180.0, ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_90, anchor), 1e-9)
        assertEquals(270.0, ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_180, anchor), 1e-9)
        assertEquals(0.0, ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_270, anchor), 1e-9)
    }

    /**
     * ★ 这条解释了"为什么横屏不能写死一个偏移常量"：
     *   两个横屏方向的偏移相差 **180°**，一个写死的值必然让其中一个方向上下颠倒。
     */
    @Test
    fun theTwoLandscapeOrientationsDifferByOneHundredEightyDegrees() {
        val a = ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_90, anchor)
        val b = ScreenOrientation.angleOffsetDeg(ScreenOrientation.DEG_270, anchor)
        assertEquals(180.0, Math.abs(a - b), 1e-9)
    }

    @Test
    fun landscapeIsOnlyNinetyAndTwoSeventy() {
        assertFalse(ScreenOrientation.isLandscape(ScreenOrientation.DEG_0))
        assertTrue(ScreenOrientation.isLandscape(ScreenOrientation.DEG_90))
        assertFalse("倒竖屏不是横屏，四个方向都要照常工作", ScreenOrientation.isLandscape(ScreenOrientation.DEG_180))
        assertTrue(ScreenOrientation.isLandscape(ScreenOrientation.DEG_270))
    }

    @Test
    fun onlyVerticalDirectionsSurviveInLandscape() {
        for (d in listOf("left", "right")) {
            assertTrue("竖屏四个方向都要放行：$d", ScreenOrientation.allowsDirection(d, landscape = false))
            assertFalse("横屏不响应左右：$d", ScreenOrientation.allowsDirection(d, landscape = true))
        }
        for (d in listOf("up", "down")) {
            assertTrue(ScreenOrientation.allowsDirection(d, landscape = false))
            assertTrue(ScreenOrientation.allowsDirection(d, landscape = true))
        }
    }

    /** 极性映射必须与 v2.3.0 之前 `applyOrientation` 的行为逐字一致（原样搬过来的）。 */
    @Test
    fun polarityMappingIsUnchangedFromTheOldImplementation() {
        // 左右互换是**无条件**的（前置摄像头画面镜像），与 invertVertical 无关
        assertEquals("right", ScreenOrientation.applyPolarity("left", invertVertical = false))
        assertEquals("left", ScreenOrientation.applyPolarity("right", invertVertical = false))
        assertEquals("up", ScreenOrientation.applyPolarity("up", invertVertical = false))
        assertEquals("down", ScreenOrientation.applyPolarity("down", invertVertical = false))
        // invertVertical = true：在左右互换之后再翻一次上下（本机默认 true）
        assertEquals("right", ScreenOrientation.applyPolarity("left", invertVertical = true))
        assertEquals("left", ScreenOrientation.applyPolarity("right", invertVertical = true))
        assertEquals("down", ScreenOrientation.applyPolarity("up", invertVertical = true))
        assertEquals("up", ScreenOrientation.applyPolarity("down", invertVertical = true))
    }

    // ================================================================ 端到端回放

    /** 屏幕上的四个正方向（0°=右、90°=上）。 */
    private val screenDirections = listOf(0.0 to "right", 90.0 to "up", 180.0 to "left", 270.0 to "down")

    @Test
    fun portraitMapsAllFourDirections() {
        for ((deg, name) in screenDirections) {
            assertEquals(
                "竖屏 $name 方向映射错了",
                name,
                finalDirection(ScreenOrientation.DEG_0, deg),
            )
        }
    }

    @Test
    fun upsideDownPortraitMapsAllFourDirections() {
        for ((deg, name) in screenDirections) {
            assertEquals(
                "倒竖屏 $name 方向映射错了",
                name,
                finalDirection(ScreenOrientation.DEG_180, deg),
            )
        }
    }

    @Test
    fun bothLandscapeOrientationsMapUpAndDown() {
        for (display in listOf(ScreenOrientation.DEG_90, ScreenOrientation.DEG_270)) {
            for ((deg, name) in listOf(90.0 to "up", 270.0 to "down")) {
                assertEquals(
                    "横屏 $display° 下 $name 方向映射错了",
                    name,
                    finalDirection(display, deg),
                )
            }
        }
    }

    /** 横屏下左右仍然**能识别出来**（只是不注入）—— 悬浮窗历史会写成"忽略左/右"。 */
    @Test
    fun landscapeStillRecognisesLeftAndRightButTheyAreNotAllowed() {
        for (display in listOf(ScreenOrientation.DEG_90, ScreenOrientation.DEG_270)) {
            for ((deg, name) in listOf(0.0 to "right", 180.0 to "left")) {
                val got = finalDirection(display, deg)
                assertEquals("横屏 $display° 下 $name 应被识别出来", name, got)
                assertFalse(
                    "横屏 $display° 下 $name 不该被放行",
                    ScreenOrientation.allowsDirection(got!!, landscape = true),
                )
            }
        }
    }

    /**
     * ★ 回归：这就是 v2.3.0 之前横屏坏掉的样子 —— 用写死的 90° 偏移时，
     *   横屏下"向上扫"会被判成**左右**（而不是上下颠倒）。所以"只屏蔽左右"
     *   若不先修映射，横屏会变成"上下也滑不动"。
     */
    @Test
    fun fixedNinetyDegreeOffsetBreaksLandscapeExactlyAsUsersSawIt() {
        val broken = finalDirection(ScreenOrientation.DEG_90, 90.0, offsetOverride = 90.0)
        assertNotEquals("写死 90° 时横屏向上扫不该还能得到 up", "up", broken)
        assertTrue("写死 90° 时横屏向上扫会被判成左右：实际=$broken", broken == "left" || broken == "right")
    }

    // ================================================================ 回放机器

    /** 屏幕旋转度数 D → 帧旋转 R（CameraX 对前置摄像头：R = sensor + D）。 */
    private fun frameRotation(displayDeg: Int): Int = (sensorOrientation + displayDeg) % 360

    /** 服务里 `lastAspect` 的算法（帧旋转 90/270 时取 h/w，否则 w/h）。 */
    private fun aspect(frameRot: Int): Double =
        if (frameRot == 90 || frameRot == 270) bufferH / bufferW else bufferW / bufferH

    /** 极性映射之后的"屏幕上会往哪滑"。 */
    private fun finalDirection(
        displayDeg: Int,
        screenDeg: Double,
        offsetOverride: Double? = null,
    ): String? = rawDirection(displayDeg, screenDeg, offsetOverride)
        ?.let { ScreenOrientation.applyPolarity(it, invertVertical = true) }

    /**
     * 把一次扫动回放给真实判定器，返回它的**原始**方向。
     *
     * 复刻服务里的处理顺序：指尖质心（x 已按 aspect 折算）→ 坐标镜像 x → 判定器。
     * 滤波器不参与（它是低通，不影响"方向对不对"）。
     */
    private fun rawDirection(
        displayDeg: Int,
        screenDeg: Double,
        offsetOverride: Double? = null,
    ): String? {
        val frameRot = frameRotation(displayDeg)
        val a = aspect(frameRot)
        val offset = offsetOverride ?: ScreenOrientation.angleOffsetDeg(displayDeg, anchor)
        val det = SweepDetector(
            enterThr = 0.14,
            deadBand = 0.02,
            togetherMax = 2.0,
            cooldownMs = 1000.0,
            settleMs = 250.0,
            quietSpeed = 0.35,
            lostMs = 300.0,
            maxSweepMs = 900.0,
            angleOffsetDeg = offset,
            axisTolDeg = 30.0,
            minExtendedFingers = 3,
        )

        // 屏幕上朝 screenDeg 扫动时，MediaPipe 回调（缓冲区坐标系）里的指尖位移
        val amp = 0.5
        val rad = Math.toRadians(frameRot - screenDeg)
        val dxl = -amp * cos(rad)
        val dyl = amp * sin(rad)
        // 服务里的处理：x 先乘 aspect，再镜像
        val dx = -dxl * a
        val dy = dyl

        val x0 = -0.5 * a
        val y0 = 0.5
        var t = 0.0
        // 动作1：摆好姿势并停稳（0.4s > settleMs=250ms，四指伸出 → 姿势锁定）
        repeat(8) {
            t += 0.05
            det.update(t, true, x0, y0, 1.0, 4)
        }
        // 动作2：分 8 帧扫到位（每帧位移大于死区，总位移远大于阈值）
        var out: String? = null
        repeat(8) { i ->
            t += 0.05
            val k = (i + 1) / 8.0
            val d = det.update(t, true, x0 + dx * k, y0 + dy * k, 1.0, 4)
            if (out == null) out = d
        }
        return out
    }
}
