package me.rerere.rikkahub.data.localtools

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权与探测封装。
 * 修复点：以前 app 从未向 Shizuku 申请权限，所以 Shizuku 里看不到本 app 的授权。
 * 现在通过 [requestPermission] 主动申请，授权后 Shizuku 会记录本 app。
 */
object ShizukuHelper {
    /** Shizuku 服务是否可连（服务在运行且 binder 可用）。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已经拿到 Shizuku 授权。 */
    fun hasPermission(): Boolean = runCatching {
        isBinderAlive() && (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    /**
     * 主动申请 Shizuku 权限（弹出授权框）。返回是否已具备/已发起。
     */
    fun requestPermission(requestCode: Int = 10086): Boolean = runCatching {
        if (!isBinderAlive()) return false
        if (hasPermission()) return true
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    fun version(): Int = runCatching { if (isBinderAlive()) Shizuku.getVersion() else -1 }.getOrDefault(-1)
}
