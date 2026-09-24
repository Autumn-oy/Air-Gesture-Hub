package com.airgesture.sweep

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 云枢（Air Gesture Hub）主界面 —— v2.0.0 按用户定稿重排。
 *
 * 页面从上到下**只有**用户指定的这些东西（不要再自己加）：
 * ```
 *   头部大字   云枢 / Air Gesture Hub
 *   小字       一句话说明（借用接近光传感器）
 *   一、状态
 *   二、请按以下步骤部署（1 相机权限 / 2 无障碍设置 / 3 总开关）
 *   三、设备白名单控制（汇总一行 + 进二级界面挑选，见 WhitelistActivity）
 *   四、其他（用法提示；「显示调试悬浮窗」要连点本标题 6 下才出现，勾选还要口令）
 *   五、作者信息
 * ```
 *
 * ★ v2.0.0：**「相机作用域」与「参数」两节从界面移除**（用户要求：内置、不给别人看见）。
 * ★ v2.0.0：**「请滑动」提示没有开关**，永远显示（见 [OverlayController.showHint]）。
 * ★ v2.0.0：App 列表挪到二级界面 [WhitelistActivity]，主页面不再被 97 行列表撑长。
 * ★ 仍然刻意**全部用代码搭 UI**（没有 layout XML）：少一批资源 = 少一类构建/兼容问题。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var whitelistSummary: TextView
    private lateinit var debugRow: LinearLayout
    private lateinit var debugSwitch: Switch

    // Switch 的状态被代码改动时也会触发监听器，用这个标志挡住自触发。
    private var suppressDebug = false

    /** 「四、其他」标题被连点的次数。到 [DEBUG_TAPS] 才放出调试开关。**不持久化**：重开 App 要重新连点。 */
    private var otherTaps = 0
    private var debugRevealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 一次性参数迁移（见 Prefs.migrateOnce）：把定稿参数强制写进 prefs。
        // 必须在读参数之前调用 —— prefs 里已有的值会盖掉代码里的默认值。
        Prefs.migrateOnce(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(32))
            // ★ 让根容器自己吃掉初始焦点：否则子控件会抢焦点，ScrollView 一上来就
            //   自动滚过去 —— 打开 App 看到的是"半截页面"，头部被顶掉。
            isFocusableInTouchMode = true
            requestFocus()
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        // ---------------------------------------------------------- 头部
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        header.addView(TextView(this).apply {
            text = "云枢"
            textSize = 26f
            setTextColor(Color.parseColor("#111111"))
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Air Gesture Hub"
            textSize = 15f
            setTextColor(Color.parseColor("#5F6B7A"))
            setPadding(dp(8), 0, 0, dp(4))
        })
        // 版本号：**从 PackageManager 读**，不写死 —— 否则每次发版都要记得改这里，
        // 迟早会出现"界面显示的版本和 APK 对不上"（用户要求把这个显示出来）。
        header.addView(TextView(this).apply {
            text = "v" + appVersionName()
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(6), 0, 0, dp(5))
        })
        root.addView(header)
        root.addView(body("该软件会借调接近光传感器助您实现接近系统级的空中手势操作本设备"))

        // ---------------------------------------------------------- 一、状态
        root.addView(section("一、状态"))
        statusText = body("")
        root.addView(statusText)
        root.addView(button("刷新状态") { refreshStatus() })

        // ---------------------------------------------------------- 二、部署
        root.addView(section("二、请按以下步骤部署"))
        root.addView(button("1. 请求相机权限") { requestCamera() })
        root.addView(button("2. 打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("3. 总开关：开 / 关") { toggleActive() })

        // ---------------------------------------------------------- 三、白名单
        // 只在这里放"一行说明 + 当前名单 + 入口"，App 列表在二级界面里
        // （97 行列表放主页会把页面撑得太长 —— 用户要求）。
        root.addView(section("三、设备白名单控制"))
        root.addView(body("隔空手势仅在已勾选的应用上可使用"))
        whitelistSummary = body("")
        root.addView(whitelistSummary)
        root.addView(button("选择应用") {
            startActivity(Intent(this, WhitelistActivity::class.java))
        })

        // ---------------------------------------------------------- 四、其他
        val otherTitle = section("四、其他")
        // 连点标题 6 下才放出调试开关 —— 目的是"平时谁打开这个页面都看不见调试项"。
        otherTitle.setOnClickListener {
            if (debugRevealed) return@setOnClickListener
            otherTaps++
            if (otherTaps >= DEBUG_TAPS) {
                debugRevealed = true
                debugRow.visibility = View.VISIBLE
                toast("已显示调试选项")
            }
        }
        root.addView(otherTitle)
        root.addView(body(USAGE_HINT))

        debugRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        debugRow.addView(TextView(this).apply {
            text = "显示调试悬浮窗"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        debugSwitch = Switch(this).apply { isChecked = Prefs.debugOverlay(this@MainActivity) }
        debugSwitch.setOnCheckedChangeListener { _, v ->
            if (suppressDebug) return@setOnCheckedChangeListener
            if (!v) {
                // 关掉不需要口令 —— 口令只挡"打开"。
                Prefs.setDebugOverlay(this, false)
                toast("已关闭调试悬浮窗")
                return@setOnCheckedChangeListener
            }
            // 想打开：先把开关弹回关闭，口令通过后才真正打开（口令错就保持关闭）。
            suppressDebug = true
            debugSwitch.isChecked = false
            suppressDebug = false
            askPin { ok ->
                if (ok) {
                    Prefs.setDebugOverlay(this, true)
                    suppressDebug = true
                    debugSwitch.isChecked = true
                    suppressDebug = false
                    toast("已开启调试悬浮窗")
                } else {
                    toast("口令不对")
                }
            }
        }
        debugRow.addView(debugSwitch)
        root.addView(debugRow)

        // ---------------------------------------------------------- 五、作者信息
        root.addView(section("五、作者信息"))
        root.addView(TextView(this).apply {
            text = AUTHOR_URL
            textSize = 14f
            setTextColor(Color.parseColor("#1565C0"))
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(2), 0, dp(8))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR_URL)))
                } catch (_: Exception) {
                    toast("没有可用的浏览器")
                }
            }
        })

        setContentView(scroll)
        // ★ 打开页面必须停在顶部。两道保险一起上，因为"谁抢到焦点"取决于设备
        //   当时是否处于触摸模式：用按键唤醒/非触摸方式启动时按钮是可聚焦的，
        //   ScrollView 会自动滚到它那里，头部（云枢/Air Gesture Hub）就被顶掉了。
        //     1) 布局完成后把焦点抢回根容器，并滚到顶；
        //     2) 400ms 再滚一次，兜住"焦点在第二帧才落定"的机器。
        scroll.post {
            root.requestFocus()
            scroll.scrollTo(0, 0)
        }
        scroll.postDelayed({ scroll.scrollTo(0, 0) }, 400)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 从二级界面（白名单）回来后，名单可能已经变了 —— 顺手刷新汇总。
        refreshStatus()
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val acc = isAccessibilityEnabled()
        val svc = SweepAccessibilityService.instance != null
        statusText.text = buildString {
            append("相机权限：").append(if (cam) "✅ 已授权" else "❌ 未授权（点第 1 步）")
            append('\n').append("无障碍服务：").append(if (acc) "✅ 已开启" else "❌ 未开启（点第 2 步）")
            append('\n').append("服务实例：").append(if (svc) "✅ 运行中" else "❌ 未运行（开启无障碍后自动启动）")
            append('\n').append("总开关：")
                .append(if (Prefs.active(this@MainActivity)) "开" else "关（不注入滑动）")
            append('\n').append("签名校验：").append(
                if (SignatureGuard.isTrusted(this@MainActivity)) "✅ 通过"
                else "❌ 未通过（手势已禁用）"
            )
            if (cam && acc && !svc) {
                append('\n').append("⚠ 开关都开了但服务没跑：把它关掉再打开一次，或重启手机")
            }
        }
        if (::whitelistSummary.isInitialized) refreshWhitelistSummary()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val packageName = packageName
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun toggleActive() {
        val next = !Prefs.active(this)
        Prefs.setActive(this, next)
        SweepAccessibilityService.instance?.reloadSettings()
        refreshStatus()
        toast(if (next) "已开启" else "已暂停（服务还在，但不再注入滑动）")
    }

    /**
     * 名单摘要（明细在二级界面里改）。
     *
     * ★ 名单里**永远有云枢自己**（[Prefs.cameraWhitelist] 读取时注入），
     *   所以真正决定"能不能用手势"的是**除自己之外**勾了几个：
     *   一个都没有 = 任何 App 里都不生效。这件事必须写在界面上。
     */
    private fun refreshWhitelistSummary() {
        val others = Prefs.cameraWhitelist(this).count { it != packageName }
        whitelistSummary.text = if (others == 0) {
            "当前名单：只有云枢自己 → 手势在任何 App 里都不会生效"
        } else {
            "当前名单：云枢 + ${others} 个 App"
        }
    }

    // ------------------------------------------------------------------ 口令

    /**
     * 弹口令输入框。
     *
     * ★ 口令写在 APK 里（[ACCESS_PIN]），反编译就能看到 —— 它的用途是**防误触**，
     *   不是安全防护。真要防"有心人"，靠的是 [SignatureGuard] 那一层。
     */
    private fun askPin(onResult: (Boolean) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "口令"
            textSize = 16f
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("输入口令")
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onResult(input.text.toString().trim() == ACCESS_PIN) }
            .setNegativeButton("取消") { _, _ -> onResult(false) }
            .show()
    }

    // ------------------------------------------------------------------ 权限

    private fun requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            toast("相机权限已经有了")
            refreshStatus()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            toast(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "已授权，服务会自动用它"
            } else {
                "没授权的话取不到画面，手势无法工作"
            })
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 当前 APK 的 versionName（读不到就显示 ?，不让界面崩）。 */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    /** 蓝色小节标题。**比下面的选项字号大 1**（用户指定的层级关系）。 */
    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Color.parseColor("#1565C0"))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.parseColor("#333333"))
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAMERA = 1001

        /** 连点「四、其他」标题多少下才放出调试开关。 */
        private const val DEBUG_TAPS = 6

        /** 调试开关口令。★ 只能防误触（见 [askPin] 的说明）。 */
        private const val ACCESS_PIN = "0000"

        private const val AUTHOR_URL = "https://github.com/Autumn-oy"

        private const val USAGE_HINT =
            "用法：靠近接近光传感器5cm，一般在摄像头所在位置以唤醒服务，" +
                "随后将手后退至距离摄像头10-20cm效果最佳，" +
                "然后抬手待屏幕上出现【请滑动】，手部进行滑动即可进行隔空滑动，" +
                "目前支持上下左右四个正方向。"
    }
}
