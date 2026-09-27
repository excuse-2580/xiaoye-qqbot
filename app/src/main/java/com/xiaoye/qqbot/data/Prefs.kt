package com.xiaoye.qqbot.data

import android.content.Context
import android.net.Uri
import com.xiaoye.qqbot.engine.InferParams
import com.xiaoye.qqbot.security.KeystoreCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 用 SharedPreferences 存 JSON，够用就好，不引 Room */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("aas_store", Context.MODE_PRIVATE)

    /* ------------------------------ 模型源 ------------------------------ */

    fun loadSources(): List<ModelSource> {
        val raw = sp.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching {
                    ModelSource(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        kind = ModelKind.valueOf(o.optString("kind", ModelKind.OPENAI_COMPAT.name)),
                        baseUrl = o.optString("baseUrl"),
                        apiKey = o.optString("apiKey"),
                        modelId = o.optString("modelId"),
                        ggufPath = o.optString("ggufPath"),
                        region = o.optString("region", "ap-guangzhou"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun saveSources(list: List<ModelSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("kind", s.kind.name)
                put("baseUrl", s.baseUrl)
                put("apiKey", s.apiKey)
                put("modelId", s.modelId)
                put("ggufPath", s.ggufPath)
                put("region", s.region)
            })
        }
        sp.edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    /* ------------------------------ 智能体 ------------------------------ */

    fun loadAgents(): List<Agent> {
        val raw = sp.getString(KEY_AGENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching {
                    val p = o.optJSONObject("params")
                    Agent(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        emoji = o.optString("emoji", "🐺"),
                        systemPrompt = o.optString("systemPrompt"),
                        greeting = o.optString("greeting"),
                        sourceId = o.optString("sourceId"),
                        params = if (p == null) InferParamsWrapper.default() else InferParamsWrapper.fromJson(p),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun saveAgents(list: List<Agent>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("emoji", a.emoji)
                put("systemPrompt", a.systemPrompt)
                put("greeting", a.greeting)
                put("sourceId", a.sourceId)
                put("params", InferParamsWrapper.toJson(a.params))
            })
        }
        sp.edit().putString(KEY_AGENTS, arr.toString()).apply()
    }

    /* ------------------------------ QQ 机器人 ------------------------------ */

    /**
     * 读 QQ 配置。密钥字段（officialToken / accessToken）在盘上是密文，
     * 读出来解密再用；旧版本若存过明文，这里顺手升级成密文。
     */
    fun loadQQConfig(): QQBotConfig {
        val raw = sp.getString(KEY_QQ, null) ?: return QQBotConfig()
        return runCatching {
            val o = org.json.JSONObject(raw)
            QQBotConfig(
                mode = QQMode.valueOf(o.optString("mode", QQMode.OFF.name)),
                appId = o.optString("appId"),
                officialToken = KeystoreCrypto.maybeUpgrade(o.optString("officialToken")),
                officialBase = o.optString("officialBase", "https://bots.qq.com"),
                wsUrl = o.optString("wsUrl"),
                httpPort = o.optInt("httpPort", 5700),
                onebotApiBase = o.optString("onebotApiBase", "http://127.0.0.1:5700"),
                accessToken = KeystoreCrypto.maybeUpgrade(o.optString("accessToken")),
                trigger = TriggerMode.valueOf(o.optString("trigger", TriggerMode.AT_ONLY.name)),
                prefixes = o.optString("prefixes", "小夜\n!"),
                keywords = o.optString("keywords"),
                groupWhitelist = o.optString("groupWhitelist"),
                replyPrivate = o.optBoolean("replyPrivate", true),
                dedupWindowSec = o.optInt("dedupWindowSec", 5),
                segmentChars = o.optInt("segmentChars", 300),
                segmentDelayMs = o.optLong("segmentDelayMs", 800L),
                cooldownSec = o.optInt("cooldownSec", 3),
                maxQueue = o.optInt("maxQueue", 8),
            )
        }.getOrDefault(QQBotConfig())
    }

    /** 存 QQ 配置：密钥类字段加密后落盘，绝不写明文 */
    fun saveQQConfig(c: QQBotConfig) {
        val o = org.json.JSONObject().apply {
            put("mode", c.mode.name)
            put("appId", c.appId)
            put("officialToken", KeystoreCrypto.encrypt(c.officialToken))
            put("officialBase", c.officialBase)
            put("wsUrl", c.wsUrl)
            put("httpPort", c.httpPort)
            put("onebotApiBase", c.onebotApiBase)
            put("accessToken", KeystoreCrypto.encrypt(c.accessToken))
            put("trigger", c.trigger.name)
            put("prefixes", c.prefixes)
            put("keywords", c.keywords)
            put("groupWhitelist", c.groupWhitelist)
            put("replyPrivate", c.replyPrivate)
            put("dedupWindowSec", c.dedupWindowSec)
            put("segmentChars", c.segmentChars)
            put("segmentDelayMs", c.segmentDelayMs)
            put("cooldownSec", c.cooldownSec)
            put("maxQueue", c.maxQueue)
        }
        sp.edit().putString(KEY_QQ, o.toString()).apply()
    }

    var lastAgentId: String?
        get() = sp.getString(KEY_LAST_AGENT, null)
        set(v) = sp.edit().putString(KEY_LAST_AGENT, v).apply()

    var dynamicColor: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(v) = sp.edit().putBoolean(KEY_DYNAMIC_COLOR, v).apply()

    var darkMode: Int  // 0 跟随系统 / 1 浅色 / 2 深色
        get() = sp.getInt(KEY_DARK, 0)
        set(v) = sp.edit().putInt(KEY_DARK, v).apply()

    /* --------------------------- GGUF 文件导入 -------------------------- */

    /**
     * 把用户选的 .gguf 拷进 app 私有目录。
     * native 需要真实文件路径，SAF 给的 content:// URI 直接传过去读不了。
     */
    fun importGguf(context: Context, uri: Uri): File? = runCatching {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val name = displayName(context, uri) ?: "model.gguf"
        val out = File(dir, name.sanitize())
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        }.getOrNull()

    private fun String.sanitize(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").let {
            if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf"
        }

    companion object {
        private const val KEY_SOURCES = "sources"
        private const val KEY_AGENTS = "agents"
        private const val KEY_LAST_AGENT = "last_agent"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DARK = "dark"
        private const val KEY_QQ = "qq_config"
    }
}

/** InferParams 的存取（JSON 里只有 Double，没有 Float） */
object InferParamsWrapper {
    fun default(): InferParams = InferParams()

    fun toJson(p: InferParams): JSONObject = JSONObject().apply {
        put("nCtx", p.nCtx)
        put("nThreads", p.nThreads)
        put("temp", p.temp.toDouble())
        put("topP", p.topP.toDouble())
        put("topK", p.topK)
        put("repeatPenalty", p.repeatPenalty.toDouble())
        put("repeatWindow", p.repeatWindow)
        put("maxTokens", p.maxTokens)
        put("seed", p.seed)
        put("useMmap", p.useMmap)
    }

    fun fromJson(o: JSONObject): InferParams = InferParams(
        nCtx = o.optInt("nCtx", 2048),
        nThreads = o.optInt("nThreads", 4),
        temp = o.optDouble("temp", 0.8).toFloat(),
        topP = o.optDouble("topP", 0.9).toFloat(),
        topK = o.optInt("topK", 40),
        repeatPenalty = o.optDouble("repeatPenalty", 1.1).toFloat(),
        repeatWindow = o.optInt("repeatWindow", 64),
        maxTokens = o.optInt("maxTokens", 512),
        seed = o.optLong("seed", 0L),
        useMmap = o.optBoolean("useMmap", true),
    )
}
