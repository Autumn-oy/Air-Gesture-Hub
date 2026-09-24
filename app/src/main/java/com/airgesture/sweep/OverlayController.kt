package com.airgesture.sweep

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮状态条。
 *
 * 关键点：用 `TYPE_ACCESSIBILITY_OVERLAY` —— 无障碍服务加这个类型的窗口
 * **不需要 SYSTEM_ALERT_WINDOW 权限**（这也是 Gameface 只声明 CAMERA 一个权限的原因）。
 *
 * 两条硬约束：
 *   1. 所有窗口操作必须回主线程（MediaPipe 的结果回调在后台线程）
 *   2. 绝不能吃掉触摸（FLAG_NOT_TOUCHABLE），否则用户点不到底下的东西——
 *      Windows 版在悬浮环上踩过同类坑（不透明像素挡住鼠标）
 */
class OverlayController(private val service: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var attached = false

    // ---- 操作提示（"请滑动"这类引导）----
    private var hintView: TextView? = null
    private val hideHint = Runnable { hintView?.visibility = android.view.View.GONE }

    private fun makeTextView(textSizeSp: Float, bg: Int): TextView = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = textSizeSp
        setPadding(32, 16, 32, 16)
        setBackgroundColor(bg)
    }

    private fun layoutParams(gravityY: Int, type: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            type,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = gravityY
        }

    /**
     * 弹一条操作提示（非阻塞、不抢触摸、到点自动消失）。
     *
     * 为什么用悬浮窗而不是 Toast：Toast 会排队，连续扫动时提示会滞后好几秒；
     * 而且 Toast 属于系统 UI，体验上更"吵"。这里是一个不可触摸的悬浮条。
     *
     * 注：v0.16.x 试过"常驻不消失"的版本，用户否掉了 —— 还是一闪即隐的干净
     * （见调用点：`showHint("请滑动", 900)`）。
     *
     * ★ v2.0.0：**不再有开关**。「请滑动」是用户指定的"强制显示"，所以这里不再查
     *   `Prefs.showHints`（那个开关连同它的 UI 复选框一起删掉了）。
     */
    fun showHint(text: String, durationMs: Long) {
        main.post {
            var tv = hintView
            if (tv == null) {
                tv = makeTextView(15f, Color.TRANSPARENT)
                // 灰色圆角矩形 + 低透明度（用户指定样式）：做成 shape drawable，
                // 而不是给 View 设纯色背景 —— 纯色是直角、且看不出圆角
                val density = service.resources.displayMetrics.density
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.argb(105, 0x55, 0x55, 0x55))
                    cornerRadius = 22f * density
                }
                try {
                    wm.addView(tv, layoutParams(180, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
                    hintView = tv
                } catch (e: Exception) {
                    android.util.Log.w("SweepOverlay", "addView(hint) 失败: ${e.message}")
                    return@post
                }
            }
            tv.text = text
            tv.visibility = android.view.View.VISIBLE
            main.removeCallbacks(hideHint)
            main.postDelayed(hideHint, durationMs)
        }
    }

    /**
     * 悬浮窗被用户关掉时收起来。
     *
     * 刻意**只设 GONE、不销毁视图**：视图留着，用户重新打开开关就能立刻显示，
     * 服务那边也可以提前 return（连 Status 都不用构造）—— 省一层每帧的字符串拼接。
     */
    fun hideForDisabled() {
        main.post { view?.visibility = android.view.View.GONE }
    }

    fun show() {
        main.post {
            if (attached) return@post
            val tv = makeTextView(12f, Color.argb(170, 16, 20, 26))
            try {
                wm.addView(tv, layoutParams(96, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
                view = tv
                attached = true
            } catch (e: Exception) {
                android.util.Log.w("SweepOverlay", "addView 失败: ${e.message}")
            }
        }
    }

    fun update(status: SweepBus.Status) {
        main.post {
            val tv = view ?: return@post
            if (!Prefs.debugOverlay(service)) {
                tv.visibility = android.view.View.GONE
                return@post
            }
            tv.visibility = android.view.View.VISIBLE
            tv.text = buildText(status)
        }
    }

    private fun buildText(s: SweepBus.Status): String {
        val hand = if (s.handPresent) "有手" else "无手"
        val gate = if (s.gated) "·手指张开(不判定)" else ""
        val angle = if (s.angle.isNaN()) "—" else String.format("%.0f°", s.angle)
        val together = if (s.together.isNaN()) "—" else String.format("%.2f", s.together)
        // 手指根数：这是在真机上确认"四根手指"闸门有没有放行的唯一读数
        val fingers = if (!s.handPresent) "—" else s.fingerInfo + if (s.extended < 4) "（需4）" else ""
        // 冷静期倒计时：从**触发那一瞬间**开始掉，用来确认"不是等滑动播完才开始"
        val cd = if (s.cooldownLeftMs > 0.5) String.format("冷静 %.0fms", s.cooldownLeftMs) else ""
        return buildString {
            append("隔空扫动  ")
            append(if (s.serviceReady) "就绪" else "未就绪")
            append("  ").append(s.state)
            if (cd.isNotEmpty()) append("  ").append(cd)
            append("  ").append(s.modelInfo)
            append("\n").append(hand).append(gate)
            append("  手指 ").append(fingers)
            append("  FPS ").append(String.format("%.0f", s.fps))
            append("\n位移 ").append(String.format("%.3f", s.disp))
            append("  角度 ").append(angle)
            append("  并拢 ").append(together)
            // 三路计数：帧在涨而回调不涨 = 推理断了；回调涨而见手不涨 = 模型没认出你的手
            append("\n帧 ").append(s.framesIn)
            append("  回调 ").append(s.resultsOut)
            append("  见手 ").append(s.handsSeen)
            append("\n上次 ").append(SweepBus.directionCn(s.lastDir))
            if (s.injectInfo.isNotEmpty()) append("  ").append(s.injectInfo)
            // 最近几次手势方向：一张截图就能看出四个方向是否都正确
            if (s.gestureHistory.isNotEmpty()) append("\n方向 ").append(s.gestureHistory)
            if (s.message.isNotEmpty()) append("\n⚠ ").append(s.message)
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(hideHint)
            hintView?.let {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
            hintView = null
            val tv = view ?: return@post
            try {
                wm.removeView(tv)
            } catch (_: Exception) {
            }
            view = null
            attached = false
        }
    }
}
