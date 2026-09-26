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
 * | 方向角偏移 | +90° | **自然竖屏（ROTATION_0）下的锚点**；横屏偏移由它 + 屏幕旋转推出（v2.3.0，见 ScreenOrientation） |
 * | 冷静期 | 1000ms | **从触发那一瞬间**起算（不是等滑动播完）；v2.3.2 由 1100 改 |
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

    /**
     * 方向角偏移的**自然竖屏锚点**（度）。
     *
     * ★ v2.3.0 语义澄清：这个值只在**屏幕处于自然竖屏（ROTATION_0）**时直接生效；
     *   横屏/倒竖屏时的偏移 = 本值 + 屏幕旋转度数（见 [ScreenOrientation.angleOffsetDeg]）。
     *   推导与依据（为什么必须是 90°、为什么横屏不能写死一个常量）写在 ScreenOrientation 顶部。
     *
     * 它是"该机型在自然方向下的几何常数"（前置 sensor 270° ⇒ 270+180=90），
     * 不是"支架角度"之类的现场标定值 —— 这也是竖屏行为在 v2.3.0 前后**逐位不变**的原因。
     */
    const val DEFAULT_ANGLE_OFFSET = 90f

    /**
     * ★冷静期（毫秒）。计时起点是**判定器触发的那一刻**（位移越过 enterThr 的那一帧），
     * 不是"合成滑动播放完"之后 —— 见 SweepDetector：触发即 `state = COOLDOWN`。
     * 这 1000ms 盖住"后半截扫动 + 顶点停顿 + 收手归位"整段，它们一次都不会被累积。
     *
     * 沿革：1300ms →（v0.17.9）1200ms →（v2.3.1）1100ms →（**v2.3.2，用户要求**）**1000ms**。
     *
     * ⚠️ 缩短冷静期 = 收窄"归位动作被吸收"的窗口。已有的实测边界（README 7.2 那张表）是
     * 在**幅度 ≤0.30 屏高**时"任何归位速度都安全"（而合成滑动幅度定稿就是 0.30），
     * 所以 1000ms 仍在安全侧；但幅度明显大于 0.35 的大开大合动作，余量已经不大
     * （实测扫描里 0.50 屏高那一档在 ~1.0s 附近就开始出现误触发，再往下就不要去了）。
     *
     * 跨语言同步：`probe/detector_ref.py` 默认值、31 个向量（`SweepVectors.kt`）都已同步（本值 1000）。
     */
    const val DEFAULT_COOLDOWN_MS = 1000f

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
            // ★ v2.3.0：这里给的是**自然竖屏锚点**，服务在每帧喂判定器之前会用
            //   `ScreenOrientation.angleOffsetDeg(屏幕旋转, 锚点)` 覆盖它（横屏要 ±90°）。
            //   留着这个初值是有意的兜底：即使覆盖那一步没跑（比如刚启动的前几帧），
            //   判定器用的也还是竖屏正确值。
            angleOffsetDeg = p.getFloat(K_ANGLE_OFFSET, DEFAULT_ANGLE_OFFSET).toDouble(),
            minExtendedFingers = p.getInt(K_MIN_EXTENDED, DEFAULT_MIN_EXTENDED),
        )
    }

    /**
     * 参数对齐（服务启动 / App 启动时各调一次）。
     *
     * ==================== v2.3.2 起改成"按值对齐"（幂等），不再用一次性布尔键 ====================
     * 理由：本项目反复踩**同一个**坑 —— `SharedPreferences` 里存着的旧值会**盖掉**代码里的新默认值。
     * 历史上每改一次定稿值就得再加一个 `migrated_vN` 键（v4 一次、v5 一次……），
     * 而漏加就会变成"代码改了、手机上没生效"这种最难查的问题（改冷静期时真踩过：
     * 手机上存着 1200，光改 `DEFAULT_COOLDOWN_MS` 一点用都没有）。
     *
     * 现在直接比"存着的值 != 代码常量就写回"：
     *   · 改多少次定稿值都自动生效，不需要再加键；
     *   · 这些参数**本来就不给用户改**（界面里没有滑块，见类注释"参数定稿并内置"），
     *     所以"代码常量是唯一真源"是符合设计的语义，而不是越权。
     *
     * ⚠️ 刻意**不碰**三个"设备相关"项：`mirror_x` / `invert_vertical` / `angle_offset_deg`。
     *    它们取决于具体机型的相机几何（前置镜像、传感器朝向），
     *    重装后回代码默认，但**绝不能**在这里被强写 —— 那会把手调好的方向搞反 180°。
     */
    fun migrateOnce(ctx: Context) {
        val p = sp(ctx)
        val e = p.edit()
        var dirty = false
        fun alignF(key: String, v: Float) {
            if (p.getFloat(key, Float.NaN) != v) {
                e.putFloat(key, v)
                dirty = true
            }
        }
        fun alignI(key: String, v: Int) {
            if (p.getInt(key, Int.MIN_VALUE) != v) {
                e.putInt(key, v)
                dirty = true
            }
        }
        alignF(K_ENTER_THR, DEFAULT_ENTER_THR)
        alignF(K_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
        alignF(K_TOGETHER_MAX, DEFAULT_TOGETHER_MAX)
        alignF(K_SWIPE_MS, DEFAULT_SWIPE_MS)
        alignF(K_SWIPE_FRAC_V, DEFAULT_SWIPE_FRAC_V)
        alignF(K_SWIPE_FRAC_H, DEFAULT_SWIPE_FRAC_H)
        alignI(K_MIN_EXTENDED, DEFAULT_MIN_EXTENDED)
        if (dirty) e.apply()
    }

    // ---------------------------------------------------------------- 存取

    fun enterThr(ctx: Context): Float = sp(ctx).getFloat(K_ENTER_THR, DEFAULT_ENTER_THR)

    fun setEnterThr(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_ENTER_THR, v).apply()

    /**
     * 方向角锚点（自然竖屏下生效的值）。**注意**：横屏时的实际偏移不是它，
     * 而是 `ScreenOrientation.angleOffsetDeg(屏幕旋转, 它)` —— 服务每帧算一次。
     */
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
     *   相机不再需要在桌面上空转到接近光窗口过期（那一段最长 8 秒）。
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
     * 接近光触发后的窗口长度（毫秒）。同时也是"多久没检测到手就解绑"的时长。
     *
     * ★ 8 秒（2026-09-25 用户要求）：历史是 30 秒 → 20 秒 → 15 秒 → 8 秒。
     *
     * 这一个数管两件事，改它等于两件事一起改：
     *   ① "贴近传感器 → 把手摆好 → 扫动"的**就位预算**；
     *   ② "最后一次检出手 → 关相机"的**死尾巴**（直接决定相机占空比）。
     * 窗口越长，"你离开后相机还开着"的时间越长；越短，"贴近一次的就位余量"越小。
     * 用户在真机上（华为 P40 Pro，接近光是实体传感器、调用完美）依次主动要求
     * 30 → 20 → 15 秒。2026-09-25 的 7h20m 日常工况实测（相机 21m15s / 66 次会话、
     * 占空比 4.84%）之后，用户要求再缩到 8 秒，理由是"手离开摄像头后最多 8 秒就解绑"。
     *
     * 注意窗口内**每次检测到手都会续期**（见 SweepAccessibilityService.onFrameModeTick），
     * 所以"手在识别姿态里就一直开着、随时能滑"不受影响，连续做手势也不用反复贴近。
     *
     * ⚠️ 已知取舍（用户 2026-09-25 已确认并选择这一档）：
     *   缩短窗口省下的电**很小**（估算 0.2~0.6 个电量点 / 7 小时，低于电量计 1% 的分辨率，
     *   实测也分辨不出来），真正会被感觉到的是"贴近后手要更快就位"。
     *   若日后要"不牺牲就位预算、只砍死尾巴"，应把这两件事拆成两个常量
     *   （ARM 窗口 15s ＋ RELEASE 窗口 5~8s）—— 见 power-conclusions.md §10 第 12 条。
     */
    const val PROXIMITY_WINDOW_MS = 8_000L

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
