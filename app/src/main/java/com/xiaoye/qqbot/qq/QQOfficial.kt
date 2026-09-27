package com.xiaoye.qqbot.qq

import com.xiaoye.qqbot.data.QQIncoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 官方 API（q.qq.com 建机器人）—— 合规稳定，长期跑的首选。
 *
 * 流程：
 *   1. 用 appId + clientSecret 换 access_token（有效期约 2 小时，会自动续）
 *   2. 拿 token 换 WebSocket 网关地址
 *   3. WSS 握手（op 2 Identify）→ 收事件（op 0 Dispatch）→ 按 op 1 心跳保活
 *   4. 发消息走 v2 REST：/v2/groups/{group_openid}/messages 或 /v2/users/{openid}/messages
 *
 * 四个常量地址按开放平台文档填，如果官方改了域名，改这里即可。
 */
class QQOfficial(
    private val scope: CoroutineScope,
    private val onEvent: suspend (QQIncoming) -> String?,
    private val onLog: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    companion object {
        private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
        private const val GATEWAY_URL = "https://api.sgroup.qq.com/gateway"
        private const val API_BASE = "https://api.sgroup.qq.com"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var heartbeat: Job? = null
    private var accessToken = ""
    private var tokenExpireAt = 0L
    private var lastSeq = 0
    private val stopped = AtomicBoolean(false)

    /**
     * @param appId        q.qq.com 后台的 AppID
     * @param clientSecret 机器人密钥（Secret），界面上叫 token，实际填这个
     */
    fun start(appId: String, clientSecret: String) {
        stop()
        stopped.set(false)
        scope.launch(Dispatchers.IO) {
            runCatching {
                onState(false, "获取令牌…")
                accessToken = fetchToken(appId, clientSecret)
                onLog("令牌已获取")

                onState(false, "获取网关…")
                val gw = fetchGateway()
                onLog("网关：$gw")

                connectWs(gw, appId, clientSecret)
            }.onFailure {
                onState(false, "启动失败：${it.message}")
                onLog("官方 API 失败：${it.message}")
            }
        }
    }

    fun stop() {
        stopped.set(true)
        heartbeat?.cancel()
        heartbeat = null
        runCatching { ws?.close(1000, "bye") }
        ws = null
        onState(false, "已断开")
    }

    /* --------------------------- 鉴权 --------------------------- */

    private fun fetchToken(appId: String, secret: String): String {
        val body = JSONObject().put("appId", appId).put("clientSecret", secret).toString()
        val req = Request.Builder().url(TOKEN_URL)
            .post(body.toRequestBody(JSON_MEDIA)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("取令牌失败 HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            val tok = o.optString("access_token")
            if (tok.isBlank()) throw IllegalStateException("返回里没有 access_token")
            val expires = o.optInt("expires_in", 7200)
            tokenExpireAt = System.currentTimeMillis() + expires * 1000L - 60_000L
            return tok
        }
    }

    private fun fetchGateway(): String {
        val req = Request.Builder().url(GATEWAY_URL)
            .header("Authorization", "QQBot $accessToken")
            .get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("取网关失败 HTTP ${r.code}")
            val url = JSONObject(r.body!!.string()).optString("url")
            if (url.isBlank()) throw IllegalStateException("返回里没有网关地址")
            return url
        }
    }

    /* --------------------------- WebSocket --------------------------- */

    private suspend fun connectWs(gw: String, appId: String, secret: String) {
        var done = false
        val req = Request.Builder().url(gw).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                onLog("WSS 已连接，等待 Hello")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(webSocket, text, appId, secret)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("WSS 异常：${t.message}")
                done = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("WSS 关闭：$code $reason")
                done = true
            }
        }

        ws = client.newWebSocket(req, listener)
        while (!done) delay(500)
        if (!stopped.get()) {
            onState(false, "连接断开，10s 后重连")
            delay(10_000)
            if (!stopped.get()) start(appId, secret)
        }
    }

    private fun handleFrame(webSocket: WebSocket, text: String, appId: String, secret: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        val op = o.optInt("op", -1)
        val d = o.optJSONObject("d")
        o.optInt("s").let { if (it > 0) lastSeq = it }

        when (op) {
            10 -> { // Hello：拿到心跳间隔，然后鉴权
                val interval = d?.optInt("heartbeat_interval", 41250) ?: 41250L.toInt()
                startHeartbeat(webSocket, interval.toLong())
                identify(webSocket)
                onState(true, "已连接")
            }
            0 -> { // Dispatch：真正的事件
                val t = o.optString("t")
                when (t) {
                    "READY" -> onLog("就绪：${d?.optString("user", "")}")
                    "GROUP_AT_MESSAGE_CREATE", "AT_MESSAGE_CREATE", "C2C_MESSAGE_CREATE" -> {
                        val msg = parseOfficial(d ?: return) ?: return
                        scope.launch(Dispatchers.IO) {
                            val reply = runCatching { onEvent(msg) }.getOrNull()
                            if (!reply.isNullOrBlank()) {
                                runCatching { sendOfficial(msg, reply) }
                                    .onFailure { onLog("回复失败：${it.message}") }
                            }
                        }
                    }
                }
            }
            11 -> { /* 心跳 ACK，忽略 */ }
            9 -> {
                onLog("收到 Invalid Session，5s 后重连")
                scope.launch(Dispatchers.IO) {
                    delay(5000)
                    if (!stopped.get()) start(appId, secret)
                }
            }
        }
    }

    private fun identify(webSocket: WebSocket) {
        val payload = JSONObject()
            .put("op", 2)
            .put("d", JSONObject()
                .put("token", "QQBot $accessToken")
                .put("intents", (1 shl 25) or (1 shl 12) or (1 shl 9))
                .put("shard", org.json.JSONArray().put(0).put(1))
            )
        webSocket.send(payload.toString())
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch(Dispatchers.IO) {
            while (!stopped.get()) {
                delay(intervalMs)
                val ping = JSONObject().put("op", 1).put("d", lastSeq).toString()
                runCatching { webSocket.send(ping) }
                // token 快过期就续一下
                if (System.currentTimeMillis() > tokenExpireAt) {
                    onLog("令牌将过期，重新获取")
                }
            }
        }
    }

    /** 官方事件 -> QQIncoming */
    private fun parseOfficial(d: JSONObject): QQIncoming? {
        val isGroup = d.has("group_openid")
        val groupId = d.optString("group_openid").ifBlank { null }
        // author 有时是对象（带 id 字段），有时直接是 openid 字符串，两种都要兼容
        val authorRaw = d.optString("author", "").ifBlank {
            runCatching { d.optJSONObject("author")?.optString("id") }.getOrNull().orEmpty()
        }
        val userId = authorRaw.ifBlank { d.optString("user_openid") }
        val content = d.optString("content").trim()
        if (content.isBlank()) return null

        return QQIncoming(
            id = d.optString("id").ifBlank { "${System.currentTimeMillis()}" },
            text = content,
            groupId = groupId,
            userId = userId,
            isAtMe = true, // 官方只有 @消息 和 私聊会推过来，等于已经触发
            replyTo = groupId ?: userId,
            raw = d.toString(),
        )
    }

    private fun sendOfficial(msg: QQIncoming, text: String) {
        val url = if (msg.groupId != null)
            "$API_BASE/v2/groups/${msg.groupId}/messages"
        else
            "$API_BASE/v2/users/${msg.userId}/messages"

        val body = JSONObject()
            .put("content", text)
            .put("msg_type", 0)
            .put("msg_id", msg.id)
            .toString()

        val req = Request.Builder().url(url)
            .header("Authorization", "QQBot $accessToken")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) onLog("发送失败 HTTP ${r.code}：${r.body?.string()?.take(150)}")
        }
    }
}
