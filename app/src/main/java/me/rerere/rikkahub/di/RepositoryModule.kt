package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import android.os.Environment
import me.rerere.workspace.ssh.SshConnectionPool
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            // 工作区文件放外部存储（用户可见）：/storage/emulated/0/RikkaHub/workspaces/<id>/files
            baseDir = File(Environment.getExternalStorageDirectory(), "RikkaHub/workspaces"),
            // Rootfs / 临时目录必须留在私有目录（FUSE 不支持符号链接与执行权限）
            rootfsBaseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表同时用于 PRoot 的 -b 参数 (命令执行与终端)、文件工具的路径解析和写入审批, 避免多处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                    writable = true,
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() },
                    target = "/builtin_skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        SshConnectionPool()
    } onClose { it?.close() }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
