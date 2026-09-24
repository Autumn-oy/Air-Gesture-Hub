package com.airgesture.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.airgesture.sweep.CameraScope.Mode

/**
 * 相机作用域（桌面/白名单决定开不开相机）的单测。
 *
 * 这个功能的目的是**省电**，但它一旦判错，症状是"手势在某些 App 里莫名其妙不工作" ——
 * 用户会以为是功能坏了，而不会想到是省电策略误伤。所以三条边界必须钉死：
 *
 *   1. 取不到前台包名 -> 必须**放行**（失效比多耗电严重得多）
 *   2. 白名单为空 -> 必须当作全局（否则刚开白名单模式、还没勾选，手势就全废）
 *   3. 桌面优先级最高（即使桌面被误勾进白名单也不开相机）
 */
class CameraScopeTest {

    private val home = "com.hihonor.android.launcher"

    // ------------------------------------------------------------ 桌面（用户的硬要求）

    @Test
    fun launcherNeverRunsTheCamera() {
        // 用户原话："桌面关是必须的"
        assertFalse(
            "全局模式下桌面上也必须关相机",
            CameraScope.shouldRunCamera(Mode.GLOBAL, home, home, emptySet()),
        )
        assertFalse(
            "白名单模式下桌面上同样关相机",
            CameraScope.shouldRunCamera(Mode.WHITELIST, home, home, setOf(home)),
        )
        // 即使桌面把自己写进了白名单，也不开（边界 3）
        assertFalse(
            "桌面被误勾进白名单也不开",
            CameraScope.shouldRunCamera(Mode.WHITELIST, home, home, setOf(home, "com.foo")),
        )
        assertEquals("桌面", CameraScope.offReason(Mode.GLOBAL, home, home, emptySet()))
    }

    // ------------------------------------------------------------ 全局模式

    @Test
    fun globalModeRunsEverywhereExceptLauncher() {
        for (pkg in listOf("com.tencent.mm", "com.ss.android.ugc.aweme", "com.android.settings")) {
            assertTrue(
                "全局模式下 $pkg 应开相机",
                CameraScope.shouldRunCamera(Mode.GLOBAL, pkg, home, emptySet()),
            )
        }
        assertNull(CameraScope.offReason(Mode.GLOBAL, "com.tencent.mm", home, emptySet()))
    }

    // ------------------------------------------------------------ 白名单模式

    @Test
    fun whitelistModeOnlyRunsForSelectedApps() {
        val wl = setOf("com.tencent.mm", "com.tencent.mobileqq")
        assertTrue(CameraScope.shouldRunCamera(Mode.WHITELIST, "com.tencent.mm", home, wl))
        assertFalse(CameraScope.shouldRunCamera(Mode.WHITELIST, "com.taobao.taobao", home, wl))
        assertEquals("不在白名单", CameraScope.offReason(Mode.WHITELIST, "com.taobao.taobao", home, wl))
    }

    // ------------------------------------------------------------ 三条边界

    /** 边界 1：取不到前台包名时必须放行 —— 宁可贵一点，不能失灵。 */
    @Test
    fun unknownForegroundIsAllowedEvenInWhitelistMode() {
        assertTrue(
            "取不到前台包名时必须放行（否则手势会莫名失效）",
            CameraScope.shouldRunCamera(Mode.WHITELIST, null, home, setOf("com.tencent.mm")),
        )
        assertTrue(CameraScope.shouldRunCamera(Mode.GLOBAL, null, home, emptySet()))
    }

    /** 边界 2：白名单为空时按全局处理，否则刚切模式就没手势了。 */
    @Test
    fun emptyWhitelistFallsBackToGlobal() {
        assertTrue(
            "白名单为空时必须放行（否则用户一切到白名单模式手势就全废）",
            CameraScope.shouldRunCamera(Mode.WHITELIST, "com.taobao.taobao", home, emptySet()),
        )
    }

    /** 桌面包名没解析出来时，不能因此把桌面误判成"非桌面"而开着相机。 */
    @Test
    fun nullHomePackageMeansWeCannotBeSureItIsNotTheLauncher() {
        // 解析不出桌面包名时，我们无法判断前台是不是桌面，只能放行（功能优先）。
        // 这条把"当前行为"记录清楚，避免以后有人改成"猜"。
        assertTrue(
            "桌面包名未知时放行（无法判定，宁可开着）",
            CameraScope.shouldRunCamera(Mode.GLOBAL, "com.unknown.launcher", null, emptySet()),
        )
    }

    // ------------------------------------------------------------ 前台包名的分层

    /**
     * ★ 前台包名的**分层规则**（被两个方向的真机 bug 反复逼出来的，别改回去）。
     *
     * 规则：**新鲜的"全屏窗口事件"包名** > `rootInActiveWindow` > null。
     *
     * 两个方向的教训：
     *   · 只用 `rootInActiveWindow`：华为桌面上它不可靠 → 退出白名单后相机 4 秒才断。
     *   · **无条件**用事件包名：输入法/系统小窗的包名也会当成前台 →
     *     白名单 App 里约 1 秒被误断（v0.17.14 的真机 bug）。
     *
     * 所以：事件包名只在**全屏 + 新鲜**时采信（过滤在服务侧用 `isFullScreen` 与时间窗做），
     * 优先级高于 `rootInActiveWindow`；否则回退。
     */
    @Test
    fun freshFullscreenEventPackageWins() {
        // 事件包名（已由调用方确认为全屏且新鲜）优先 —— 这是桌面断开够快的关键
        assertEquals("com.huawei.android.launcher", CameraScope.pickForegroundForWhitelist("com.huawei.android.launcher", "com.xingin.xhs"))
        assertEquals("com.tencent.mm", CameraScope.pickForegroundForWhitelist("com.tencent.mm", null))
    }

    @Test
    fun rootInActiveWindowIsTheFallback() {
        // 没有可用事件时回退到节点树来源
        assertEquals("com.xingin.xhs", CameraScope.pickForegroundForWhitelist(null, "com.xingin.xhs"))
        assertEquals("com.xingin.xhs", CameraScope.pickForegroundForWhitelist("", "com.xingin.xhs"))
        // 两个都没有 -> null（调用方按"放行"处理，避免手势失灵）
        assertNull(CameraScope.pickForegroundForWhitelist(null, null))
        assertNull(CameraScope.pickForegroundForWhitelist("", ""))
    }

    /**
     * 端到端地看这条规则：在白名单 App 里，
     * 若权威来源说我们在微信，相机必须**继续开着**（不被别的来源踢出）。
     */
    @Test
    fun aWhitelistedAppStaysAlive() {
        val wl = setOf("com.tencent.mm")
        val fg = CameraScope.pickForegroundForWhitelist("com.tencent.mm", "com.tencent.mm")
        assertTrue(
            "白名单 App 内相机必须继续开着",
            CameraScope.shouldRunCamera(Mode.WHITELIST, fg, home, wl),
        )
        // 反面记录：v0.17.14 的真机 bug 就是把这个包名当成了前台
        assertFalse(
            "（反例）把输入法包名当前台会误判成不在白名单 —— v0.17.14 的真机 bug",
            CameraScope.shouldRunCamera(Mode.WHITELIST, "com.baidu.input_huawei", home, wl),
        )
    }

    // ------------------------------------------------------------ 模式解析

    @Test
    fun modeParsingAlwaysYieldsWhitelist() {
        // ★ v1.0.0：白名单是**唯一**模式。无论存的是什么（含旧版留下的 "global"、
        //   空值、垃圾值），都必须解析成 WHITELIST ——
        //   全局模式会让相机在所有 App 里常开，与本项目的省电目标直接冲突。
        assertEquals(Mode.WHITELIST, CameraScope.parseMode("whitelist"))
        assertEquals(Mode.WHITELIST, CameraScope.parseMode("WHITELIST"))
        assertEquals(Mode.WHITELIST, CameraScope.parseMode("  whitelist  "))
        assertEquals(Mode.WHITELIST, CameraScope.parseMode("global"))
        assertEquals(Mode.WHITELIST, CameraScope.parseMode(null))
        assertEquals(Mode.WHITELIST, CameraScope.parseMode(""))
        assertEquals(Mode.WHITELIST, CameraScope.parseMode("nonsense"))
    }
}
