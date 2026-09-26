package com.airgesture.sweep

/**
 * 屏幕方向 → 扫动方向的映射（**纯逻辑，不 import 任何 Android API**，可跑纯 JVM 单测 ——
 * 与 [CameraScope] / [ProximityTrigger] 同一个套路）。
 *
 * ==================== 为什么需要它（v2.3.0 横屏支持） ====================
 * MediaPipe 的 `ImageProcessingOptions.setRotationDegrees()` **只改变"模型看到的画面"**，
 * 回调里的 21 个关键点坐标始终在 **CameraX 未旋转的原始缓冲区坐标系**里。证据链（三处源码）：
 *   1. `ImageProcessingOptions.Builder.setRotationDegrees(int)` —— 参数是**顺时针度数**；
 *   2. C++ `BaseVisionTaskApi::ConvertToNormalizedRect()` 把它变成
 *      `NormalizedRect.rotation = -degrees * π/180`，注释写明 ImageToTensorCalculator
 *      "先按这个 rect 旋转、用它裁剪，**最后再把 rect 转回来**"；
 *   3. 手部图里的 `LandmarkProjectionCalculator` 用同一个 `rect.rotation()` 把模型输出
 *      **反向投影回原图坐标系**（`new_x = cos(angle)*x - sin(angle)*y ...`）。
 * → 结论：**"屏幕转了多少"必须由我们自己补**，不能指望 rotationDegrees 帮我们转坐标。
 *
 * ==================== 需要多少偏移（推导） ====================
 * 记屏幕（内容）方向角为 T（0°=右、90°=上），帧旋转为 R（`ImageInfo.rotationDegrees`，
 * 含义是"把缓冲区**顺时针**转 R 度就得到正立画面"），前置摄像头画面本身左右镜像。
 * 于是"屏幕上朝 T 的位移"在缓冲区里是：
 *
 *     缓冲区位移 ∝ ( −cos(R−T), +sin(R−T) )
 *
 * 判定器内部先做坐标镜像（x 取反）、再算 α = atan2(−dy, dx)。对正轴方向
 * （R−T 必是 90° 的整数倍）有 α = T − R；而 [SweepDetector] 的最终方向
 * γ = α + offset + 180（那 180° 来自 [applyPolarity] 的左右互换 + 上下反转）。
 * 要 γ = T，就必须：
 *
 *     offset = R + 180
 *
 * 竖屏代入（本机前置 sensor 270°、ROTATION_0 ⇒ R = 270）得到 **offset = 90°** ——
 * 正好等于项目里一直生效的 `Prefs.DEFAULT_ANGLE_OFFSET`，也与 README 7.4 记录的实测关系
 * 「实测角 = 90° − 真实角」首尾相合。两条独立证据互证说明推导没跑偏，也说明那 90° 不是
 * "支架转了 90°"的历史遗留，而是**该机型在自然方向下的几何常数**。
 *
 * 再看横屏：`ROTATION_90` ⇒ offset **180°**，`ROTATION_270` ⇒ offset **0°**。
 * **两个横屏方向相差 180°** —— 所以"横屏用一个写死的偏移常量"必然有一边上下颠倒，
 * 这就是必须按旋转实时推导、而不能写死的原因。
 *
 * ==================== 为什么用屏幕旋转 D 而不是帧旋转 R ====================
 *
 *     offset = 锚点(自然竖屏下实测有效的值) + D      // [angleOffsetDeg]
 *
 * 与 `R + 180` 等价（R = sensorOrientation + D），但用 D 有两个好处：
 *   · **不依赖 CameraX 的 `targetRotation` 是否被及时更新**。我们确实会同步它
 *     （见服务里 `syncTargetRotation`），但那只是为了让模型看到正立的画面、提高识别率；
 *     方向映射的正确性不该押在它上面。
 *   · 竖屏时 `offset == 锚点`，而锚点取的就是**手机上已存的那个值**
 *     （`angle_offset_deg`，默认 90°），所以**竖屏行为与旧版本逐位相同**。
 *
 * ⚠️ 唯一不是实测定下来的是 [ROTATION_COUPLING_SIGN] 的符号（推导为 +1）。真机上若发现
 * 横屏上下正好相反，把它改成 -1 重编即可（两个横屏方向会一起翻 180°）。
 */
object ScreenOrientation {

    /** 自然竖屏（`Surface.ROTATION_0`）对应的度数。 */
    const val DEG_0 = 0

    /** 横屏（`Surface.ROTATION_90`）。 */
    const val DEG_90 = 90

    /** 倒竖屏（`Surface.ROTATION_180`）。 */
    const val DEG_180 = 180

    /** 另一个方向的横屏（`Surface.ROTATION_270`）。 */
    const val DEG_270 = 270

    /**
     * 偏移随屏幕旋转的耦合符号（+1 或 -1）。
     *
     * +1 来自 CameraX 对**前置**摄像头的算法 `rotationDegrees = (sensorOrientation + targetRotation) % 360`
     * 与本文件顶部推导的自洽。它是唯一没在真机上实测过的量，因此单独抽成常量：
     * 横屏上下反了就改成 -1（一行，不动其它逻辑）。
     */
    const val ROTATION_COUPLING_SIGN = 1

    /** 这个屏幕旋转是不是横屏（`ROTATION_90` / `ROTATION_270`）。 */
    fun isLandscape(displayRotationDeg: Int): Boolean =
        displayRotationDeg == DEG_90 || displayRotationDeg == DEG_270

    /**
     * 当前屏幕旋转下判定器需要的方向角偏移量（度）。
     *
     * [naturalOffsetDeg] 是**自然竖屏下实测有效**的锚点值（`Prefs.angleOffset`，默认 90°）。
     */
    fun angleOffsetDeg(displayRotationDeg: Int, naturalOffsetDeg: Double): Double =
        normalize360(naturalOffsetDeg + ROTATION_COUPLING_SIGN * displayRotationDeg.toDouble())

    /**
     * 把判定器给出的**原始**方向映射成"用户想要的屏幕上方向"。
     *
     * 这两步与坐标系无关，纯粹是"前置摄像头画面镜像"带来的极性修正，所以**与屏幕旋转无关**：
     *   · 左右互换：前置画面是镜像的，左右极性天生相反；
     *   · 上下反转：`Prefs.invertVertical` 的应急开关（正常标定后不需要）。
     * 旋转那部分完全由 [angleOffsetDeg] 负责 —— 两者正交，别混在一起改。
     *
     * （这段逻辑 v2.3.0 之前叫 `SweepAccessibilityService.applyOrientation`，原样搬来，
     *   只是为了能脱离 Android 单测。）
     */
    fun applyPolarity(dir: String, invertVertical: Boolean): String {
        var d = when (dir) {
            "left" -> "right"
            "right" -> "left"
            else -> dir
        }
        if (invertVertical) {
            d = when (d) {
                "up" -> "down"
                "down" -> "up"
                else -> d
            }
        }
        return d
    }

    /**
     * 这个方向现在允许不允许注入。
     *
     * ★ 用户要求（v2.3.0）：**横屏下只处理上下两个方向**，左右扫不响应 ——
     *   横屏时手机在支架上被转 90°，左右扫既没有明确的屏幕语义，也容易和"上下"混淆。
     *   这里只是纯判定；真正的拦截点在 [SwipeInjector.perform]（注入那一层，单一策略点）。
     */
    fun allowsDirection(dir: String, landscape: Boolean): Boolean =
        !landscape || dir == "up" || dir == "down"

    private fun normalize360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
