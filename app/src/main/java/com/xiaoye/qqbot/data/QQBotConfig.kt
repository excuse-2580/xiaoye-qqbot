package com.xiaoye.qqbot.data

/**
 * QQ 接入配置
 *
 * 三种模式：
 *   OFFICIAL   —— QQ 开放平台官方 API（q.qq.com 建机器人），合规稳定，推荐长期使用
 *   ONEBOT_WS  —— OneBot 反向 WebSocket，App 主动连出去（不用暴露端口，最省事）
 *   ONEBOT_HTTP—— OneBot HTTP 上报，App 在本机起服务收 POST
 *
 * 关于风险：OneBot 属于第三方协议，腾讯并没有授权，
 * 用主号跑有封号风险 —— 务必用小号测试，别拿常用号冒险。
 */
enum class QQMode { OFF, OFFICIAL, ONEBOT_WS, ONEBOT_HTTP }

/** 什么情况下才搭理这条消息 */
enum class TriggerMode {
    AT_ONLY,      // 只有 @机器人 才回（最安静）
    PREFIX,       // 指定前缀，比如 "小夜" 或 "!"
    KEYWORD,      // 命中关键词才回
    ALL_GROUP,    // 群里啥都回（慎用，容易被踢）
}

data class QQBotConfig(
    val mode: QQMode = QQMode.OFF,

    /* ---------- 官方 API ---------- */
    /** q.qq.com 后台拿到的 AppID */
    val appId: String = "",
    /** 机器人令牌，加密存储 */
    val officialToken: String = "",
    /** 官方沙箱/正式环境地址 */
    val officialBase: String = "https://bots.qq.com",

    /* ---------- OneBot ---------- */
    /** 反向 WS：要连出去的地址，如 ws://192.168.1.20:3001 */
    val wsUrl: String = "",
    /** HTTP 上报：本机监听端口 */
    val httpPort: Int = 5700,
    /**
     * OneBot 的 HTTP API 地址（HTTP 上报模式下发消息要用，
     * 因为上报只负责收，发还得调协议端的接口）
     * 例：http://127.0.0.1:5700
     */
    val onebotApiBase: String = "http://127.0.0.1:5700",
    /** OneBot 的 access_token，可留空 */
    val accessToken: String = "",

    /* ---------- 触发与限流 ---------- */
    val trigger: TriggerMode = TriggerMode.AT_ONLY,
    /** TriggerMode.PREFIX 用，多个用换行分隔 */
    val prefixes: String = "小夜\n!",
    /** TriggerMode.KEYWORD 用，多个用换行分隔 */
    val keywords: String = "",
    /** 群白名单，换行分隔；留空 = 全部放行 */
    val groupWhitelist: String = "",
    /** 群里是否回复私聊也回 */
    val replyPrivate: Boolean = true,
    /** 同一条消息多久内不重复处理（秒），防抖 */
    val dedupWindowSec: Int = 5,
    /** 单条回复超过多少字就分段发 */
    val segmentChars: Int = 300,
    /** 分段之间的间隔（毫秒），太快容易被风控 */
    val segmentDelayMs: Long = 800L,
    /** 同一个群/人，两次回复最小间隔（秒），防止刷屏 */
    val cooldownSec: Int = 3,
    /** 多少人同时排队等回复，超了直接丢弃 */
    val maxQueue: Int = 8,
)

/** 一条待处理的 QQ 消息，各模式统一成这个结构再交给引擎 */
data class QQIncoming(
    val id: String,               // 消息唯一 ID，用于去重
    val text: String,             // 纯文本（CQ 码已剥掉）
    val groupId: String? = null,  // null = 私聊
    val userId: String = "",
    val isAtMe: Boolean = false,  // 是否 @了机器人
    val replyTo: String = "",     // 回给谁：群号 或 用户 ID
    val raw: String = "",         // 原始报文，调试用
)

/** 处理完的结论：不回 / 回复内容 */
sealed interface QQDecision {
    data object Ignore : QQDecision
    data class Reply(val text: String) : QQDecision
}
