package com.xiaoye.qqbot.qq

import com.xiaoye.qqbot.data.QQIncoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OneBot 反向 WebSocket —— 推荐模式
 *
 * App 作为客户端主动连出去（连 Lagrange / LLOneBot / NapCat 等协议端的反向 WS 地址），
 * 好处是不用在本机暴露端口、不用配内网穿透，手机上最省事。
 *
 * 收发都走这一条连接：
 *   收 event  → onMessage
 *   发消息    → 发 {"action":"send_group_msg","params":{...},"echo":...}
 */
class OneBotWs(
    private val scope: CoroutineScope,
    private val onEvent: suspend (QQIncoming) -> String?,   // 返回要回复的内容，null=不回
    private val onLog: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var job: Job? = null
    private val closedByUs = AtomicBoolean(false)

    fun start(url: String, token: String) {
        stop()
        closedByUs.set(false)
        job = scope.launch(Dispatchers.IO) { connectLoop(url, token) }
    }

    /** 供 BotService 直接投递一条回复（分段发送时用） */
    fun sendRaw(payload: String) {
        val w = ws
        if (w == null) { onLog("WS 未连接，丢弃一条回复"); return }
        runCatching { w.send(payload) }.onFailure { onLog("发送失败：${it.message}") }
    }

    fun stop() {
        closedByUs.set(true)
        job?.cancel()
        job = null
        runCatching { ws?.close(1000, "bye") }
        ws = null
        onState(false, "已断开")
    }

    /** 断线重连：最多 10 次，间隔指数退避到 30 秒封顶 */
    private suspend fun connectLoop(url: String, token: String) {
        var retry = 0
        while (retry < 10) {
            if (closedByUs.get()) return
            onState(false, "连接中…（第 ${retry + 1} 次）")
            val ok = runCatching { openOnce(url, token) }.getOrDefault(false)
            if (ok) { retry = 0 }
            if (closedByUs.get()) return
            retry++
            val wait = (2000L shl minOf(retry, 4)).coerceAtMost(30_000L)
            onState(false, "断开，${wait / 1000}s 后重连")
            delay(wait)
        }
        onState(false, "重连次数用尽，检查协议端是否在线")
    }

    /** 建立一次连接并阻塞到它断开；成功连上且正常收发返回 true */
    private suspend fun openOnce(url: String, token: String): Boolean {
        val req = Request.Builder().url(url).apply {
            if (token.isNotBlank()) {
                addHeader("Authorization", "Bearer $token")
            }
        }.build()

        var done = false
        var success = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                success = true
                onState(true, "已连接")
                onLog("WS 已连接：$url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text, webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("WS 异常：${t.message}")
                success = false
                done = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("WS 关闭：$code $reason")
                done = true
            }
        }

        ws = client.newWebSocket(req, listener)

        // 等它断开（onFailure/onClosed 会把 done 置位）
        while (!done) delay(300)
        return success
    }

    private fun handle(text: String, webSocket: WebSocket) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return

        // echo 回包是 API 调用结果，不是事件，忽略
        if (o.has("echo") && !o.has("post_type")) return

        val postType = o.optString("post_type")
        if (postType != "message" && postType != "message_sent") return

        val msg = OneBotProtocol.parse(o) ?: return
        scope.launch(Dispatchers.IO) {
            val reply = runCatching { onEvent(msg) }.getOrNull()
            if (!reply.isNullOrBlank()) {
                sendReply(webSocket, msg, reply)
            }
        }
    }

    private fun sendReply(webSocket: WebSocket, msg: QQIncoming, text: String) {
        val payload = OneBotProtocol.replyAction(msg, text)
        runCatching { webSocket.send(payload) }
            .onFailure { onLog("发送失败：${it.message}") }
    }
}
