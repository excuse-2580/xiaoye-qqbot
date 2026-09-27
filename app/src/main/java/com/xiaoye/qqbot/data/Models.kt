package com.xiaoye.qqbot.data

import com.xiaoye.qqbot.engine.HunyuanSigner
import com.xiaoye.qqbot.engine.InferParams

/** 模型来源的接入方式 */
enum class ModelKind {
    /** 手机本地的 .gguf，走内置 llama.cpp，离线可用 */
    LOCAL_GGUF,

    /** OpenAI 兼容协议（DeepSeek / Kimi / 智谱 / 硅基流动 / OpenAI / llama.cpp server） */
    OPENAI_COMPAT,

    /** Ollama 的 /api/chat */
    OLLAMA,

    /** 腾讯混元，TC3-HMAC-SHA256 签名 */
    HUNYUAN,
}

/** 一键填写的预设 */
enum class PresetProvider(
    val label: String,
    val kind: ModelKind,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
) {
    LOCAL(
        "手机本地 GGUF", ModelKind.LOCAL_GGUF, "", "",
        "不需要密钥，选一个 .gguf 文件即可"
    ),
    DEEPSEEK(
        "DeepSeek", ModelKind.OPENAI_COMPAT,
        "https://api.deepseek.com/v1", "deepseek-chat", "sk-..."
    ),
    KIMI(
        "Kimi · 月之暗面", ModelKind.OPENAI_COMPAT,
        "https://api.moonshot.cn/v1", "moonshot-v1-8k", "sk-..."
    ),
    ZHIPU(
        "智谱 GLM", ModelKind.OPENAI_COMPAT,
        "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "id.key"
    ),
    SILICONFLOW(
        "硅基流动", ModelKind.OPENAI_COMPAT,
        "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", "sk-..."
    ),
    OPENAI(
        "OpenAI", ModelKind.OPENAI_COMPAT,
        "https://api.openai.com/v1", "gpt-4o-mini", "sk-..."
    ),
    LLAMACPP(
        "llama.cpp server", ModelKind.OPENAI_COMPAT,
        "http://192.168.1.10:8080/v1", "local-model", "本地服务一般留空"
    ),
    OLLAMA(
        "Ollama", ModelKind.OLLAMA,
        "http://192.168.1.10:11434", "qwen2.5:1.5b", "本地服务一般留空"
    ),
    HUNYUAN(
        "腾讯混元", ModelKind.HUNYUAN,
        HunyuanSigner.ENDPOINT, "hunyuan-turbo", "SecretId:SecretKey"
    ),
}

data class ModelSource(
    val id: String,
    val name: String,
    val kind: ModelKind = ModelKind.OPENAI_COMPAT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    /** 仅 LOCAL_GGUF：.gguf 文件的绝对路径 */
    val ggufPath: String = "",
    /** 仅 HUNYUAN：地域，默认华南 */
    val region: String = "ap-guangzhou",
)

data class Agent(
    val id: String,
    val name: String,
    val emoji: String = "🐺",
    val systemPrompt: String = "你是一个有用、友善的 AI 助手。",
    val greeting: String = "你好呀～",
    val sourceId: String = "",
    val params: InferParams = InferParams(),
) {
    /** 本地模型才用得上这些参数 */
    fun isLocal(source: ModelSource?): Boolean = source?.kind == ModelKind.LOCAL_GGUF
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // user / assistant / system
    val content: String,
    val ts: Long = System.currentTimeMillis(),
)

/** 推荐的默认模型：约 1GB，中文好，手机上跑得动 */
object DefaultModel {
    const val NAME = "Qwen2.5-1.5B-Instruct-Q4_K_M"
    const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val SIZE_HINT = "约 1GB"
    const val URL =
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE"

    /** 内存吃紧时的备选 */
    const val SMALL_NAME = "Qwen2.5-0.5B-Instruct-Q4_K_M"
    const val SMALL_FILE = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val SMALL_URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/$SMALL_FILE"
}
