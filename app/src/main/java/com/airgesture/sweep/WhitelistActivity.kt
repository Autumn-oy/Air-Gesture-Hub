package com.airgesture.sweep

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 设备白名单（二级界面）。
 *
 * ★ v2.0.0：从主页搬出来的。97 行 App 列表放主页会把主界面撑得极长 ——
 *   用户要求做成二级界面，主页只留"一行说明 + 当前名单 + 入口"。
 *
 * 列表样式照用户给的参考图：搜索框 + 白色卡片（「全部」主开关 + 每个 App
 * 一行：图标 / 名称 / 开关），行间细分隔线。
 *
 * ★ 名单里**永远含云枢自己**（[Prefs.cameraWhitelist] 读取时注入），它那一行
 *   灰阶 + 不可撤销。这同时保证名单永远不为空 → [CameraScope] 那条
 *   "空名单 = 全部放行"的兜底永远够不着，于是"一个都不勾 = 谁都不能用"成立。
 */
class WhitelistActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var scanInfo: TextView
    private lateinit var listHolder: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var allSwitch: Switch

    private class AppRow(val pkg: String, val label: String, val icon: Drawable?)

    private var apps: List<AppRow> = emptyList()

    /** 当前渲染出来的每一行开关，按包名索引 —— 「全部」要用它批量翻状态。 */
    private val rowSwitches = HashMap<String, Switch>()

    // Switch 的状态被代码改动时也会触发监听器，不挡住就会自己写自己。
    private var suppressAll = false
    private var suppressRow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
            // 和主页同理：别让搜索框抢初始焦点把页面顶下去。
            isFocusableInTouchMode = true
            requestFocus()
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        // ---------------------------------------------------------- 返回 + 标题
        root.addView(TextView(this).apply {
            text = "← 返回"
            textSize = 14f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 0, 0, dp(6))
            setOnClickListener { finish() }
        })
        root.addView(section("设备白名单控制"))
        root.addView(body("隔空手势仅在已勾选的应用上可使用"))
        summary = body("")
        root.addView(summary)
        scanInfo = small("正在扫描…")
        root.addView(scanInfo)

        searchBox = EditText(this).apply {
            hint = "🔍  搜索应用"
            textSize = 14f
            isSingleLine = true
            setTextColor(Color.parseColor("#1A1A1A"))
            setHintTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = rounded(Color.parseColor("#EFF1F4"), 22f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderRows()
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        root.addView(searchBox)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        // 「全部」= 一键把扫到的所有 App 都放进名单（**机制仍是白名单**，不是全局模式）。
        card.addView(simpleRow("全部") { sw ->
            allSwitch = sw
            sw.setOnCheckedChangeListener { _, v -> onAllToggled(v) }
        })
        card.addView(divider())
        listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(listHolder)
        root.addView(card)
        root.addView(button("重新扫描应用") { scanApps(force = true) })

        setContentView(scroll)
        // 同上：搜索框会抢焦点并把页面顶下去（尤其非触摸模式下启动时）。
        // 抢回焦点 + 两次滚顶，保证打开就看见"← 返回"和标题。
        scroll.post {
            root.requestFocus()
            searchBox.clearFocus()
            scroll.scrollTo(0, 0)
        }
        scroll.postDelayed({ scroll.scrollTo(0, 0) }, 400)
        refreshSummary()
        scanApps()
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 扫描"已安装且有启动入口"的应用。
     *
     * 为什么用 `queryIntentActivities(CATEGORY_LAUNCHER)` 而不是 `getInstalledApplications`：
     *   · 后者会把几百个系统组件（输入法、provider、壁纸……）都列出来，用户根本没法挑；
     *   · 有启动图标的才是"用户会打开来用的 App"，正好就是隔空手势可能用到的范围。
     *   · 桌面自己**不在**这个列表里（实测华为：97 个可启动应用里没有 launcher），
     *     而桌面本来也永远不响应手势，所以不需要它。
     *
     * 放后台线程：一两百个包要 loadLabel/loadIcon（都是跨进程调用），主线程会卡住页面。
     */
    private fun scanApps(force: Boolean = false) {
        if (apps.isNotEmpty() && !force) return
        scanInfo.text = "正在扫描已安装的应用…"
        Thread {
            val pm = packageManager
            val resolved = pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
            )
            val seen = HashSet<String>()
            val rows = ArrayList<AppRow>()
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                // 自己**不跳过**：云枢要作为"灰阶不可撤销"的那一行显示，
                // 它同时是"名单永不为空"的保证（见 Prefs.cameraWhitelist）。
                if (!seen.add(pkg)) continue
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    ri.loadIcon(pm)
                } catch (_: Exception) {
                    null
                }
                rows.add(AppRow(pkg, label, icon))
            }
            // 自己钉最前，其余按中文习惯排序（Collator 按拼音排，与参考图一致）。
            val coll = java.text.Collator.getInstance(java.util.Locale.CHINA)
            rows.sortWith(compareBy({ it.pkg != packageName }, { coll.getCollationKey(it.label) }))
            runOnUiThread {
                apps = rows
                scanInfo.text = "共 ${rows.size} 个可启动应用"
                renderRows()
            }
        }.start()
    }

    /** 按搜索词重建列表；每次都从 [Prefs] 重新读名单，保证界面不与实际生效值脱节。 */
    private fun renderRows() {
        listHolder.removeAllViews()
        rowSwitches.clear()

        val q = searchBox.text.toString().trim().lowercase()
        val picked = Prefs.cameraWhitelist(this)
        val shown = if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }

        if (shown.isEmpty()) {
            listHolder.addView(small(if (apps.isEmpty()) "没有扫描到可启动的应用" else "没有匹配的应用"))
        } else {
            shown.forEachIndexed { i, a ->
                val isSelf = a.pkg == packageName
                val sw = Switch(this).apply {
                    isChecked = a.pkg in picked
                    // 云枢自己：灰阶 + 不可撤销。
                    isEnabled = !isSelf
                }
                if (!isSelf) {
                    sw.setOnCheckedChangeListener { _, v ->
                        if (suppressRow) return@setOnCheckedChangeListener
                        if (v) Prefs.addToCameraWhitelist(this, a.pkg)
                        else Prefs.removeFromCameraWhitelist(this, a.pkg)
                        SweepAccessibilityService.instance?.reloadSettings()
                        syncAllSwitch()
                    }
                }
                rowSwitches[a.pkg] = sw
                listHolder.addView(appRow(a, sw, isSelf))
                if (i != shown.lastIndex) listHolder.addView(divider())
            }
        }
        syncAllSwitch()
    }

    private fun appRow(a: AppRow, sw: Switch, isSelf: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(8), dp(5))
        }
        row.addView(ImageView(this).apply {
            a.icon?.let { setImageDrawable(it) }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })
        row.addView(TextView(this).apply {
            text = if (isSelf) "${a.label}（本应用·固定）" else a.label
            textSize = 14f
            setTextColor(Color.parseColor(if (isSelf) "#9AA0A6" else "#1A1A1A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(sw)
        return row
    }

    /**
     * 「全部」开关：开 = 把扫到的所有 App 都放进名单；关 = 清空名单。
     *
     * ★ 关掉之后名单里**只剩云枢自己**（读取时自动注入），效果就是
     * "手势在任何 App 里都不生效" —— 正是用户要的语义。
     */
    private fun onAllToggled(on: Boolean) {
        if (suppressAll) return
        Prefs.setCameraWhitelist(this, if (on) apps.map { it.pkg }.toSet() else emptySet())
        SweepAccessibilityService.instance?.reloadSettings()
        syncRowSwitches()
        refreshSummary()
        toast(if (on) "已选中全部 ${apps.count { it.pkg != packageName }} 个应用"
              else "已清空名单：除云枢外都不生效")
    }

    /** 把每一行的开关同步成"实际生效的名单"（读取路径已注入云枢自己）。 */
    private fun syncRowSwitches() {
        val picked = Prefs.cameraWhitelist(this)
        suppressRow = true
        for ((pkg, sw) in rowSwitches) sw.isChecked = pkg in picked
        suppressRow = false
    }

    private fun syncAllSwitch() {
        val picked = Prefs.cameraWhitelist(this)
        suppressAll = true
        allSwitch.isChecked = apps.isNotEmpty() && apps.all { it.pkg in picked }
        suppressAll = false
        refreshSummary()
    }

    private fun refreshSummary() {
        val others = Prefs.cameraWhitelist(this).count { it != packageName }
        summary.text = if (others == 0) {
            "当前名单：只有云枢自己 → 手势在任何 App 里都不会生效"
        } else {
            "当前名单：云枢 + ${others} 个 App"
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Color.parseColor("#1565C0"))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.parseColor("#333333"))
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun small(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        ).apply { leftMargin = dp(12); rightMargin = dp(12) }
        setBackgroundColor(Color.parseColor("#EEF0F3"))
    }

    private fun simpleRow(label: String, onSwitch: (Switch) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this)
        onSwitch(sw)
        row.addView(sw)
        return row
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
}
