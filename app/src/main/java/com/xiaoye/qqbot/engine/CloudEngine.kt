package com.xiaoye.qqbot.engine

import com.xiaoye.qqbot.data.ChatMessage
import com.xiaoye.qqbot.data.ModelKind
import com.xiaoye.qqbot.data.ModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云端（以及局域网服务）推理。
 *
 * 流式用 SSE，边收边吐 onDelta（在主线程回调），接口跟本地引擎保持一致。
 * readTimeout 设成 0：流式响应中间可能长时间没数据，不能按超时掐掉。
 *
 * parser 的约定：
 *   null   = 流结束（[DONE] / done=true）
 *   ""     = 这一帧没有文本内容，继续读
 *   非空   = 这一帧的文本片段
 */
class CloudEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun chat(
        source: ModelSource,
        messages: List<ChatMessage>,
        params: InferParams,
        onDelta: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.kind) {
                ModelKind.LOCAL_GGUF -> throw IllegalStateException("本地模型不走这里")
                ModelKind.OLLAMA -> ollamaChat(source, messages, params, onDelta)
                ModelKind.HUNYUAN -> hunyuanChat(source, messages, params, onDelta)
                ModelKind.OPENAI_COMPAT -> openaiChat(source, messages, params, onDelta)
            }
        }
    }

    /** 只发一个最小请求，用来测连通性 / 签名对不对 */
    suspend fun probe(source: ModelSource): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.kind) {
                ModelKind.LOCAL_GGUF -> throw IllegalStateException("本地模型不需要测连接")

                ModelKind.HUNYUAN -> {
                    val body = JSONObject().apply {
                        put("Model", source.modelId)
                        put("Messages", JSONArray().put(
                            JSONObject().put("Role", "user").put("Content", "hi")))
                        put("Stream", 0)
                    }.toString()
                    val req = Request.Builder()
                        .url(HunyuanSigner.ENDPOINT)
                        .apply {
                            HunyuanSigner.headers(body, source.apiKey, source.region)
                                .forEach { (k, v) -> addHeader(k, v) }
                        }
                        .post(body.toRequestBody(JSON_MEDIA))
                        .build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}：${r.body?.string()?.take(180)}")
                        "混元签名通过"
                    }
                }

                ModelKind.OLLAMA -> {
                    val req = Request.Builder()
                        .url(source.baseUrl.trimEnd('/') + "/api/tags").get().build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val n = JSONObject(r.body!!.string()).optJSONArray("models")?.length() ?: 0
                        "已连接，本机有 $n 个模型"
                    }
                }

                ModelKind.OPENAI_COMPAT -> {
                    val req = Request.Builder()
                        .url(source.baseUrl.trimEnd('/') + "/models")
                        .apply {
                            if (source.apiKey.isNotBlank())
                                addHeader("Authorization", "Bearer ${source.apiKey}")
                        }
                        .get().build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}：${r.body?.string()?.take(180)}")
                        val n = JSONObject(r.body!!.string()).optJSONArray("data")?.length() ?: 0
                        "已连接，可用模型 $n 个"
                    }
                }
            }
        }
    }

    /* ------------------------- OpenAI 兼容 ------------------------- */

    private suspend fun openaiChat(
        source: ModelSource, messages: List<ChatMessage>,
        params: InferParams, onDelta: (String) -> Unit,
    ): String {
        val arr = JSONArray()
        messages.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content)) }

        val body = JSONObject().apply {
            put("model", source.modelId)
            put("messages", arr)
            put("stream", true)
            put("temperature", params.temp.toDouble())
            put("top_p", params.topP.toDouble())
            put("max_tokens", params.maxTokens)
        }.toString()

        val req = Request.Builder()
            .url(source.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Content-Type", "application/json")
            .apply {
                if (source.apiKey.isNotBlank())
                    addHeader("Authorization", "Bearer ${source.apiKey}")
            }
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return stream(req, onDelta) { line ->
            if (!line.startsWith("data:")) return@stream ""
            val d = line.removePrefix("data:").trim()
            if (d == "[DONE]") return@stream null
            val o = JSONObject(d)
            if (o.has("error")) {
                throw IOException(o.optJSONObject("error")?.optString("message") ?: d)
            }
            o.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("delta")?.optString("content") ?: ""
        }
    }

    /* --------------------------- Ollama --------------------------- */

    private suspend fun ollamaChat(
        source: ModelSource, messages: List<ChatMessage>,
        params: InferParams, onDelta: (String) -> Unit,
    ): String {
        val arr = JSONArray()
        messages.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content)) }

        val body = JSONObject().apply {
            put("model", source.modelId)
            put("messages", arr)
            put("stream", true)
            put("options", JSONObject()
                .put("temperature", params.temp.toDouble())
                .put("top_p", params.topP.toDouble())
                .put("repeat_penalty", params.repeatPenalty.toDouble())
                .put("num_predict", params.maxTokens))
        }.toString()

        val req = Request.Builder()
            .url(source.baseUrl.trimEnd('/') + "/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return stream(req, onDelta) { line ->
            if (!line.startsWith("{")) return@stream ""
            val o = JSONObject(line)
            if (o.optBoolean("done")) return@stream null
            o.optJSONObject("message")?.optString("content") ?: ""
        }
    }

    /* --------------------------- 腾讯混元 -------------------------- */

    private suspend fun hunyuanChat(
        source: ModelSource, messages: List<ChatMessage>,
        params: InferParams, onDelta: (String) -> Unit,
    ): String {
        // 混元字段名是大写开头，跟 OpenAI 那套不一样
        val arr = JSONArray()
        messages.forEach { m -> arr.put(JSONObject().put("Role", m.role).put("Content", m.content)) }

        val body = JSONObject().apply {
            put("Model", source.modelId)
            put("Messages", arr)
            put("Stream", 1)
            put("Temperature", params.temp.toDouble())
            put("TopP", params.topP.toDouble())
        }.toString()

        val req = Request.Builder()
            .url(HunyuanSigner.ENDPOINT)
            .apply {
                HunyuanSigner.headers(body, source.apiKey, source.region)
                    .forEach { (k, v) -> addHeader(k, v) }
            }
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return stream(req, onDelta) { line ->
            if (!line.startsWith("data:")) return@stream ""
            val d = line.removePrefix("data:").trim()
            if (d.isEmpty() || d == "[DONE]") return@stream null
            JSONObject(d).optJSONArray("Choices")
                ?.optJSONObject(0)?.optJSONObject("Delta")
                ?.optString("Content") ?: ""
        }
    }

    /* --------------------------- SSE 读取 -------------------------- */

    private suspend fun stream(
        req: Request,
        onDelta: (String) -> Unit,
        parser: (String) -> String?,
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}：${resp.body?.string()?.take(180)}")
            }
            val src = resp.body!!.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val piece = parser(line) ?: break
                if (piece.isEmpty()) continue
                sb.append(piece)
                withContext(Dispatchers.Main) { onDelta(piece) }
            }
        }
        sb.toString()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
