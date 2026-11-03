package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File

/**
 * 聚合后的一体化"原生工具链环境"。
 * 把所有已装扩展的 payload/bin 汇总成 PATH、payload/lib 汇总成 LD_LIBRARY_PATH。
 * 工作目录/ HOME 统一放到 files/RikkaHub 下（RikkaHub 作为工作目录）。
 */
data class ToolchainEnv(
    val path: String,
    val ldLibraryPath: String,
    val home: String,
    val tmp: String,
    val workDir: String,
    val binDirs: List<String>,
    val libDirs: List<String>,
) {
    val isEmpty: Boolean get() = binDirs.isEmpty() && libDirs.isEmpty()
}

object ToolchainEnvironment {
    const val HOST_PATH =
        "/system/bin:/system/xbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"

    /** RikkaHub 工作目录（默认 CLI 工作目录）。 */
    fun workDir(context: Context): File =
        File(context.filesDir, "RikkaHub").apply { mkdirs() }

    fun build(context: Context, installer: ExtensionInstaller): ToolchainEnv {
        val base = workDir(context)
        val home = File(base, ".home").apply { mkdirs() }
        val tmp = File(base, ".tmp").apply { mkdirs() }
        val records = installer.installed.value.values.sortedBy { it.id }
        val binDirs = records.mapNotNull { rec ->
            File(rec.payloadDir, "bin").takeIf { it.isDirectory }?.absolutePath
        }
        val libDirs = records.mapNotNull { rec ->
            File(rec.payloadDir, "lib").takeIf { it.isDirectory }?.absolutePath
        }
        val path = (binDirs + HOST_PATH).joinToString(":")
        val ld = libDirs.joinToString(":")
        return ToolchainEnv(
            path = path,
            ldLibraryPath = ld,
            home = home.absolutePath,
            tmp = tmp.absolutePath,
            workDir = base.absolutePath,
            binDirs = binDirs,
            libDirs = libDirs,
        )
    }
}
