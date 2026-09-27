package com.xiaoye.qqbot.qq

import com.xiaoye.qqbot.data.QQBotConfig
import com.xiaoye.qqbot.data.QQDecision
import com.xiaoye.qqbot.data.QQIncoming
import com.xiaoye.qqbot.data.TriggerMode
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.collections.ArrayList

/**
 * QQ 消息规则引擎 —— 决定"这条消息要不要回"。
 *
 * 四层过滤，顺序不能乱：
 *   1. 去重（同一个 msgId 短时间重复上报，多半是协议端重试）
 *   2. 群白名单（不在名单里的群直接无视）
 *   3. 冷却（同一个群/人刚回过就别连着回，防刷屏）
 *   4. 触发规则（@ / 前缀 / 关键词 / 全部）
 *
 * 另外负责把长回复切成多段，模拟真人打字节奏，避免一次性糊一大坨。
 */
class BotRules {

    /** 去重：LRU，key=msgId，value=过期时刻 */
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 256
    }

    /** 冷却：key=群号或用户ID，value=上次回复时刻 */
    private val lastReplyAt = Collections.synchronizedMap(LinkedHashMap<String, Long>())

    private val lock = Any()

    /** 先看该不该回；返回 null 表示"不回" */
    fun decide(cfg: QQBotConfig, msg: QQIncoming, now: Long = System.currentTimeMillis()): QQDecision {
        val text = msg.text.trim()
        if (text.isEmpty()) return QQDecision.Ignore

        // 1. 去重
        synchronized(lock) {
            val expire = seen[msg.id]
            if (expire != null && expire > now) return QQDecision.Ignore
            seen[msg.id] = now + cfg.dedupWindowSec * 1000L
        }

        // 2. 群白名单
        if (msg.groupId != null) {
            val allow = cfg.groupWhitelist.lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (allow.isNotEmpty() && msg.groupId !in allow) return QQDecision.Ignore
        } else if (!cfg.replyPrivate) {
            return QQDecision.Ignore // 私聊没开
        }

        // 3. 触发规则
        val hit = when (cfg.trigger) {
            TriggerMode.AT_ONLY -> msg.isAtMe
            TriggerMode.PREFIX -> {
                val ps = cfg.prefixes.lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }
                ps.any { text.startsWith(it) }
            }
            TriggerMode.KEYWORD -> {
                val ks = cfg.keywords.lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                ks.isEmpty() || ks.any { text.contains(it) }
            }
            TriggerMode.ALL_GROUP -> true
        }
        if (!hit) return QQDecision.Ignore

        // 4. 冷却（触发规则都过了再判，免得被无关消息刷掉窗口）
        val key = msg.groupId ?: "u:${msg.userId}"
        synchronized(lock) {
            val last = lastReplyAt[key] ?: 0L
            if (now - last < cfg.cooldownSec * 1000L) return QQDecision.Ignore
            lastReplyAt[key] = now
        }

        return QQDecision.Reply(stripTrigger(cfg, text))
    }

    /** 把触发词本身从问题里去掉，别让 "@小夜 你好" 里的 @ 混进 prompt */
    private fun stripTrigger(cfg: QQBotConfig, text: String): String {
        return when (cfg.trigger) {
            TriggerMode.PREFIX -> {
                val ps = cfg.prefixes.lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val hit = ps.firstOrNull { text.startsWith(it) }
                if (hit != null) text.removePrefix(hit).trim() else text
            }
            else -> text.replace(Regex("\\[CQ:at[^\\]]*\\]"), "").trim()
        }
    }

    /**
     * 长回复分段。优先在换行/句号处切，切不动才硬切，
     * 这样读起来自然，也降低被风控的概率。
     */
    fun segment(text: String, maxChars: Int): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxChars) return listOf(clean)

        val out = ArrayList<String>()
        var rest = clean
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '；', '.', '!', '?'), maxChars - 1)
            if (cut < maxChars / 2) cut = maxChars
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)

        // 兜底：最多 5 段，再多就截断，别在群里刷屏
        return if (out.size > 5) out.take(5) else out
    }

    fun reset() {
        synchronized(lock) {
            seen.clear()
            lastReplyAt.clear()
        }
    }
}
