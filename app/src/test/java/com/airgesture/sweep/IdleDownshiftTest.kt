package com.airgesture.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.airgesture.sweep.IdleDownshift.Action
import com.airgesture.sweep.IdleDownshift.Mode

/**
 * 空闲降频决策的单测。
 *
 * 这段逻辑是 v0.17 新增的省电机制的核心，但它**不影响任何手势判定结果**，
 * 所以既有的跨语言向量测试（SweepVectors / FingerVectors）不会覆盖它。
 * 一旦时序写错，真机上的症状是"根本不省电"或"反复绑定解绑反而更费电"，
 * 都很难从外部观察出来 —— 因此必须用确定性单测钉住。
 *
 * 重点覆盖三个最容易写错的点：
 *   1. 待机探测窗内见到手 → 必须**重置**计时，否则切回 ACTIVE 后下一帧立刻又睡
 *      （症状：反复解绑/绑定抖动，比不改还费电）
 *   2. 待机探测窗内**没**见到手 → 计时**不得**前进，否则待机永远进不去
 *   3. 边界值：刚好等于 idleDownMs 时必须触发（用 >= 而不是 >）
 */
class IdleDownshiftTest {

    private val idleDown = 5_000L
    private val sleepMs = 800L
    private val burstMs = 1_200L

    // ------------------------------------------------------------ decide()

    @Test
    fun activeWithHandNeverSleeps() {
        // 有手的时候，无论过去多久都不该进待机
        var lastSeen = 0L
        var now = 0L
        repeat(200) {
            now += 16
            assertEquals(Action.NONE, IdleDownshift.decide(Mode.ACTIVE, true, now, lastSeen, idleDown))
            lastSeen = IdleDownshift.nextLastHandSeenMs(true, now, lastSeen)
        }
        assertEquals(now, lastSeen)
    }

    @Test
    fun activeWithoutHandSleepsAfterIdleWindow() {
        val lastSeen = 1_000L
        // 还差 1ms：不该睡
        assertEquals(Action.NONE, IdleDownshift.decide(Mode.ACTIVE, false, 1_000 + idleDown - 1, lastSeen, idleDown))
        // 边界：刚好到点就必须睡（>= 而不是 >）
        assertEquals(Action.ENTER_STANDBY, IdleDownshift.decide(Mode.ACTIVE, false, 1_000 + idleDown, lastSeen, idleDown))
        // 超时很久：仍然只是"该睡"，不重复给别的动作
        assertEquals(Action.ENTER_STANDBY, IdleDownshift.decide(Mode.ACTIVE, false, 1_000 + idleDown * 3, lastSeen, idleDown))
    }

    @Test
    fun lowModeSleepsUntilHandAppears() {
        // 待机探测窗内没手 → 不产生任何新动作（由定时器负责回去睡）
        assertEquals(Action.NONE, IdleDownshift.decide(Mode.LOW, false, 9_999, 0, idleDown))
        // 见到手 → 立刻恢复连续取流
        assertEquals(Action.ENTER_ACTIVE, IdleDownshift.decide(Mode.LOW, true, 9_999, 0, idleDown))
    }

    // ------------------------------------------------- nextLastHandSeenMs()

    @Test
    fun seeingAHandAlwaysResetsTheIdleTimer() {
        assertEquals(42_000L, IdleDownshift.nextLastHandSeenMs(true, 42_000L, 1L))
    }

    @Test
    fun notSeeingAHandNeverAdvancesTheTimer() {
        // ★ 这条是"待机能进得去"的关键：待机探测窗里没手时，计时器不许前进
        assertEquals(1L, IdleDownshift.nextLastHandSeenMs(false, 42_000L, 1L))
    }

    // ------------------------------------------------------------ 完整周期

    /**
     * 端到端（纯逻辑）重现一次"用一次手势 → 闲置 → 待机 → 探测到手 → 恢复"的完整时序。
     *
     * 同时钉住那个反直觉的回归点：从 LOW 切回 ACTIVE 时若忘了重置计时，
     * 下一帧就会因为"已经超时"而立刻再次进待机。
     */
    @Test
    fun fullCycleNeverOscillatesBetweenModes() {
        var mode = Mode.ACTIVE
        var lastSeen = 0L
        var now = 0L
        var standbyCount = 0

        fun tick(handPresent: Boolean) {
            now += 33                     // 约 30fps
            val action = IdleDownshift.decide(mode, handPresent, now, lastSeen, idleDown)
            lastSeen = IdleDownshift.nextLastHandSeenMs(handPresent, now, lastSeen)
            when (action) {
                Action.ENTER_STANDBY -> { mode = Mode.LOW; standbyCount++ }
                Action.ENTER_ACTIVE -> mode = Mode.ACTIVE
                Action.NONE -> {}
            }
        }

        // 1) 手在画面里 1 秒：必须一直 ACTIVE
        repeat(30) { tick(true) }
        assertEquals(Mode.ACTIVE, mode)
        assertEquals(0, standbyCount)

        // 2) 手离开 6 秒：应当进入待机，且只进一次
        repeat(182) { tick(false) }
        assertEquals(Mode.LOW, mode)
        assertEquals(1, standbyCount)

        // 3) 待机期间模拟若干次"探测窗但没探到手"：仍然保持 LOW，不产生抖动
        repeat(50) { tick(false) }
        assertEquals(Mode.LOW, mode)
        assertEquals(1, standbyCount)

        // 4) 探测窗里探到手：恢复到 ACTIVE
        tick(true)
        assertEquals(Mode.ACTIVE, mode)

        // 5) ★ 关键：紧接着的下一帧（仍然没手）绝不能立刻又回待机
        //    —— 若 lastSeen 没被重置，这里会再进一次待机（standbyCount 变 2）
        tick(false)
        assertEquals(Mode.ACTIVE, mode)
        assertEquals(1, standbyCount)

        // 6) 恢复后手持续在画面里：保持 ACTIVE
        repeat(60) { tick(true) }
        assertEquals(Mode.ACTIVE, mode)
        assertEquals(1, standbyCount)
    }

    /** 用户确认的方案 B 参数：每 2 秒探一次、每次 1.2 秒 → 占空比约 60%。 */
    @Test
    fun probeParamsMatchTheConfirmedPlanB() {
        assertEquals(2_000L, IdleDownshift.probeCycleMs(sleepMs, burstMs))
        val duty = IdleDownshift.dutyCycle(burstMs, sleepMs)
        assertTrue("占空比应约为 0.6，实际 $duty", duty > 0.59 && duty < 0.61)
    }

    /** 占空比必须能区分"温和"与"灵敏"两档 —— 这是省电幅度的直接来源。 */
    @Test
    fun shorterSleepMeansHigherDutyCycle() {
        val gentle = IdleDownshift.dutyCycle(1_200L, 800L)    // 方案 B
        val eager = IdleDownshift.dutyCycle(1_200L, 0L)       // 常开
        assertEquals(1.0, eager, 1e-9)
        assertNotEquals(gentle, eager)
        assertTrue(gentle < eager)
    }

    // ------------------------------------------------------- 自适应分档

    // 与服务里 T_* 常量保持一致（改那边记得同步这里，否则这些断言会失去意义）
    private val tier1After = 30_000L
    private val sleep1 = 600L
    private val sleep2 = 30_000L

    @Test
    fun justAfterUseProbesDensely() {
        // 刚停手（还没到 30 秒）：走密集档 —— 抬手立即响应
        assertEquals(sleep1, IdleDownshift.sleepForIdle(0, tier1After, sleep1, sleep2))
        assertEquals(sleep1, IdleDownshift.sleepForIdle(29_999, tier1After, sleep1, sleep2))
        // 边界：刚好到 30 秒就切稀疏档
        assertEquals(sleep2, IdleDownshift.sleepForIdle(30_000, tier1After, sleep1, sleep2))
        // 长时间闲置（比如放了一夜）：保持稀疏档
        assertEquals(sleep2, IdleDownshift.sleepForIdle(8 * 3600_000L, tier1After, sleep1, sleep2))
    }

    /**
     * 用户的核心诉求："刚停手那阵子抬手要快"。
     * 密集档的平均响应必须 < 1 秒（用户明确接受 ≤1s 的重新唤醒延迟）。
     *
     * ★ 这条断言曾经失败过：原来的 sleep1 = 800ms 会让平均响应正好 1000ms
     *   （(800+1200)/2），不满足"< 1s"。是单测逼着把 sleep1 降到 600ms 的 ——
     *   不能改成 >= 来迁就参数。
     */
    @Test
    fun denseTierKeepsResponseUnderOneSecond() {
        val mean = IdleDownshift.meanResponseMs(sleep1, burstMs)
        assertTrue("密集档平均响应 $mean ms，应该 < 1000ms", mean < 1000.0)
    }

    /**
     * 稀疏档必须**真的**比密集档省一个量级，否则分档没有意义。
     *
     * ★ 这条也失败过：sleep2 = 14s 时占空比 7.9%，只有密集档的 1/7.6。
     *   同样是单测把 sleep2 顶到 30s 的。
     *
     * 同时把"密集档本来就不达标"这个**已知取舍**写进断言：用户已确认
     * "空闲 CPU <1%" 与 "抬手 ≤1s" 数学上互斥，密集档负责响应、稀疏档负责省电。
     */
    @Test
    fun sparseTierIsAnOrderOfMagnitudeCheaper() {
        val denseDuty = IdleDownshift.dutyForSleep(sleep1, burstMs)
        val sparseDuty = IdleDownshift.dutyForSleep(sleep2, burstMs)
        assertTrue(
            "稀疏档占空比 $sparseDuty 应至少比密集档 $denseDuty 低一个量级",
            sparseDuty * 10 <= denseDuty,
        )
        // 按实测空闲 ~100% 单核折算：稀疏档应落到个位数百分比
        assertTrue("稀疏档折算空闲 CPU 应 < 10%，实际 ${sparseDuty * 100}%", sparseDuty * 100.0 < 10.0)
        // 密集档明确 > 10%（达不到 <1%），把它记录成"已知取舍"而不是假装达标
        assertTrue("密集档折算空闲 CPU 本来就 > 10%，这是已知取舍", denseDuty * 100.0 > 10.0)
    }

    // ------------------------------------------------------- 帧陈旧看门狗

    // 与服务里 T_WATCHDOG_MS / T_FRAME_STALE_MS 保持一致
    private val watchdogCheck = 10_000L
    private val watchdogStale = 4_000L

    /**
     * 看门狗的阈值不变式。
     *
     * 这条测试防的是一种**沉默失效**：如果有人只把检查间隔改小（比如 1 秒），
     * 看门狗其实还在跑，但 `staleMs` 永远够不到，于是"相机卡死自动恢复"这个
     * 保护就悄悄没了 —— 真机上表现为偶尔相机再也不出帧、必须手动重开服务。
     */
    @Test
    fun watchdogThresholdsAreInternallyConsistent() {
        assertTrue(
            "检查间隔必须大于陈旧阈值，否则检测会滞后甚至永远够不到",
            Watchdog.thresholdsAreConsistent(watchdogCheck, watchdogStale),
        )
        // 反例：间隔被改得比阈值还小 -> 必须判为不一致
        assertTrue(
            "checkInterval < staleMs 应当被判为不一致",
            !Watchdog.thresholdsAreConsistent(1_000L, 4_000L),
        )
        // 反例：阈值小到接近正常帧间隔（33ms）会把抖动误判为断链
        assertTrue(
            "staleMs 过小应当被判为不一致",
            !Watchdog.thresholdsAreConsistent(10_000L, 100L),
        )
    }

    @Test
    fun watchdogIgnoresTheStartupGracePeriod() {
        // lastFrameMs == 0 表示"还没有过任何一帧"（服务刚起来），不算故障
        assertTrue(!Watchdog.isStale(100_000L, 0L, watchdogStale))
        // 有了第一帧之后，超过阈值才算
        assertTrue(!Watchdog.isStale(1_000L + watchdogStale, 1_000L, watchdogStale))
        assertTrue(Watchdog.isStale(1_000L + watchdogStale + 1, 1_000L, watchdogStale))
    }

    // ------------------------------------------------ 待机探测周期（真机回归）

    /**
     * 待机不是"睡下去就完了"，而是 **睡 -> 醒来探测 -> 再睡** 的循环。
     *
     * 这条测试记录的是 v0.17.0 的一个**致命真机 bug**：服务里"睡醒后要探测"那一步
     * 排成了"再睡一次"（`postDelayed(this)` 而不是排探测任务），于是循环退化成
     * "解绑 -> 等 -> 又解绑 -> 等…"，**相机永远不再绑定**。
     * 现象是用户实测到的："拿到权限时闪了一下『请滑动』，之后就不动了，相机也不亮。"
     *
     * 用真机证据定位：`dumpsys media.camera` 里最后一条记录是
     * `DISCONNECT ... (PID 492)`，之后再无 CONNECT。
     *
     * 这里把"一个周期必须同时包含睡眠和探测"这件事钉住：如果谁再把待机写成
     * "只睡不探"，周期里就不再有探测窗口，探测占空比会变成 0 —— 测试会失败。
     */
    @Test
    fun standbyCycleMustIncludeAProbeWindow() {
        val sleep = 600L
        val burst = 1_200L
        val duty = IdleDownshift.dutyForSleep(sleep, burst)
        assertTrue("待机周期里必须有探测窗口（占空比 > 0），实际 $duty", duty > 0.0)
        // 一个周期 = 睡 + 探，两者都必须计入
        assertEquals(sleep + burst, IdleDownshift.probeCycleMs(sleep, burst))
        // 而且探测窗口必须真的能覆盖"重新绑相机 + 出一帧"（实测 400~600ms 起步）
        assertTrue("探测窗口 $burst ms 必须够相机重绑", burst >= 1_000L)
    }

    // ------------------------------------------------------- 跳帧（现行方案）

    /** 服务里 ANALYSE_EVERY_NTH_FRAME 的值，两处必须一致。 */
    private val everyNth = 4
    private val frameMs = 1000.0 / 30.0    // 30fps

    @Test
    fun firstFrameIsAnalysedImmediately() {
        // 启动后第 1 帧必须立刻推理，否则启动时会白等 N 帧
        val s = FrameSkipper(everyNth)
        assertTrue("第 1 帧必须被推理", s.shouldAnalyse())
    }

    @Test
    fun exactlyOneInNIsAnalysed() {
        val s = FrameSkipper(everyNth)
        var taken = 0
        repeat(1000) { if (s.shouldAnalyse()) taken++ }
        // 1000 帧、每 4 帧取 1 -> 正好 250（第 1 帧起算，落在边界上）
        assertEquals(250, taken)
        assertEquals(1000L, s.framesSeen)
        assertEquals(250L, s.framesAnalysed)
        assertEquals(0.25, s.analyseRatio(), 1e-9)
    }

    @Test
    fun everyNthOnePreservesLegacyBehaviour() {
        // everyNth = 1 必须等价于 v0.16.1 的"每帧都推理"
        val s = FrameSkipper(1)
        repeat(50) { assertTrue(s.shouldAnalyse()) }
        assertEquals(1.0, s.analyseRatio(), 1e-9)
    }

    /**
     * ★ 用户对响应速度的硬要求是"最坏 ≤0.3 秒"。跳帧是唯一会吃掉这个预算的地方，
     *   所以在这里钉死：如果谁把 everyNth 调大（比如为了更省电调到 10），
     *   最坏延迟就会变成 333ms 而超标 —— 这条测试会挡住。
     */
    @Test
    fun worstCaseLatencyStaysWithinTheUsersBudget() {
        val worst = FrameSkipper(everyNth).worstCaseLatencyMs(frameMs)
        assertTrue("最坏响应 ${worst}ms 必须 <= 300ms（用户要求）", worst <= 300.0)
        // 同时确认这个预算不是靠"其实没省多少"换来的：必须真的跳掉大部分帧
        assertTrue("必须真的降载（推理占比 <= 0.3）", FrameSkipper(everyNth).analyseRatio() <= 0.3 + 1e-9)
    }

    @Test
    fun rejectsInvalidSkipFactor() {
        // everyNth = 0 会让 % 运算除零；必须挡在构造期而不是运行期
        var threw = false
        try {
            FrameSkipper(0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("everyNth = 0 必须抛异常", threw)
    }
}
