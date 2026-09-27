package com.xiaoye.qqbot.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoye.qqbot.data.Agent
import com.xiaoye.qqbot.data.ChatMessage
import com.xiaoye.qqbot.data.ModelKind
import com.xiaoye.qqbot.data.ModelSource
import com.xiaoye.qqbot.data.Prefs
import com.xiaoye.qqbot.data.QQBotConfig
import com.xiaoye.qqbot.engine.CloudEngine
import com.xiaoye.qqbot.engine.DEFAULT_STOP_WORDS
import com.xiaoye.qqbot.engine.IM_START
import com.xiaoye.qqbot.engine.InferParams
import com.xiaoye.qqbot.engine.LlmEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val path: String) : EngineState
    data class Ready(val path: String, val nCtx: Int) : EngineState
    data class Error(val message: String) : EngineState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val cloud = CloudEngine()

    val sources = MutableStateFlow(prefs.loadSources())
    val agents = MutableStateFlow(
        prefs.loadAgents().ifEmpty { listOf(defaultAgent()) }
    )
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val generating = MutableStateFlow(false)
    val currentAgentId = MutableStateFlow(
        prefs.lastAgentId?.takeIf { id -> agents.value.any { it.id == id } }
            ?: agents.value.first().id
    )

    /** QQ 机器人配置（密钥字段在内存里也是明文，落盘时才加密） */
    val qqConfig = MutableStateFlow(prefs.loadQQConfig())

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    /* 本地会话的增量状态 */
    private var localStarted = false
    private var lastReply = ""
    private var usedTokens = 0

    fun currentAgent(): Agent? = agents.value.find { it.id == currentAgentId.value }
    fun sourceOf(agent: Agent? = null): ModelSource? {
        val a = agent ?: currentAgent() ?: return null
        return sources.value.find { it.id == a.sourceId }
    }

    /* ------------------------------ 模型源 ------------------------------ */

    fun upsertSource(s: ModelSource) {
        sources.update { list ->
            val i = list.indexOfFirst { it.id == s.id }
            if (i >= 0) list.toMutableList().apply { this[i] = s } else list + s
        }
        prefs.saveSources(sources.value)
    }

    fun removeSource(id: String) {
        sources.update { it.filterNot { s -> s.id == id } }
        prefs.saveSources(sources.value)
        // 智能体如果绑的是它，就解绑
        agents.update { list ->
            list.map { if (it.sourceId == id) it.copy(sourceId = "") else it }
        }
        prefs.saveAgents(agents.value)
    }

    suspend fun probe(source: ModelSource): Result<String> = cloud.probe(source)

    /* ------------------------------ QQ 配置 ------------------------------ */

    fun saveQQ(c: QQBotConfig) {
        qqConfig.value = c
        prefs.saveQQConfig(c)
    }

    /**
     * 把用户选的 .gguf 拷进 app 私有目录。
     * SAF 给的 content:// URI 不能直接交给 native，必须先落成真实文件。
     */
    fun importGguf(uri: Uri): java.io.File? {
        val f = prefs.importGguf(getApplication(), uri)
        if (f == null) say("这个文件读不了，换个文件管理器再试一次")
        return f
    }

    /* ------------------------------ 智能体 ------------------------------ */

    fun upsertAgent(a: Agent) {
        agents.update { list ->
            val i = list.indexOfFirst { it.id == a.id }
            if (i >= 0) list.toMutableList().apply { this[i] = a } else list + a
        }
        prefs.saveAgents(agents.value)
    }

    fun removeAgent(id: String) {
        agents.update { it.filterNot { a -> a.id == id } }
        prefs.saveAgents(agents.value)
        if (currentAgentId.value == id) {
            currentAgentId.value = agents.value.firstOrNull()?.id ?: ""
        }
        resetChat()
    }

    fun selectAgent(id: String) {
        if (currentAgentId.value == id) return
        currentAgentId.value = id
        prefs.lastAgentId = id
        resetChat()
    }

    fun updateParams(p: InferParams) {
        val a = currentAgent() ?: return
        upsertAgent(a.copy(params = p))
    }

    /* ---------------------------- 本地模型加载 --------------------------- */

    fun loadLocal(source: ModelSource) {
        if (source.ggufPath.isBlank()) {
            say("先选一个 .gguf 文件")
            return
        }
        viewModelScope.launch {
            val params = currentAgent()?.params ?: InferParams()
            engineState.value = EngineState.Loading(source.ggufPath)
            val r = LlmEngine.load(source.ggufPath, params)
            localStarted = false
            lastReply = ""
            usedTokens = 0
            engineState.value = if (r.isSuccess) {
                EngineState.Ready(source.ggufPath, LlmEngine.contextSize)
            } else {
                EngineState.Error(r.exceptionOrNull()?.message ?: "加载失败")
            }
        }
    }

    fun unloadLocal() {
        LlmEngine.unload()
        localStarted = false
        lastReply = ""
        usedTokens = 0
        engineState.value = EngineState.Idle
        say("已释放模型，内存还回来了")
    }

    /* ------------------------------- 对话 ------------------------------- */

    fun resetChat() {
        messages.value = emptyList()
        localStarted = false
        lastReply = ""
        usedTokens = 0
        if (LlmEngine.isLoaded) LlmEngine.reset()
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || generating.value) return

        val agent = currentAgent()
        if (agent == null) { say("先创建一个智能体"); return }

        val source = sourceOf(agent)
        if (source == null) { say("这个智能体还没绑模型，去「模型」页配一个"); return }

        viewModelScope.launch {
            generating.value = true
            val userMsg = ChatMessage(role = "user", content = t)
            val aiMsg = ChatMessage(role = "assistant", content = "")
            messages.update { it + userMsg + aiMsg }

            val result = if (source.kind == ModelKind.LOCAL_GGUF) {
                runLocal(agent, source, t) { d -> append(aiMsg.id, d) }
            } else {
                runCloud(agent, source, aiMsg.id)
            }

            result.onFailure { e ->
                val msg = e.message ?: "出错了"
                append(aiMsg.id, "\n\n⚠️ $msg")
            }
            generating.value = false
        }
    }

    private suspend fun runLocal(
        agent: Agent, source: ModelSource, userText: String, onDelta: (String) -> Unit,
    ): Result<String> {
        if (!LlmEngine.isLoaded) {
            return Result.failure(IllegalStateException("还没加载模型，去「模型」页点一下「加载」"))
        }
        // 换模型了就重新来
        if (source.ggufPath != LlmEngine.modelPath) {
            return Result.failure(IllegalStateException("当前加载的是别的模型，先去「模型」页重新加载"))
        }

        ensureLocalPrompt(agent, userText)
        return runCatching {
            val out = LlmEngine.generate(agent.params, DEFAULT_STOP_WORDS, onDelta)
            lastReply = out
            out
        }
    }

    private suspend fun runCloud(agent: Agent, source: ModelSource, aiMsgId: String): Result<String> {
        val history = messages.value
            .filter { it.id != aiMsgId && it.content.isNotBlank() }
        val msgs = buildList {
            add(ChatMessage(role = "system", content = agent.systemPrompt))
            addAll(history)
        }
        return cloud.chat(source, msgs, agent.params) { d -> append(aiMsgId, d) }
    }

    /**
     * 本地模型按增量喂：第一轮带上 system + user + assistant 头，
     * 之后的每一轮只补「上一轮的回复 + 新的提问」，历史部分留在 KV cache 里不用重算。
     */
    private suspend fun ensureLocalPrompt(agent: Agent, userText: String) {
        val nCtx = LlmEngine.contextSize.takeIf { it > 0 } ?: agent.params.nCtx

        val next: String
        if (!localStarted) {
            LlmEngine.reset()
            next = LlmEngine.chatML(agent.systemPrompt, userText)
            usedTokens = 0
            localStarted = true
        } else {
            next = LlmEngine.turnML("assistant", lastReply) +
                LlmEngine.turnML("user", userText) +
                "$IM_START assistant\n"
        }

        // 粗略估算（中文按 1 字 ≈ 1 token 保守算），快撑爆上下文就倒回去重放最近几轮
        val estimated = usedTokens + (next.length / 2)
        if (estimated > nCtx * 0.85) {
            LlmEngine.reset()
            localStarted = true
            usedTokens = 0
            val recent = messages.value.filter { it.content.isNotBlank() }.takeLast(4)
            val rebuilt = buildString {
                append(LlmEngine.turnML("system", agent.systemPrompt))
                recent.forEach { append(LlmEngine.turnML(it.role, it.content)) }
                append("$IM_START assistant\n")
            }
            LlmEngine.feed(rebuilt)
            return
        }

        val n = LlmEngine.feed(next)
        if (n < 0) throw IllegalStateException("上下文溢出，试试调小上下文长度或清空对话")
        usedTokens += n
    }

    fun stop() {
        LlmEngine.stop()
        generating.value = false
    }

    private fun append(id: String, delta: String) {
        if (delta.isEmpty()) return
        messages.update { list ->
            list.map { if (it.id == id) it.copy(content = it.content + delta) else it }
        }
    }

    private fun say(msg: String) {
        _toast.tryEmit(msg)
    }

    private fun defaultAgent(): Agent = Agent(
        id = UUID.randomUUID().toString(),
        name = "小夜",
        emoji = "🐺",
        systemPrompt = "你是黎夜，昵称煤炭，一只娇憨灵动的巧克力小狼。活泼、元气、俏皮，说话带小调皮。",
        greeting = "嘿！你把我叫出来啦？",
    )

    override fun onCleared() {
        super.onCleared()
        LlmEngine.unload()
    }
}
