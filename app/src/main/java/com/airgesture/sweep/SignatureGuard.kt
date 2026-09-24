package com.airgesture.sweep

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * 运行时签名自检（v2.0.0）。
 *
 * ## 它做什么
 * 读取**当前安装包自己的签名证书**，把 SHA-256 和内置白名单比对。
 *
 * ## 它能挡什么、挡不住什么（必须诚实）
 * - **能挡**：别人改包（改代码/资源）后用自己的 key 重签 —— 签名不在白名单里，校验不过。
 * - **挡不住**：会反编译的人把这段校验 patch 掉（就几行字节码），或者拿到本工程私钥的人。
 *   所以它的定位是**提高门槛**，不是 DRM。
 *
 * ## 强度（用户选的）
 * 「只禁用隔空手势功能」：App 照常能打开、界面照常能看，但**不开相机、不注入滑动**。
 * 见 [SweepAccessibilityService] 里的两处判定，以及 MainActivity 状态区的显示。
 *
 * ## 失败时按"不可信"处理（fail closed）
 * 读不到签名本身就是异常信号。代价是万一某台机器读签名失败，手势会被静默禁用 ——
 * 所以界面状态区一定把这一行显示出来，不让人猜。
 */
object SignatureGuard {

    private const val TAG = "SweepSignature"

    /**
     * 允许的签名证书 SHA-256（**大写、无冒号**）。
     *
     * - `release`：本工程自己的 keystore（`android/keystore/yunshu-release.jks`）
     * - `debug`  ：本机 Android 调试证书。留着是为了以后还能临时装 debug 包自测；
     *              它只存在于开发机上，不构成实际弱点。
     *
     * ⚠️ **换 keystore 必须同时改这里**，否则新包会被自己拦下（手势直接失效）。
     */
    private val ALLOWED = setOf(
        "7D704957ECE36901CDD55DB5E5FD39B7A6A385CCA0E2720DA5FBBC3ECCB4AF7D",
        "ED196556E84E669B0E04EC7ABC8250FD7C716B6A4943C7EB2E195CFD7610DD86",
    )

    @Volatile
    private var cached: Boolean? = null

    /** 当前安装包的签名是否可信。结果在一个进程内缓存。 */
    fun isTrusted(ctx: Context): Boolean {
        cached?.let { return it }
        val ok = try {
            certHashes(ctx).any { it in ALLOWED }
        } catch (e: Exception) {
            Log.w(TAG, "读取签名失败，按不可信处理: ${e.message}")
            false
        }
        cached = ok
        return ok
    }

    /** 当前包的签名证书 SHA-256 列表（大写十六进制）。minSdk 29 → signingInfo 一定可用。 */
    private fun certHashes(ctx: Context): List<String> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return signers.map { sig ->
            // and 0xFF 是必须的：Byte 直接给 %02X 在不同 JDK 上可能变成 8 位补码的长串
            md.digest(sig.toByteArray()).joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) }
        }
    }
}
