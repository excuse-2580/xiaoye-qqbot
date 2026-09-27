package com.xiaoye.qqbot.qq

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * OneBot HTTP 上报 —— App 在本机起一个极简 HTTP 服务收 POST。
 *
 * 为什么自己写而不用 NanoHTTPD/Ktor：少一个依赖就少一堆版本冲突，
 * 这里只需要"收一个 JSON POST"这么点功能，手写够用了。
 *
 * 注意：上报只负责"收"，要发消息还得反过来调协议端的 HTTP API
 * （配置里的 onebotApiBase，默认 http://127.0.0.1:5700）。
 */
class OneBotHttp(
    private val scope: CoroutineScope,
    private val onEvent: suspend (com.xiaoye.qqbot.data.QQIncoming) -> String?,
    private val onLog: (String) -> Unit,
    private val onState: (Boolean, String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(port: Int, apiBase: String, token: String) {
        stop()
        running = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                val ss = ServerSocket(port).apply { reuseAddress = true }
                server = ss
                onState(true, "监听 :$port")
                onLog("HTTP 上报已启动，端口 $port")
                while (running) {
                    val sock = ss.accept()
                    // 每个连接开一个协程，别让慢请求堵住后面
                    scope.launch(Dispatchers.IO) { handleConn(sock, apiBase, token) }
                }
            }.onFailure {
                onState(false, "启动失败：${it.message}")
                onLog("HTTP 上报启动失败：${it.message}")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        onState(false, "已停止")
    }

    private fun handleConn(sock: Socket, apiBase: String, token: String) {
        runCatching {
            sock.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                // 读请求行
                val line = reader.readLine() ?: return
                if (!line.startsWith("POST")) {
                    writeResponse(s, 200, """{"retcode":0}""")
                    return
                }

                // 读 header，拿 Content-Length 和签名
                var contentLength = 0
                var signature = ""
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    val lower = h.lowercase()
                    when {
                        lower.startsWith("content-length:") ->
                            contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        lower.startsWith("x-signature:") ->
                            signature = h.substringAfter(':').trim()
                    }
                }

                // 读 body
                val body = CharArray(contentLength).let { buf ->
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                }

                // 先验签再处理
                if (token.isNotBlank() && !OneBotProtocol.checkSignature(body, token, signature)) {
                    onLog("签名校验失败，丢弃一条上报")
                    writeResponse(s, 401, """{"retcode":1403}""")
                    return
                }

                writeResponse(s, 200, """{"retcode":0}""")

                // 回包发完再处理，别让协议端等 AI 推理（那会超时重发）
                handleBody(body, apiBase)
            }
        }.onFailure {
            onLog("连接处理异常：${it.message}")
        }
    }

    private fun handleBody(body: String, apiBase: String) {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return
        val msg = OneBotProtocol.parse(o) ?: return

        scope.launch(Dispatchers.IO) {
            val reply = runCatching { onEvent(msg) }.getOrNull()
            if (!reply.isNullOrBlank()) send(apiBase, msg, reply)
        }
    }

    /** 供 BotService 直接投递一条回复（分段发送时用） */
    fun sendDirect(apiBase: String, msg: com.xiaoye.qqbot.data.QQIncoming, text: String) {
        send(apiBase, msg, text)
    }

    private fun send(apiBase: String, msg: com.xiaoye.qqbot.data.QQIncoming, text: String) {
        val base = apiBase.trimEnd('/')
        val endpoint = if (msg.groupId != null) "$base/send_group_msg" else "$base/send_private_msg"
        val params = JSONObject().apply {
            if (msg.groupId != null) put("group_id", msg.groupId.toLongOrNull() ?: msg.groupId)
            else put("user_id", msg.userId.toLongOrNull() ?: msg.userId)
            put("message", text)
        }
        val req = Request.Builder()
            .url(endpoint)
            .post(params.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching { client.newCall(req).execute().use { } }
            .onFailure { onLog("回复发送失败：${it.message}") }
    }

    private fun writeResponse(s: Socket, code: Int, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Unauthorized"}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${b.size}\r\n" +
                "Connection: close\r\n\r\n"
        runCatching {
            s.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(b)
                out.flush()
            }
        }
    }
}
