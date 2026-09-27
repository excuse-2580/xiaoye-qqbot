package com.xiaoye.qqbot.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 手机本地 GGUF 推理引擎 —— libggml-jni.so 的 Kotlin 封装
 *
 * 采样（temperature / top-k / top-p / 重复惩罚）在 native 层做，
 * 这一层负责：ChatML 拼装、停止词截断、把 token 片段安全地吐给 UI。
 *
 * 停止词处理有个坑：token 是分片来的，"<|im_end|>" 可能被切成
 * "<|im" + "_end|>" 好几段。所以这里用「pending 缓冲 + 最长前缀保留」
 * 的办法 —— 结尾看着像停止词开头的部分先扣住不发，确认不是再吐出去。
 */

data class InferParams(
    val nCtx: Int = 2048,
    val nThreads: Int = 4,
    val temp: Float = 0.8f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val repeatWindow: Int = 64,
    val maxTokens: Int = 512,
    val seed: Long = 0L,
    val useMmap: Boolean = true,
)

/** native 每生成一个 token 回调一次，返回 false 立即停 */
fun interface TokenSink {
    fun onToken(text: String): Boolean
}

const val IM_START = "<|im_start|>"
const val IM_END = "<|im_end|>"

val DEFAULT_STOP_WORDS = listOf(IM_END, IM_START, "<|endoftext|>", "</s>", "<|im_sep|>")

object LlmEngine {

    init {
        System.loadLibrary("ggml-jni")
    }

    private var handle = 0L
    private var currentPath: String? = null
    private var currentCtx = 0

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, useMmap: Boolean): Long
    external fun nativeFree(h: Long)
    external fun nativeReset(h: Long)
    external fun nativeStop(h: Long)
    external fun nativeEvalPrompt(h: Long, text: String): Int
    external fun nativeGenerate(
        h: Long, temp: Float, topP: Float, topK: Int, repeatPenalty: Float,
        repeatWindow: Int, maxTokens: Int, seed: Long, cb: TokenSink
    ): String
    external fun nativeVocabSize(h: Long): Int
    external fun nativeEosToken(h: Long): Int
    external fun nativeContextSize(h: Long): Int

    val isLoaded: Boolean get() = handle != 0L
    val modelPath: String? get() = currentPath
    val contextSize: Int get() = currentCtx

    /** 可用 CPU 核数，用来给线程数一个合理默认值（手机别占满，会过热降频） */
    fun recommendedThreads(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        return when {
            n <= 2 -> 2
            n <= 4 -> 3
            n <= 6 -> 4
            else -> 6
        }
    }

    suspend fun load(path: String, params: InferParams): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            unload()
            val h = nativeLoad(path, params.nCtx, params.nThreads, params.useMmap)
            if (h == 0L) throw IllegalStateException("加载失败：模型文件可能损坏或不是 GGUF")
            handle = h
            currentPath = path
            currentCtx = nativeContextSize(h)
        }
    }

    fun unload() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
            currentPath = null
            currentCtx = 0
        }
    }

    /** 清空 KV cache，开始新对话 */
    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /**
     * 把一段文本塞进上下文（prefill），返回 token 数。
     * 多轮对话靠这个做增量，比每轮重发整个历史快得多。
     */
    suspend fun feed(text: String): Int = withContext(Dispatchers.Default) {
        if (handle == 0L) throw IllegalStateException("模型未加载")
        nativeEvalPrompt(handle, text)
    }

    /** ChatML 拼装 —— Qwen2.5 / 多数国产模型都吃这套模板 */
    fun chatML(system: String, userMessage: String): String =
        "$IM_START system\n$system$IM_END\n$IM_START user\n$userMessage$IM_END\n$IM_START assistant\n"

    /** 对话历史里一整轮的 ChatML 片段 */
    fun turnML(role: String, content: String): String =
        "$IM_START $role\n$content$IM_END\n"

    /**
     * 从当前上下文继续生成。
     * onDelta 会在推理线程上被调用（不是主线程），UI 侧请用线程安全的方式更新状态。
     */
    suspend fun generate(
        params: InferParams,
        stopWords: List<String> = DEFAULT_STOP_WORDS,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val h = handle
        if (h == 0L) throw IllegalStateException("模型未加载")

        val full = StringBuilder()
        val pending = StringBuilder()

        val sink = TokenSink { text ->
            pending.append(text)
            val s = pending.toString()

            // 命中完整停止词 —— 截断到这里，不把停止词本身吐出去
            var stopAt = -1
            for (sw in stopWords) {
                val i = s.indexOf(sw)
                if (i >= 0 && (stopAt < 0 || i < stopAt)) stopAt = i
            }
            if (stopAt >= 0) {
                val out = s.substring(0, stopAt)
                pending.setLength(0)
                if (out.isNotEmpty()) { full.append(out); onDelta(out) }
                return@TokenSink false
            }

            // 结尾可能是停止词的前缀，先扣住
            val hold = holdLength(s, stopWords)
            val safeLen = s.length - hold
            if (safeLen > 0) {
                val chunk = s.substring(0, safeLen)
                pending.delete(0, safeLen)
                full.append(chunk)
                onDelta(chunk)
            }
            true
        }

        nativeGenerate(
            h, params.temp, params.topP, params.topK, params.repeatPenalty,
            params.repeatWindow, params.maxTokens, params.seed, sink
        )

        // 收尾：把扣住的部分吐出来（说明它不是停止词）
        if (pending.isNotEmpty()) {
            full.append(pending)
            onDelta(pending.toString())
            pending.setLength(0)
        }
        full.toString().trim()
    }

    /** s 的结尾最多有多少个字符可能是某个停止词的前缀 */
    private fun holdLength(s: String, stops: List<String>): Int {
        var hold = 0
        for (sw in stops) {
            val maxL = min(sw.length - 1, s.length)
            for (l in maxL downTo 1) {
                if (s.endsWith(sw.substring(0, l))) {
                    if (l > hold) hold = l
                    break
                }
            }
        }
        return hold
    }
}
