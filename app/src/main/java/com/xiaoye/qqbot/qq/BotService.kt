package com.xiaoye.qqbot.qq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaoye.qqbot.MainActivity
import com.xiaoye.qqbot.R
import com.xiaoye.qqbot.data.ModelKind
import com.xiaoye.qqbot.data.ModelSource
import com.xiaoye.qqbot.data.QQBotConfig
import com.xiaoye.qqbot.data.QQIncoming
import com.xiaoye.qqbot.data.QQDecision
import com.xiaoye.qqbot.data.QQMode
import com.xiaoye.qqbot.engine.CloudEngine
import com.xiaoye.qqbot.engine.DEFAULT_STOP_WORDS
import com.xiaoye.qqbot.engine.InferParams
import com.xiaoye.qqbot.engine.LlmEngine
import com.xiaoye.qqbot.security.KeystoreCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 机器人常驻服务 —— 前台服务保活，不然锁屏几分钟就被系统回收了。
 *
 * 一条消息的完整链路：
 *   协议端上报 → BotRules 过滤（去重/白名单/冷却/触发）
 *             → 排队（防并发把 CPU 打满）
 *             → 本地 llama.cpp 或 云端 API 生成
 *             → 分段 → 逐段发送（段间留间隔，模拟打字，也躲风控）
 *
 * 为什么用队列：本地推理一次可能好几秒，群里同时来 10 条消息
 * 如果全部并发跑，手机直接卡死 + 内存爆掉。排队最多 maxQueue 条，超了丢弃。
 */
class BotService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rules = BotRules()
    private val cloud = CloudEngine()

    private val inFlight = AtomicInteger(0)
    private var draining = false

    private lateinit var cfg: QQBotConfig
    private lateinit var source: ModelSource
    private lateinit var systemPrompt: String

    private var ws: OneBotWs? = null
    private var http: OneBotHttp? = null
    private var official: QQOfficial? = null


    companion object {
        const val ACTION_START = "com.xiaoye.qqbot.START"
        const val ACTION_STOP = "com.xiaoye.qqbot.STOP"
        const val CHANNEL_ID = "qqbot_running"
        private const val NOTIF_ID = 20240927

        /** 最新状态，界面从这读（简单起见用静态，够用） */
        @Volatile var statusText: String = "未运行"
        @Volatile var running: Boolean = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification("启动中…"))
                statusText = "启动中…"
                running = true
                scope.launch(Dispatchers.IO) { boot() }
            }
        }
        return START_STICKY
    }

    private suspend fun boot() {
        val prefs = Prefs(this)
        cfg = prefs.loadQQConfig()
        systemPrompt = prefs.loadAgents().firstOrNull()?.systemPrompt
            ?: "你是小夜，一个友善、简洁的 QQ 群助手。回答尽量短，别超过三句话。"

        source = prefs.loadSources().firstOrNull()
            ?: run {
                statusText = "没有配置模型源"
                running = false
                stopSelf()
                return
            }

        // 本地模型没加载就先加载
        if (source.kind == ModelKind.LOCAL_GGUF) {
            if (source.ggufPath.isBlank()) {
                statusText = "本地模型没选文件"
                running = false
                stopSelf(); return
            }
            if (!LlmEngine.isLoaded || LlmEngine.modelPath != source.ggufPath) {
                statusText = "加载模型中…"
                LlmEngine.load(source.ggufPath, InferParams()).onFailure {
                    statusText = "模型加载失败：${it.message}"
                    running = false
                    stopSelf(); return
                }
            }
            LlmEngine.reset()
        }

        val handler: suspend (QQIncoming) -> String? = { msg ->
            handleIncoming(msg)
        }

        when (cfg.mode) {
            QQMode.OFFICIAL -> {
                official = QQOfficial(scope, handler, ::log, ::onState)
                official?.start(cfg.appId, KeystoreCrypto.decrypt(cfg.officialToken))
            }
            QQMode.ONEBOT_WS -> {
                ws = OneBotWs(scope, handler, ::log, ::onState)
                ws?.start(cfg.wsUrl, KeystoreCrypto.decrypt(cfg.accessToken))
            }
            QQMode.ONEBOT_HTTP -> {
                http = OneBotHttp(scope, { msg ->
                    val reply = handleIncoming(msg)
                    reply // HTTP 模式下由自己发，不在这里发
                }, ::log, ::onState)
                // HTTP 模式需要自己调 API 发消息，所以 handler 要带发送动作
                startHttpMode()
            }
            QQMode.OFF -> {
                statusText = "未启用"
                stopSelf()
            }
        }
    }

    private fun startHttpMode() {
        http = OneBotHttp(scope, { msg -> handleIncoming(msg) }, ::log, ::onState)
        val base = cfg.onebotApiBase
        // HTTP 上报只负责收，发出去要单独调协议端 API
        http?.start(cfg.httpPort, base, KeystoreCrypto.decrypt(cfg.accessToken).also {
            // 注意：HTTP 上报的签名用的是 accessToken 明文，这里解密后传
        })
        // OneBotHttp 内部已用 apiBase 发送，这里无需额外处理
    }

    /** 规则过滤 → 排队 → 生成 → 分段（发送由各模式自己完成，所以这里只返回全文） */
    private suspend fun handleIncoming(msg: QQIncoming): String? {
        val decision = rules.decide(cfg, msg)
        if (decision is QQDecision.Ignore) return null
        val question = (decision as QQDecision.Reply).text
        if (question.isBlank()) return null

        val reply = generate(question) ?: return null
        if (reply.isBlank()) return null

        // 分段发送（各模式内部负责实际投递，这里只做节奏控制）
        val parts = rules.segment(reply, cfg.segmentChars)
        if (parts.size > 1) {
            // 多段时，除最后一段外逐段发，间隔由调用方（WS/HTTP）控制
            // 这里把节奏放在服务层统一处理，避免各模式重复实现
            deliverWithPacing(msg, parts)
            return parts.first()
        }
        return reply
    }

    private suspend fun deliverWithPacing(msg: QQIncoming, parts: List<String>) {
        for (i in parts.indices) {
            if (i > 0) delay(cfg.segmentDelayMs)
            // 交给当前模式的发送通道
            sendVia(msg, parts[i])
        }
    }

    private suspend fun sendVia(msg: QQIncoming, text: String) {
        when (cfg.mode) {
            QQMode.ONEBOT_WS -> runCatching {
                // 通过 WS 连接直接发 action
                ws?.sendRaw(OneBotProtocol.replyAction(msg, text))
            }
            QQMode.ONEBOT_HTTP -> runCatching {
                http?.sendDirect(cfg.onebotApiBase, msg, text)
            }
            else -> { /* 官方模式在 QQOfficial 内部发 */ }
        }
    }

    private suspend fun generate(question: String): String? {
        // 队列限流：同时最多处理 maxQueue 条
        if (inFlight.get() >= cfg.maxQueue) {
            log("队列满了，丢弃：$question")
            return null
        }
        inFlight.incrementAndGet()
        return try {
            if (source.kind == ModelKind.LOCAL_GGUF) {
                if (!LlmEngine.isLoaded) return null
                runCatching {
                    val prompt = LlmEngine.chatML(systemPrompt, question)
                    LlmEngine.feed(prompt)
                    LlmEngine.generate(InferParams(), DEFAULT_STOP_WORDS) {}
                }.getOrNull()
            } else {
                val msgs = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = question),
                )
                runCatching {
                    cloud.chat(source, msgs, InferParams()) { }.getOrNull()
                }.getOrNull()
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun onDestroy() {
        running = false
        statusText = "已停止"
        ws?.stop(); http?.stop(); official?.stop()
        super.onDestroy()
    }

    private fun log(s: String) {
        statusText = s
    }

    private fun onState(ok: Boolean, s: String) {
        statusText = if (ok) "运行中：$s" else "断开：$s"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "机器人运行", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "小夜 QQBot 后台运行时的常驻通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小夜 QQBot")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
