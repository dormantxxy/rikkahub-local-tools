package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 自动化：识别模型生成错误并自动执行恢复方案。
 *
 * 仅处理"非手动中断"的异常 —— 用户主动停止（CancellationException）永远不在此范围。
 */
@Serializable
data class AutomationConfig(
    /** 总开关 */
    val enabled: Boolean = false,
    /** 网络异常：自动重试 */
    val autoRetryNetwork: Boolean = true,
    /** 思考中断：自动在原位续写 */
    val autoResumeThinking: Boolean = true,
    /** 上下文超限：自动精简历史后重试 */
    val autoRecoverContext: Boolean = false,
)
