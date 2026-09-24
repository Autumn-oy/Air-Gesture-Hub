package com.airgesture.sweep

/**
 * 空闲降频的**决策逻辑**（纯 Kotlin，无 Android 依赖，可跑纯 JVM 单测）。
 *
 * 为什么把它抽出来：这段逻辑决定"什么时候解绑相机"，一旦时序写错，症状是
 * "相机永远不解绑"（不省电）或者"刚解绑就立刻又绑回来"（反复抖动，比原来还费电）。
 * 这两种错误在真机上都不容易看出来，所以必须有确定性单测钉住。
 *
 * 与 `SweepAccessibilityService` 的分工：
 *   · 这里只回答"下一步该做什么"（[Action]），不碰相机、不碰 Handler；
 *   · 服务负责执行动作（绑定/解绑）并把每帧的 `handPresent` 喂进来。
 *
 * 状态只有两个：
 * ```
 *   ACTIVE（连续取流）--连续 idleDownMs 没见到手--> LOW（待机：解绑 + 定时探测）
 *   LOW --探测窗内见到手--> ACTIVE
 * ```
 *
 * ★ 关键约定：`lastHandSeenMs` **只在 ACTIVE 模式下见到手时才前进**。
 *   如果待机探测窗里的零星帧也去更新它，待机就永远进不去（每探到一次就重置计时）。
 *   反之，从 LOW 回到 ACTIVE 时它必须被重置，否则下一帧立刻又判定"超时该睡了"。
 */
object IdleDownshift {

    /** 取流模式。与 `SweepAccessibilityService.Mode` 一一对应。 */
    enum class Mode { ACTIVE, LOW }

    /** 决策结果：调用方据此执行副作用。 */
    enum class Action {
        /** 什么都不做。 */
        NONE,

        /** 解绑相机，进入待机。 */
        ENTER_STANDBY,

        /** 见到手，恢复连续取流（必要时重新绑定相机）。 */
        ENTER_ACTIVE,
    }

    /**
     * 判定下一步动作。
     *
     * @param mode          当前模式
     * @param handPresent   本帧是否检测到手
     * @param nowMs         单调时钟（毫秒）
     * @param lastHandSeenMs 上一次**在 ACTIVE 模式下**见到手的时刻
     * @param idleDownMs    连续多久没见到手就进入待机
     */
    fun decide(
        mode: Mode,
        handPresent: Boolean,
        nowMs: Long,
        lastHandSeenMs: Long,
        idleDownMs: Long,
    ): Action = when (mode) {
        Mode.ACTIVE -> when {
            handPresent -> Action.NONE
            // 注意是 >=：真机上 idleDownMs 的边界必须能触发，否则时间被无限推迟
            nowMs - lastHandSeenMs >= idleDownMs -> Action.ENTER_STANDBY
            else -> Action.NONE
        }
        Mode.LOW -> if (handPresent) Action.ENTER_ACTIVE else Action.NONE
    }

    /**
     * 喂入一帧后的新 `lastHandSeenMs`。
     *
     * 规则（很容易写错，所以单独成函数并由单测覆盖）：
     *   · 只要见到手 —— 不管当前是 ACTIVE 还是 LOW —— 都重置为 `nowMs`
     *     （LOW 下见到手意味着马上要切 ACTIVE，计时必须从此刻重新开始）；
     *   · 没见到手时**保持不变**，绝不前进。
     */
    fun nextLastHandSeenMs(handPresent: Boolean, nowMs: Long, lastHandSeenMs: Long): Long =
        if (handPresent) nowMs else lastHandSeenMs

    /** 探测窗口 + 睡眠时间 = 一个完整探测周期的长度（诊断用）。 */
    fun probeCycleMs(sleepMs: Long, burstMs: Long): Long = sleepMs + burstMs

    /** 待机期间的相机占空比（0~1）。占空比越低越省电，但探测越稀。 */
    fun dutyCycle(burstMs: Long, sleepMs: Long): Double {
        val cycle = probeCycleMs(sleepMs, burstMs)
        return if (cycle <= 0L) 0.0 else burstMs.toDouble() / cycle.toDouble()
    }

    /**
     * 探测间隔的**自适应分档**（用户确认的方案）。
     *
     * 为什么不是一个固定间隔：响应速度与省电在数学上互斥 ——
     * "探手"必须跑完整推理，所以探得越勤越费电（实测空闲 ~100% 单核，
     * 探 1200/睡 800 的占空比 60% 就只能降到 ~60%）。而用户的实际用法是
     * "一天用不了几次"：**刚刚用完的那段时间**最可能马上再用一次，
     * 之后大概率是长时间闲置。
     *
     * 于是按"停手已多久"给两档：
     * ```
     *   停手 0 ~ tier1AfterIdleMs ：每 (sleep1 + burst) 探一次  -> 抬手立即响应
     *   停手 > tier1AfterIdleMs   ：每 (sleep2 + burst) 探一次  -> 长时间闲置时省电
     * ```
     * 一旦探到手就重置计时（变回密集档），所以"抬手就能用"只在你真的在用时生效。
     *
     * ★ 注意：这里只决定"睡多久"，**不改变探测窗口本身的行为** ——
     *   探测帧与正常帧走的是同一条 analyze/onResult 路径，判定结果不受分档影响。
     *
     * @param idleForMs 距离上一次"在 ACTIVE 模式下见到手"已经过去多久
     */
    fun sleepForIdle(
        idleForMs: Long,
        tier1AfterIdleMs: Long,
        sleep1Ms: Long,
        sleep2Ms: Long,
    ): Long = if (idleForMs < tier1AfterIdleMs) sleep1Ms else sleep2Ms

    /** 该分档下的平均响应延迟 ≈ 半个探测周期（手随机时刻抬起的期望等待）。 */
    fun meanResponseMs(sleepMs: Long, burstMs: Long): Double =
        probeCycleMs(sleepMs, burstMs) / 2.0

    /** 该分档下的相机占空比。 */
    fun dutyForSleep(sleepMs: Long, burstMs: Long): Double = dutyCycle(burstMs, sleepMs)
}

/**
 * 帧跳过器：让相机全程绑定，但只对每 N 帧做一次推理。
 *
 * ## 为什么是这个方案（而不是"解绑相机 + 定时唤醒"）
 *
 * 原方案（[IdleDownshift]）在真机上被证明**体验不可接受**：解绑后重绑本身要
 * 400~600ms，为了省电只能把探测间隔拉长，用户实测"伸手后要等很久"，而且
 * v0.17.0 还因为"睡醒后忘了探测"导致相机永不重绑、功能彻底失效。
 *
 * PC 侧分辨率实验（`probe/test_resolution_limit.py`，用真机录制的帧）给出关键事实：
 * **降分辨率对检出率没有伤害**（缩到 0.35 仍是 100% 检出），因为 MediaPipe 内部
 * 会把输入归一化到 192x192。所以"省电"不能靠在空间上降质，只能靠在**时间上少算**。
 *
 * 于是：
 * ```
 *   相机：全程绑定（不 unbind）      -> 没有重绑延迟
 *   推理：每 N 帧一次                -> 推理开销降到 1/N
 *   响应：最坏 = N 个帧间隔          -> 30fps、N=4 时约 133ms
 * ```
 * 相机管线本身那部分开销（取流/HAL）省不掉 —— 这是"要快"必须付的代价，已被用户接受
 * （他选择"尽力最低，不设硬指标"，而把响应放在首位：最坏 ≤0.3s）。
 *
 * ## 为什么必须是"纯逻辑"
 *
 * 跳帧直接决定"多久能看到手"，是体验的核心参数；写错了表现为响应变慢或推理频率
 * 没降下来，两种都很难从外部观察。所以计数与判定放在这里，用单测钉住。
 */
class FrameSkipper(
    /** 每多少帧做一次推理。1 = 每帧都推理（v0.16.1 的行为）。 */
    val everyNth: Int = 1,
) {
    private var seen = 0L
    private var analysed = 0L

    init {
        require(everyNth >= 1) { "everyNth must be >= 1, got $everyNth" }
    }

    /** 这一帧要不要送去推理。 */
    fun shouldAnalyse(): Boolean {
        seen++
        // 计数从刚开始就生效：第 1 帧立即推理，避免启动时白等 N 帧
        val take = ((seen - 1) % everyNth) == 0L
        if (take) analysed++
        return take
    }

    val framesSeen: Long get() = seen
    val framesAnalysed: Long get() = analysed

    /** 实际推理的帧占比（1/everyNth 的理想值）。 */
    fun analyseRatio(): Double = if (seen == 0L) 0.0 else analysed.toDouble() / seen.toDouble()

    /**
     * 最坏响应延迟（毫秒）：手在第 1 帧刚过时伸进来，要等到下一次推理。
     * @param frameIntervalMs 相机帧间隔（30fps 约 33ms）
     */
    fun worstCaseLatencyMs(frameIntervalMs: Double): Double = frameIntervalMs * everyNth
}

/**
 * 相机作用域：决定"当前这一刻该不该开相机"。
 *
 * ## 为什么需要它（用户提的关键洞察）
 *
 * 真机实测的总账（v0.17.2，跳帧 1/4）：空闲 CPU 从 97% 降到 51.7%。
 * 但跳帧只省掉「推理 + 它带来的 GC」，**省不掉「相机一直在取流」** ——
 * 线程级数据里 CameraX + 相机 HAL 那 13~16% 一分没少，
 * 内存上还占着 `GL mtrack 80MB / Graphics 87MB`。
 *
 * 想再往下压，唯一的路是**在不需要的时候根本不绑相机**。
 * 而"不需要"有一个用户已经确认的判定依据：**手机桌面**。
 * 桌面本来就不响应隔空手势（[com.airgesture.sweep.SwipeInjector] 里固定跳过），
 * 所以桌面上开着相机纯粹是浪费。用户原话："桌面关是必须的"。
 *
 * ## 两种模式（用户指定）
 *
 * - [Mode.GLOBAL]：全局模式，相机一直开（= v0.16.1 行为）**但桌面仍然关闭**。
 * - [Mode.WHITELIST]：白名单模式，只有用户勾选的 App 里才开相机。
 *
 * ## 边界条件（这些是易错点，已由单测钉住）
 *
 * 1. **取不到前台包名时按"允许"处理**。取不到可能只是系统一时没给节点，
 *    此时若判成"不允许"会让手势莫名失效 —— 失效比多耗一点电严重得多。
 * 2. **白名单为空时按全局处理**。否则用户一开白名单模式、还没勾任何 App，
 *    手势就整个不工作，看起来像坏了。
 * 3. 桌面判定优先于白名单：即使桌面被误勾进白名单，也不开相机。
 */
object CameraScope {

    enum class Mode { GLOBAL, WHITELIST }

    /**
     * 解析用户存的模式字符串。
     *
     * ★ v1.0.0 起**只支持白名单**：无论存的是什么（包括旧版本留下的 "global"），
     *   一律按 [Mode.WHITELIST] 处理。理由（用户决定）：
     *   "将白名单模式作为这个软件唯一的模式" —— 全局模式会让相机在所有 App 里常开，
     *   与这个项目的省电目标直接冲突，留着一个能把自己耗电的挡位没有意义。
     *
     *   保留 [Mode] 枚举本身（而不是删掉 GLOBAL）是为了让 [shouldRunCamera] 的
     *   既有单测与调用点不必大改；实际永远不会再返回 GLOBAL。
     */
    fun parseMode(raw: String?): Mode = Mode.WHITELIST

    /**
     * 当前该不该开相机。
     *
     * @param mode           用户选的模式
     * @param foregroundPkg  当前前台 App 包名；null = 取不到
     * @param homePkg        桌面包名；null = 没解析出来
     * @param whitelist      白名单包名集合（仅在 [Mode.WHITELIST] 下有意义）
     */
    fun shouldRunCamera(
        mode: Mode,
        foregroundPkg: String?,
        homePkg: String?,
        whitelist: Set<String>,
    ): Boolean {
        // 1) 桌面上永远不开 —— 用户明确要求，且桌面本来就不响应手势
        if (foregroundPkg != null && homePkg != null && foregroundPkg == homePkg) return false

        // 2) 全局模式：桌面上已经挡掉了，其余一律开
        if (mode == Mode.GLOBAL) return true

        // 3) 白名单模式
        if (foregroundPkg == null) return true              // 取不到 -> 放行（见边界条件 1）
        if (whitelist.isEmpty()) return true                // 还没勾选 -> 按全局（见边界条件 2）
        return whitelist.contains(foregroundPkg)
    }

    /** 反向：当前是否因为"不在作用域内"而关着相机。用于把原因显示给用户。 */
    fun offReason(
        mode: Mode,
        foregroundPkg: String?,
        homePkg: String?,
        whitelist: Set<String>,
    ): String? {
        if (shouldRunCamera(mode, foregroundPkg, homePkg, whitelist)) return null
        if (foregroundPkg != null && homePkg != null && foregroundPkg == homePkg) return "桌面"
        return "不在白名单"
    }

    /**
     * 前台包名的**分层选择**（纯逻辑，可单测；这是被真机 bug 逼出来的规则）。
     *
     * ```
     *   新鲜的"全屏窗口事件"包名  -> 优先（切换那一刻就准，且不依赖读节点树）
     *   否则用 rootInActiveWindow  -> 次选
     *   都没有                    -> null（调用方按"放行"处理，避免失灵）
     * ```
     *
     * ★ 为什么新鲜事件包名优先：
     *   **华为桌面上 `rootInActiveWindow` 不可靠** —— 实测按 HOME 后约 4 秒它才反映出
     *   桌面，只靠它会让"退出白名单 App 后相机 4 秒才断"。窗口事件在切换那一刻就带着
     *   正确包名。
     *
     * ★ 为什么必须是"新鲜 + 全屏"的：
     *   · **全屏**（调用方用 `AccessibilityEvent.isFullScreen` 过滤）：输入法弹出、
     *     系统小窗、通知栏也会发窗口事件并带自己的包名，拿它们比白名单会在白名单 App 里
     *     误判成"不在白名单"而关相机 —— 这就是 v0.17.14 的真机 bug（进 App 约 1 秒被断）。
     *   · **新鲜**（调用方用时间窗过滤）：事件包名只在刚切换那一刻可靠。
     *
     * 注意参数顺序：**第一个参数优先级更高**（历史上曾写反过，导致桌面断开慢 4 秒）。
     */
    fun pickForegroundForWhitelist(preferredPkg: String?, fallbackPkg: String?): String? =
        preferredPkg?.takeIf { it.isNotEmpty() }
            ?: fallbackPkg?.takeIf { it.isNotEmpty() }
}

/**
 * 接近光触发器：用"手贴近传感器"当**显式的开机开关**。
 *
 * ## 为什么是这个方案（用户提出的设计，比自动推断更省电）
 *
 * 通用接近光的量程只有约 **5 厘米**（实测本机报告值恒为 5.00），
 * 所以它**不能**用来识别 20~30cm 处的手势 —— 但它**不需要**：
 * 它只负责回答"用户是不是打算现在用手势"。
 *
 * ```
 *   手贴近传感器(<5cm)  ->  开一个 T 秒的窗口，相机连上
 *   手退回 20~30cm 做手势 ->  正常识别
 *   窗口内没做手势 / 做完手势 ->  解绑相机，回到关闭状态
 *   想再用  ->  再贴近一次
 * ```
 *
 * 这正好合上用户的真实流程（"伸手 → **停稳** → 看到请滑动 → 扫"）：
 * 那个"停稳"的停顿本来就有，所以窗口开启后的一点延迟不会被感知。
 * 而且它把占空比压到接近零 —— 不需要任何"定时探测"（那才是耗电和延迟的根源）。
 *
 * ## 为什么单独抽成纯逻辑
 *
 * 这里的时序错了会表现成两种相反的坏结果：
 * 窗口太短 -> 手退回去相机已经关了，手势时好时坏；
 * 窗口不会过期 -> 相机永远不会关，等于白做。
 * 两种都很难从外部观察，所以用单测钉住。
 */
object ProximityTrigger {

    /**
     * 接近光读数是否算"贴近"。
     *
     * 本机（荣耀 AMG-AN00）的接近光量程约 5cm，**远离时也报 5.00**
     * （实测 `dumpsys sensorservice` 里恒为 `5.00, 0.00, 0.00`），
     * 所以判据必须是"**严格小于**量程附近的一个阈值"，
     * 不能写成 `<= 5` 之类 —— 那样远离时也会判成贴近，窗口永不关闭。
     */
    fun isNear(value: Float, maxRange: Float): Boolean {
        if (value.isNaN()) return false
        val threshold = maxRange * 0.6f      // 5cm 量程 -> 3cm
        return value < threshold
    }

    /**
     * 拿到一次读数后，窗口该不该开/续期。
     *
     * @param currentlyArmed 当前窗口是否开着
     * @param near           本次读数是否算贴近
     * @param handPresent    本帧是否检测到手
     *
     * 规则：
     *   · 贴近 -> 开窗（并续期）
     *   · 窗口内检测到手 -> 续期（连续做几次手势不用反复贴近）
     *   · 其余 -> 不动（由 [isExpired] 判断过期）
     */
    fun shouldArm(currentlyArmed: Boolean, near: Boolean, handPresent: Boolean): Boolean =
        near || (currentlyArmed && handPresent)

    /**
     * 窗口是否已过期。
     *
     * @param armed     窗口是否开着
     * @param nowMs     单调时钟
     * @param armedAtMs 窗口最近一次续期的时刻
     * @param windowMs  窗口长度
     */
    fun isExpired(armed: Boolean, nowMs: Long, armedAtMs: Long, windowMs: Long): Boolean =
        armed && (nowMs - armedAtMs) >= windowMs
}

/**
 * 相机作用域的**判定节流**（纯逻辑，可单测）。
 *
 * 为什么需要节流：窗口事件可能密集到达（返回桌面时系统会连报好几个窗口变化），
 * 而"解绑/重绑相机"是几百毫秒级的重操作（实测重绑要 300~600ms）。
 * 不做节流的话，快速切 App 会让相机反复解绑重绑 ——
 * **那比少量卡顿严重得多**，所以这个下限必须有。
 */
object ScopeThrottle {

    /**
     * 这次判定该不该执行。
     *
     * @param nowMs      当前单调时钟
     * @param lastMs     上次真正执行判定的时刻（0 = 从未执行）
     * @param minGapMs   两次判定之间的最小间隔
     */
    fun shouldRun(nowMs: Long, lastMs: Long, minGapMs: Long): Boolean =
        lastMs == 0L || (nowMs - lastMs) >= minGapMs
}

/**
 * 帧陈旧看门狗的**阈值不变式**（纯 Kotlin，可单测）。
 *
 * 看门狗本身活在服务里（要碰 Handler / SystemClock），但它成立的条件是几条
 * 数值关系；这些关系一旦被单独改坏，看门狗就会**默默失效**（再也不触发），
 * 而症状只是"偶尔相机卡死没人管"，极难发现。所以把它们抽到这里用单测钉住。
 */
object Watchdog {
    /**
     * @param checkIntervalMs 看门狗自己的检查间隔
     * @param staleMs         连续多久没有帧就算断了
     */
    fun isStale(nowMs: Long, lastFrameMs: Long, staleMs: Long): Boolean {
        // lastFrameMs == 0 表示"还没有过任何一帧"，那是启动宽限期，不算故障
        if (lastFrameMs == 0L) return false
        return (nowMs - lastFrameMs) > staleMs
    }

    /**
     * 阈值必须满足的不变式。
     *
     * 1. `staleMs < checkIntervalMs`：否则一次检查到下一次检查之间就积压了超过
     *    一个 stale 窗口，检测会明显滞后（甚至永远差一点点够不到）。
     * 2. `staleMs` 必须显著大于一个正常帧间隔，否则会把正常的帧抖动误判为断链。
     *    正常帧间隔 ~33ms（30fps），待机探测是断续的，取 4000ms 有百倍余量。
     */
    fun thresholdsAreConsistent(checkIntervalMs: Long, staleMs: Long): Boolean =
        staleMs > 0L &&
            checkIntervalMs > staleMs &&
            staleMs >= 1_000L
}
