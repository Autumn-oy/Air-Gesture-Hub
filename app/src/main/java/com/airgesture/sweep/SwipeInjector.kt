package com.airgesture.sweep

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path

/**
 * 把"一次扫动"变成"屏幕上的一次滑动"。
 *
 * ==================== 只剩一条通道：合成滑动 ====================
 * v0.16.0 按用户要求**废除 `ACTION_SCROLL_*`**（那条"让控件自己滚"的通道）。
 * 原因：它产生的是一次性跳变 —— 滚动量由控件自己定、没有跟手过程，体感明显不如
 * 真实触摸顺滑；而且它只在实现了该动作的控件上有效，还会滚错节点（抖音滚到顶部导航）。
 * 合成滑动是**真实触摸事件的等价物**，目标应用的跟手/惯性动画照常跑，所以更顺、更通用。
 *
 * 方向语义（与"手指在屏幕上滑"保持一致）：
 *   手向上扫 -> 内容向上走 -> 相当于手指在屏幕上向上滑 -> 合成滑动向上
 *
 * ==================== 桌面（启动器）不响应 ====================
 * [perform] 先看一眼前台应用是不是桌面，是就**什么都不做**（固定行为，没有开关）。
 * 桌面的分页规则和普通应用完全不一样（"拖过半屏才翻页"、松手速度还会变成惯性甩页），
 * 为它单独做一套手势不值得 —— 与其在桌面上时灵时不灵，不如不接管。
 *
 * 实现刻意做得很薄：桌面包名只查**一次**并缓存；前台包名走 `rootInActiveWindow`
 * 一次调用拿到（**不遍历窗口列表**）；不弹提示、不加诊断字段。
 * 这段只在**每次手势**时跑一次（一分钟几次），不是每帧。任何一步取不到就**放行**。
 *
 * ==================== 横屏只做上下（v2.3.0，用户需求） ====================
 * 屏幕横过来时只保留上下两个方向：左右扫**不注入**，返回一句"横屏·忽略左/右"给悬浮窗。
 * 判定核不认识"横屏"这件事（它是纯几何），策略统一收在 [perform] 这一层 ——
 * 与桌面判定、签名复检并列，理由见函数内注释。方向映射本身（横屏该往哪偏移）
 * 在 [ScreenOrientation] 里，两者是正交的两件事。
 */
object SwipeInjector {

    /**
     * 合成滑动的最小距离（像素），太小会被系统当成点击或被目标应用忽略。
     *
     * ⚠️ 这个下限会**覆盖**设置里的比例：旧值 120 在 1080 宽的屏上会把 0.05（54px）
     * 悄悄抬到 120px（≈0.11）。现在是 48px，且定稿幅度 0.30（1200 屏 = 360px）远在其上。
     */
    private const val MIN_SWIPE_PX = 48

    /** 默认桌面包名：只解析一次就缓存（换桌面后重启服务会重新解析）。 */
    @Volatile
    private var homePkg: String? = null
    @Volatile
    private var homePkgResolved = false

    @Volatile
    private var gestureInFlight = false

    /** 按当前设置实际会滑动多少像素（唯一的下限是 [MIN_SWIPE_PX]）。 */
    fun effectiveSwipePx(spanPx: Int, frac: Float): Double =
        (spanPx.toDouble() * frac).coerceAtLeast(MIN_SWIPE_PX.toDouble())

    // ------------------------------------------------------------ 桌面判定（极薄）

    /**
     * 当前前台 App 的包名；取不到返回 null。
     *
     * 供两处共用，避免出现两套"前台是谁"的实现：
     *   · [isLauncher] —— 桌面上不注入手势（既有行为）
     *   · [com.airgesture.sweep.CameraScope] —— 桌面上/不在白名单时**不绑相机**省电
     */
    fun foregroundPackage(service: AccessibilityService): String? = try {
        service.rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) {
        null
    }

    /** 桌面包名（缓存）。解析不出来返回 null。 */
    fun homePackage(ctx: Context): String? = cachedHomePackage(ctx)

    /**
     * 默认屏幕当前的旋转**度数**（0 / 90 / 180 / 270）。
     *
     * ★ 为什么放在这里：这是本项目唯一的"屏幕朝向"查询入口 ——
     *   服务每帧（跳帧之后）要用它推方向角偏移，注入时要用它判"是不是横屏"。
     *   放在同一处，避免出现两套"屏幕转了多少"的实现（本项目在"前台是谁"上踩过这类坑）。
     *
     * 注意别和 `Surface.ROTATION_*` 混淆：那是 0~3 的枚举值，这里换算成度数。
     * 取不到时返回 0（= 按竖屏处理 = 不限制方向 = 旧行为），**绝不因为查不到就去拦手势**。
     */
    fun displayRotationDegrees(ctx: Context): Int = try {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        when (dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation) {
            android.view.Surface.ROTATION_90 -> ScreenOrientation.DEG_90
            android.view.Surface.ROTATION_180 -> ScreenOrientation.DEG_180
            android.view.Surface.ROTATION_270 -> ScreenOrientation.DEG_270
            else -> ScreenOrientation.DEG_0
        }
    } catch (e: Exception) {
        ScreenOrientation.DEG_0
    }

    /**
     * 前台应用是不是桌面：只比较"活跃窗口的包名"和"默认桌面包名"。
     * 取不到任何一边就返回 false（放行）。刻意**不用** `service.windows` 遍历窗口列表 ——
     * 那要枚举所有窗口、每个取一次 root 节点，判桌面根本用不着。
     */
    private fun isLauncher(service: AccessibilityService): Boolean {
        val fg = foregroundPackage(service) ?: return false
        val home = cachedHomePackage(service) ?: return false
        return fg == home
    }

    /**
     * 对外暴露的桌面判定，供服务在**弹提示前**判断。
     *
     * 用途：桌面不响应隔空手势（见 [perform]），所以"请滑动"这个出发信号在桌面上
     * 没有任何意义 —— 用户明确要求桌面上不要弹（"桌面我们是不调度手势的"）。
     * 不弹也顺带避免了在桌面上白做视觉干扰。
     */
    fun isOnLauncher(service: AccessibilityService): Boolean = isLauncher(service)

    /**
     * 默认桌面包名（缓存）。动态解析 `CATEGORY_HOME` 而不是硬编码：
     * 国产 ROM 的桌面五花八门（com.miui.home / com.hihonor.android.launcher / ...），
     * 硬编码必然漏。解析失败保持 null（= 永不跳过）。
     */
    private fun cachedHomePackage(ctx: Context): String? {
        if (homePkgResolved) return homePkg
        homePkg = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        homePkgResolved = true
        return homePkg
    }

    // ------------------------------------------------------------ 注入

    /**
     * 执行一次注入。返回一句给用户看的结果说明（显示在调试悬浮窗里）。
     * 必须在主线程调用（手势派发建议在主线程）。
     */
    fun perform(
        service: AccessibilityService,
        dir: String,
        swipeMs: Int,
        fracVertical: Float,
        fracHorizontal: Float,
    ): String {
        // ★ 需求："手机桌面不给调用这个软件"。桌面上一次都不注入。
        if (isLauncher(service)) return "桌面·跳过"
        // ★ v2.3.0 需求：**横屏下只处理上下两个方向** —— 左右扫不注入。
        //
        //   为什么拦在**注入层**而不是判定核里（方案对比见文档，结论是这里更优）：
        //     · 判定核 `SweepDetector` 与 `probe/detector_ref.py` + 31 个跨语言向量**保持冻结**，
        //       这套护栏的全部价值就是"两端逐行一致"，为横屏需求去改判定核会把它稀释掉；
        //     · 所有注入都经过这一个函数，"哪些方向能注入"只有**一处**可审计
        //       （和上面那句桌面判定、以及服务里的签名复检同一个位置）；
        //     · 热路径（每帧）**零改动**，只有真正触发手势时（一分钟几次）多一次判断。
        //   代价是左右扫仍会走完判定、吃掉 1100ms 冷静期 —— 这是刻意保留的：
        //   横屏时一次误扫不会立刻被当成别的动作。
        if (!ScreenOrientation.allowsDirection(
                dir, ScreenOrientation.isLandscape(displayRotationDegrees(service)))) {
            return "横屏·忽略${SweepBus.directionCn(dir)}"
        }
        // 上下和左右的合成幅度分开给：两者在一个屏幕上对应的像素数差很多
        // （屏高 2664 vs 屏宽 1200），用同一个比例很难两头都合适。
        val frac = if (dir == "up" || dir == "down") fracVertical else fracHorizontal
        return if (dispatchSwipe(service, dir, swipeMs, frac)) {
            "合成滑动($dir)"
        } else {
            "注入失败($dir)"
        }
    }

    /**
     * 合成滑动：单段匀速滑到底，松手时带惯性（真实触摸事件的等价物）。
     *
     * 注意：单段 Path 内部按**弧长**均匀走位，所以"把最后一段画短一点"并不能降低松手速度；
     * v0.14 那套两段式减速（`willContinue` + 慢速收尾，为桌面翻页压住 fling）已经删掉了 ——
     * 桌面现在直接不接管，不需要为它冒险。
     */
    private fun dispatchSwipe(
        service: AccessibilityService,
        dir: String,
        swipeMs: Int,
        swipeFrac: Float,
    ): Boolean {
        if (gestureInFlight) return false      // 上一次手势还没结束，别叠加

        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toDouble()
        val h = dm.heightPixels.toDouble()
        val cx = w / 2.0
        val cy = h / 2.0

        val span = (if (dir == "left" || dir == "right") w else h) * swipeFrac
        val dist = span.coerceAtLeast(MIN_SWIPE_PX.toDouble())
        val (ux, uy) = when (dir) {
            "up" -> 0.0 to -1.0
            "down" -> 0.0 to 1.0
            "left" -> -1.0 to 0.0
            else -> 1.0 to 0.0
        }
        // 起点：以屏幕中心为基准对称展开
        val x0 = cx - ux * dist / 2
        val y0 = cy - uy * dist / 2

        val path = Path().apply {
            moveTo(x0.toFloat(), y0.toFloat())
            lineTo((x0 + ux * dist).toFloat(), (y0 + uy * dist).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path, 0L, swipeMs.toLong().coerceAtLeast(20L))

        gestureInFlight = true
        val ok = dispatch(service, GestureDescription.Builder().addStroke(stroke).build(),
            onEnd = { gestureInFlight = false })
        // ★ 派发本身就失败时回调不会被调用，必须在这里解锁，否则后续滑动全被静默挡掉
        if (!ok) gestureInFlight = false
        return ok
    }

    private fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        onEnd: () -> Unit,
    ): Boolean = try {
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onEnd()
            override fun onCancelled(gestureDescription: GestureDescription?) = onEnd()
        }, null)
    } catch (e: Exception) {
        false
    }
}
