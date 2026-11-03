package me.rerere.rikkahub.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku UserService 管理器（照搬 MobiCode Studio 的 ShizukuShellManager 思路）。
 *
 * 通过 [Shizuku.bindUserService] 绑定 [ShizukuShellService]（运行在 Shizuku 的 root 进程），
 * 再通过 AIDL 执行 shell 命令。完全不依赖 `Shizuku.newProcess`（13.x 中为私有方法且会被 R8 移除）。
 */
object ShizukuShellManager {
    private const val TAG = "ShizukuShellManager"
    private const val BIND_TIMEOUT_MILLIS = 8_000L

    private val lock = Any()

    @Volatile
    private var service: IShizukuShellService? = null

    @Volatile
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var installed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Shizuku shell service connected")
            synchronized(lock) {
                service = IShizukuShellService.Stub.asInterface(binder)
                lock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku shell service disconnected")
            synchronized(lock) {
                service = null
            }
        }
    }

    /** 在 Application.onCreate 调用一次。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        serviceArgs = runCatching {
            Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuShellService::class.java))
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .debuggable(false)
                .version(1)
        }.onFailure { Log.e(TAG, "create UserServiceArgs failed", it) }.getOrNull()

        // binder 重启后旧 service 失效，等待下次 bind
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                synchronized(lock) {
                    service = null
                }
            }
        }
        runCatching {
            Shizuku.addBinderDeadListener {
                synchronized(lock) {
                    service = null
                }
            }
        }
    }

    /** 绑定并获取服务（最多等 8 秒）。 */
    private fun ensureService(): IShizukuShellService? {
        service?.let { return it }
        val args = serviceArgs ?: return null
        if (!Shizuku.pingBinder()) return null
        synchronized(lock) {
            service?.let { return it }
            runCatching {
                Shizuku.bindUserService(args, connection)
            }.onFailure {
                Log.e(TAG, "bindUserService failed", it)
            }
            val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MILLIS
            while (service == null && System.currentTimeMillis() < deadline) {
                runCatching { lock.wait(200) }
                if (!Shizuku.pingBinder()) return null
            }
            return service
        }
    }

    /**
     * 在 Shizuku（root）进程执行 shell 命令。
     */
    fun exec(
        command: String,
        cwd: String? = null,
        timeoutSeconds: Int = 60,
        environment: Map<String, String> = emptyMap(),
    ): Result {
        val svc = ensureService()
            ?: return Result(-1, "", "Shizuku shell service 不可用（未授权或绑定失败）", timedOut = false)
        return try {
            val bundle = svc.exec(
                listOf("/system/bin/sh", "-c", command),
                environment.map { "${it.key}=${it.value}" },
                cwd,
                timeoutSeconds,
            )
            Result(
                exitCode = bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, -1),
                stdout = bundle.getString(ShizukuShellService.KEY_OUTPUT) ?: "",
                stderr = "",
                timedOut = bundle.getBoolean(ShizukuShellService.KEY_TIMED_OUT, false),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed", t)
            Result(-1, "", t.message ?: t.toString(), timedOut = false)
        }
    }

    fun isAvailable(): Boolean = Shizuku.pingBinder() && serviceArgs != null

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )
}
