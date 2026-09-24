package com.airgesture.sweep

import android.content.Context

/**
 * 参数存取。
 *
 * ★ v0.16.0 起**参数定稿并内置**：下面的常量就是最终值（用户逐项确认过），
 *   设置页里不再有滑块 —— 要改就直接改这里（或者告诉我）。
 *
 * | 参数 | 内置值 | 说明 |
 * |---|---|---|
 * | 触发阈值 | 0.14 | 画面高度比例；实测扫动幅度约 0.45，0.14 抗误触发更好 |
 * | 方向角偏移 | +90° | 用户当前修正后的值（画面整体转了 90°） |
 * | 冷静期 | 1300ms | **从触发那一瞬间**起算（不是等滑动播完） |
 * | 要求伸出的手指数 | 3 | 单指/双指仍然被挡住 |
 * | 四指并拢上限 | 2.0 | 2.0 等于**不再用并拢度筛**（张开手掌约 1.4~1.8），留字段备用 |
 * | 合成滑动时长 | 200ms | |
 * | 合成滑动幅度 | 上下 0.30 屏高（≈799px/2664）、左右 0.30 屏宽（≈360px/1200） | |
 * | 桌面（启动器） | 固定不响应 | 见 SwipeInjector |
 */
object Prefs {
    private const val FILE = "airgesture"

    private const val K_ENTER_THR = "enter_thr"
    private const val K_DEAD_BAND = "dead_band"
    private const val K_TOGETHER_MAX = "together_max"
    private const val K_COOLDOWN_MS = "cooldown_ms"
    private const val K_LOST_MS = "lost_ms"
    private const val K_MAX_SWEEP_MS = "max_sweep_ms"
    private const val K_ANGLE_OFFSET = "angle_offset_deg"

    private const val K_MIRROR_X = "mirror_x"
    private const val K_INVERT_V = "invert_vertical"
    private const val K_IMAGE_PATH = "image_path"
    private const val K_SWIPE_MS = "swipe_ms"
    private const val K_SWIPE_FRAC_V = "swipe_frac_v"
    private const val K_SWIPE_FRAC_H = "swipe_frac_h"
    private const val K_MIN_EXTENDED = "min_extended"
    private const val K_MIGRATED_V4 = "migrated_v4"
    private const val K_DEBUG_OVERLAY = "debug_overlay"
    private const val K_ACTIVE = "active"
    private const val K_CAMERA_SCOPE = "camera_scope"
    private const val K_CAMERA_WHITELIST = "camera_whitelist"
    private const val K_PROXIMITY_TRIGGER = "proximity_trigger"
    private const val K_PROXIMITY_FORCE = "proximity_force_armed"

    /** 相机作用域：全局。 */
    const val SCOPE_GLOBAL = "global"
    /** 相机作用域：白名单。 */
    const val SCOPE_WHITELIST = "whitelist"

    // ---------------------------------------------------------------- 定稿参数

    /** 触发位移阈值（画面高度比例）。 */
    const val DEFAULT_ENTER_THR = 0.14f

    /** 方向角整体偏移（度）—— 用户当前修正后的值。 */
    const val DEFAULT_ANGLE_OFFSET = 90f

    /**
     * ★冷静期（毫秒）。计时起点是**判定器触发的那一刻**（位移越过 enterThr 的那一帧），
     * 不是"合成滑动播放完"之后 —— 见 SweepDetector：触发即 `state = COOLDOWN`。
     * 这 1200ms 盖住"后半截扫动 + 顶点停顿 + 收手归位"整段，它们一次都不会被累积。
     *
     * v0.17.9 由 1300ms 改为 1200ms（用户实测后定的）。
     */
    const val DEFAULT_COOLDOWN_MS = 1200f

    /** 锁定时四指里至少要伸出几根。3 = 单指/双指挡住，三根就算过（用户定稿）。 */
    const val DEFAULT_MIN_EXTENDED = 3

    /**
     * 四指并拢度上限。定稿 **2.0** —— 等于**不再用并拢度筛人**
     * （张开手掌的实测比值约 1.4~1.8，正常并拢约 0.8~1.1）。
     * 字段保留：以后想重新启用这道门控，把它调回 1.2 即可。
     */
    const val DEFAULT_TOGETHER_MAX = 2.0f

    /** 合成滑动时长（毫秒）：拖动段，越短速度越快。 */
    const val DEFAULT_SWIPE_MS = 200f

    /** 合成滑动幅度（屏高/屏宽比例）。0.30 → 1200x2664 的屏上约 360px / 799px。 */
    const val DEFAULT_SWIPE_FRAC_V = 0.30f
    const val DEFAULT_SWIPE_FRAC_H = 0.30f

    /** 死区：每帧位移小于它就不累积（"慢归位很安全"的原因）。 */
    const val DEFAULT_DEAD_BAND = 0.02f

    /** 姿势锁定时间：手停稳这么久才算"动作1 完成"。 */
    const val DEFAULT_LOST_MS = 300f
    const val DEFAULT_MAX_SWEEP_MS = 900f

    /** 图像通道：
     *  rgba  = CameraX 直接出 RGBA，在分析线程里同步拷进 Bitmap 再交给 MediaPipe（**数据一定有效**）
     *  media = 旧的零拷贝通道（MediaImageBuilder 直接包 ImageProxy 的 Image）
     *
     *  为什么默认改成 rgba：MediaImageBuilder 的源码注释写明"传进去之后不要修改 Image 的内容"，
     *  它的 build() 只是 `new MediaImageContainer(mediaImage)`，**不拷贝像素**。而我们在
     *  detectAsync 之后立刻 proxy.close()（照抄官方示例），MediaPipe 是异步流水线，
     *  等它真去读像素时 Image 已经失效 —— 现象就是"相机在跑但永远无手"。
     *  留着 media 这条是为了万一 rgba 在某些机型上有 stride 问题可以切回来。
     */
    const val IMG_RGBA = "rgba"
    const val IMG_MEDIA = "media"

    fun imagePath(ctx: Context): String = sp(ctx).getString(K_IMAGE_PATH, IMG_RGBA) ?: IMG_RGBA

    fun setImagePath(ctx: Context, v: String) = sp(ctx).edit().putString(K_IMAGE_PATH, v).apply()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 从设置构造判定器。服务启动/参数变更时调用。
     *
     * 轴向容差固定 ±30°（"斜着扫不判定"）。v0.16.0 删掉了标定向导，所以不再需要
     * `relaxAxisTol` 那个"标定时放宽容差"的口子。
     */
    fun buildDetector(ctx: Context): SweepDetector {
        val p = sp(ctx)
        return SweepDetector(
            enterThr = p.getFloat(K_ENTER_THR, DEFAULT_ENTER_THR).toDouble(),
            deadBand = p.getFloat(K_DEAD_BAND, DEFAULT_DEAD_BAND).toDouble(),
            togetherMax = p.getFloat(K_TOGETHER_MAX, DEFAULT_TOGETHER_MAX).toDouble(),
            cooldownMs = p.getFloat(K_COOLDOWN_MS, DEFAULT_COOLDOWN_MS).toDouble(),
            lostMs = p.getFloat(K_LOST_MS, DEFAULT_LOST_MS).toDouble(),
            maxSweepMs = p.getFloat(K_MAX_SWEEP_MS, DEFAULT_MAX_SWEEP_MS).toDouble(),
            angleOffsetDeg = p.getFloat(K_ANGLE_OFFSET, DEFAULT_ANGLE_OFFSET).toDouble(),
            minExtendedFingers = p.getInt(K_MIN_EXTENDED, DEFAULT_MIN_EXTENDED),
        )
    }

    /**
     * 一次性迁移（v0.16.0）：把所有定稿参数**强制**写成内置值。
     *
     * 为什么必须做：SharedPreferences 里已有的值会**盖掉**代码里的新默认值，
     * 而这些键在旧版本里被滑块/标定写过（比如左右幅度被"桌面翻页"顶到 0.60、
     * 冷静期是 1500、方向角是标定值）。
     *
     * ⚠️ 唯一**不覆盖**的是方向角：用户报"当前修正后的方向角"是 +90°，已作为代码默认值；
     *    但他手机上已经生效的那个值（可能是 -90 或别的）保持原样 —— 万一口述的符号与实际
     *    相反，强制写入会把他本来好用的方向搞反 180°。**重装后**才会用到代码里的 +90°。
     */
    fun migrateOnce(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean(K_MIGRATED_V4, false)) return
        p.edit()
            .putFloat(K_ENTER_THR, DEFAULT_ENTER_THR)
            .putFloat(K_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
            .putFloat(K_TOGETHER_MAX, DEFAULT_TOGETHER_MAX)
            .putFloat(K_SWIPE_MS, DEFAULT_SWIPE_MS)
            .putFloat(K_SWIPE_FRAC_V, DEFAULT_SWIPE_FRAC_V)
            .putFloat(K_SWIPE_FRAC_H, DEFAULT_SWIPE_FRAC_H)
            .putInt(K_MIN_EXTENDED, DEFAULT_MIN_EXTENDED)
            .putBoolean(K_MIGRATED_V4, true)
            .apply()
    }

    // ---------------------------------------------------------------- 存取

    fun enterThr(ctx: Context): Float = sp(ctx).getFloat(K_ENTER_THR, DEFAULT_ENTER_THR)

    fun setEnterThr(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_ENTER_THR, v).apply()

    fun angleOffset(ctx: Context): Float = sp(ctx).getFloat(K_ANGLE_OFFSET, DEFAULT_ANGLE_OFFSET)

    fun setAngleOffset(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_ANGLE_OFFSET, v).apply()

    fun togetherMax(ctx: Context): Float = sp(ctx).getFloat(K_TOGETHER_MAX, DEFAULT_TOGETHER_MAX)

    fun setTogetherMax(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_TOGETHER_MAX, v).apply()

    fun cooldownMs(ctx: Context): Float = sp(ctx).getFloat(K_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)

    fun setCooldownMs(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_COOLDOWN_MS, v).apply()

    fun swipeMs(ctx: Context): Int = sp(ctx).getFloat(K_SWIPE_MS, DEFAULT_SWIPE_MS).toInt()

    fun setSwipeMs(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_SWIPE_MS, v).apply()

    /** 上下滑动的合成幅度（屏幕高度比例）＝实际滑动距离。 */
    fun swipeFracVertical(ctx: Context): Float =
        sp(ctx).getFloat(K_SWIPE_FRAC_V, DEFAULT_SWIPE_FRAC_V)

    fun setSwipeFracVertical(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat(K_SWIPE_FRAC_V, v).apply()

    /** 左右滑动的合成幅度（屏幕宽度比例）＝实际滑动距离。 */
    fun swipeFracHorizontal(ctx: Context): Float =
        sp(ctx).getFloat(K_SWIPE_FRAC_H, DEFAULT_SWIPE_FRAC_H)

    fun setSwipeFracHorizontal(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat(K_SWIPE_FRAC_H, v).apply()

    /** 锁定时至少要伸出的手指数（0 = 关掉这道闸门）。 */
    fun minExtendedFingers(ctx: Context): Int =
        sp(ctx).getInt(K_MIN_EXTENDED, DEFAULT_MIN_EXTENDED)

    fun setMinExtendedFingers(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(K_MIN_EXTENDED, v).apply()

    /**
     * 水平镜像 —— **在坐标层面把 x 取反**（不是只翻方向标签）。
     *
     * 前置摄像头画面本身是左右镜像的，再叠上传感器旋转，"镜像 ∘ 旋转"是一个**反射**。
     * 反射不是旋转，所以单靠方向角偏移永远修不好（把上下修对，左右必然反过来）。
     * 先在坐标上镜像一次，剩下的就是纯旋转，一个偏移量即可对齐四个方向。
     *
     * 默认打开（前置摄像头需要）。
     */
    fun mirrorX(ctx: Context): Boolean = sp(ctx).getBoolean(K_MIRROR_X, true)

    fun setMirrorX(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_MIRROR_X, v).apply()

    /**
     * 上下方向反转。
     *
     * ★ v0.17.3 起默认 **true**。依据是 README 的排障表：
     *   「只有上下反了 -> `Prefs.invertVertical` 默认改 true」。
     *   v0.17.3 真机实测反馈就是"上下滑动方向反了"，所以按该表把默认值改过来。
     *
     * 为什么必须改**默认值**而不是只改手机上那个值：这个键不在 `migrateOnce` 的
     * 强制写入列表里，所以一旦 App 数据被清（覆盖安装 debug 包就会），它就会回到
     * 代码默认。若默认仍是 false，用户重装一次方向就又反了 —— 反复复发。
     *
     * 保留这个开关的理由不变：它是零成本应急 —— 换手机/换支架后万一上下整体反了，
     * 改这一个布尔值即可。
     */
    fun invertVertical(ctx: Context): Boolean = sp(ctx).getBoolean(K_INVERT_V, true)

    fun setInvertVertical(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_INVERT_V, v).apply()

    /**
     * 调试悬浮窗。**默认关**（v2.0.0 起）。
     *
     * 为什么从"默认开"改成"默认关"：v2.0.0 的界面把「显示调试悬浮窗」藏了起来
     * （要连点「四、其他」标题 6 下才出现，勾选还要口令 0000），目的就是
     * **不要让别人看见调试信息**。默认开着就等于把面板摊开给所有人看，与目的相反。
     */
    fun debugOverlay(ctx: Context): Boolean = sp(ctx).getBoolean(K_DEBUG_OVERLAY, false)

    fun setDebugOverlay(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_DEBUG_OVERLAY, v).apply()

    /** 总开关（由 App 内按钮控制，不必去系统设置里关无障碍）。 */
    fun active(ctx: Context): Boolean = sp(ctx).getBoolean(K_ACTIVE, true)

    fun setActive(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ACTIVE, v).apply()

    // ---------------------------------------------------------------- 相机作用域

    /**
     * 相机作用域模式：
     *   "global"    = 全局（除桌面外都开）
     *   "whitelist" = 白名单（只在用户勾选的 App 里开相机）
     *
     * ★ 默认 **whitelist**（v0.17.13 起）。理由：
     *   用户的实际用法是"在固定的几个 App 里用手势"，而白名单的判据是**用户自己勾的**，
     *   不依赖任何厂商行为 —— 这比"识别是不是桌面"可靠得多：
     *   识别桌面要正确解析桌面包名并读得到 `rootInActiveWindow`，
     *   而本项目两台测试机（荣耀 AMG-AN00 / 华为 ELS-AN00）都在这上面踩过坑。
     *
     *   "一离开白名单就切断相机"同时解决了用户在桌面看到的降帧：
     *   相机不再需要在桌面上空转到接近光窗口过期（那一段最长 15 秒）。
     *
     * 空名单时 [CameraScope.shouldRunCamera] 会退化为"按全局处理"，
     * 所以新装的用户不会因为还没勾选就完全用不了。
     */
    fun cameraScope(ctx: Context): String =
        sp(ctx).getString(K_CAMERA_SCOPE, SCOPE_WHITELIST) ?: SCOPE_WHITELIST

    fun setCameraScope(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_CAMERA_SCOPE, v).apply()

    /**
     * 白名单包名集合（白名单是**唯一模式**，见 [cameraScope] 与 [CameraScope.parseMode]）。
     *
     * ★ v2.0.0：**读取时永远注入本应用自己的包名**（用户定的"出生就勾着云枢、灰阶不可撤销"）。
     *
     * 为什么这么做：[CameraScope.shouldRunCamera] 有一条兜底规则 —— 名单为空时按
     * "所有 App 都放行"处理（防"刚装还没勾就整个用不了"）。而用户要的语义是
     * **"一个都不勾 = 谁都不能用"**，与这条兜底正好相反。把"自己"永久钉在名单里，
     * 名单就永远不会为空 → 那条兜底永远够不着 → 语义与直觉一致。
     *
     * 引擎里那条兜底**刻意不动**（它和它的单测是最后一道保险，见 HANDOFF §5.2）：
     * 这里是存取层保证它够不着，而不是把保险拆掉。
     *
     * 用 `StringSet` 的原生支持存，读取时复制一份 —— SharedPreferences 返回的 Set
     * 不允许直接改（会抛 UnsupportedOperationException），而且我们要把它交给
     * CameraScope 当只读集合用。
     */
    fun cameraWhitelist(ctx: Context): Set<String> {
        val raw = sp(ctx).getStringSet(K_CAMERA_WHITELIST, emptySet()) ?: emptySet()
        val out = HashSet(raw)
        // 本应用自己永远在名单里 —— 见上面的说明；这一行就是"名单永不为空"的保证。
        out.add(ctx.packageName)
        return out
    }

    fun setCameraWhitelist(ctx: Context, pkgs: Set<String>) =
        sp(ctx).edit().putStringSet(K_CAMERA_WHITELIST, HashSet(pkgs)).apply()

    fun addToCameraWhitelist(ctx: Context, pkg: String) {
        // MutableSet explicitly: cameraWhitelist() is declared as the read-only Set
        // (callers only read it), but here we need to modify a copy before storing.
        val cur: MutableSet<String> = HashSet(cameraWhitelist(ctx))
        cur.add(pkg)
        setCameraWhitelist(ctx, cur)
    }

    fun removeFromCameraWhitelist(ctx: Context, pkg: String) {
        val cur: MutableSet<String> = HashSet(cameraWhitelist(ctx))
        cur.remove(pkg)
        setCameraWhitelist(ctx, cur)
    }

    /**
     * 接近光触发开关（用户提出的设计）。
     *
     * 打开后：只有"手贴近接近光传感器"才会连相机，窗口过期就解绑。
     * 这是目前最省电的挡位 —— 占空比接近零，且完全由用户显式触发。
     *
     * 默认 **true**：用户明确要求这个行为（"手先去接近传感器来激活"）。
     */
    fun proximityTrigger(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_PROXIMITY_TRIGGER, true)

    fun setProximityTrigger(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_PROXIMITY_TRIGGER, v).apply()

    /**
     * 接近光触发后的窗口长度（毫秒）。也是"多久没检测到手就解绑"的时长。
     *
     * ★ 15 秒（2026-09-24 用户要求缩短）：历史是 30 秒 → 20 秒 → 15 秒。
     *   窗口越长，"你离开后相机还开着"的时间就越长，占空比越高；
     *   窗口越短，"贴近一次能连做几次手势"的余量就越小。
     *   用户在真机上（华为 P40 Pro，接近光是实体传感器、调用完美）试过 30 秒后
     *   主动要求改成 20 秒；2026-09-24 又要求再缩到 15 秒。
     *
     * 注意窗口内**每次检测到手都会续期**（见 SweepAccessibilityService.onFrameModeTick），
     * 所以连续做手势不会因为窗口短而被切断。
     */
    const val PROXIMITY_WINDOW_MS = 15_000L

    /**
     * 【仅测试用】强制把接近光窗口视为已开启。
     *
     * 为什么需要：本项目的测试机（荣耀 AMG-AN00）的接近光传感器是**虚拟的** ——
     * 它挂在 `android.sensor.proximity` 上、能注册监听，但**永远只报量程值 5.00、
     * 从不产生"贴近"事件**（实测：手盖住传感器区域时环境光在实时变化，
     * 而接近光 16 小时没有一条新事件）。
     *
     * 后果：开着 `proximity_trigger` 时相机**永远不会连**，在这台机上没法测任何东西。
     * 这个开关让测试可以绕过传感器、直接验证"窗口开着时"的其余链路
     * （连相机、跳帧、识别、注入、30 秒后解绑）。
     *
     * ★ 刻意**不放进设置界面**：它不是给用户的功能，只是一个测试旁路。
     *   目标用户的实体接近光是好的，正式行为不受影响。
     */
    fun proximityForceArmed(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_PROXIMITY_FORCE, false)

    fun setProximityForceArmed(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_PROXIMITY_FORCE, v).apply()
}
