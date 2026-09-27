package com.xiaoye.qqbot.qq

import com.xiaoye.qqbot.data.QQIncoming
import org.json.JSONArray
import org.json.JSONObject

/**
 * OneBot v11 报文解析 —— 反向 WS 和 HTTP 上报共用。
 *
 * 两个坑：
 *   1. message 字段既可能是纯字符串，也可能是 CQ 码数组（协议端实现不一）
 *   2. @判定要看 message 里的 CQ:at 是否指向 self_id
 */
object OneBotProtocol {

    /** 把一条上报报文压平成 QQIncoming；不是消息事件或没文本内容返回 null */
    fun parse(o: JSONObject): QQIncoming? {
        val postType = o.optString("post_type")
        if (postType != "message" && postType != "message_sent") return null

        val isGroup = o.optString("message_type") == "group"
        val groupId = if (isGroup) o.optString("group_id").ifBlank { null } else null
        val userId = o.optString("user_id")
        val msgId = o.optString("message_id").ifBlank { "${System.currentTimeMillis()}" }

        val text = flatten(o.opt("message"))
        if (text.isBlank()) return null

        val selfId = o.optString("self_id")
        val atTag = "[CQ:at,qq=$selfId]"
        val isAtMe = text.contains(atTag) || o.optString("raw_message").contains(atTag)

        return QQIncoming(
            id = msgId,
            text = text,
            groupId = groupId,
            userId = userId,
            isAtMe = isAtMe,
            replyTo = groupId ?: userId,
            raw = o.toString(),
        )
    }

    /** message 可能是 String 也可能是 JSONArray */
    fun flatten(raw: Any?): String = when (raw) {
        null -> ""
        is String -> raw
        is JSONArray -> buildString {
            for (i in 0 until raw.length()) {
                val seg = raw.optJSONObject(i) ?: continue
                if (seg.optString("type") == "text") {
                    append(seg.optJSONObject("data")?.optString("text").orEmpty())
                } else {
                    append(seg.toString())
                }
            }
        }
        else -> raw.toString()
    }

    /** 发消息的 action 报文（反向 WS 用） */
    fun replyAction(msg: QQIncoming, text: String): String {
        val o = JSONObject()
        if (msg.groupId != null) {
            o.put("action", "send_group_msg")
            o.put("params", JSONObject()
                .put("group_id", msg.groupId.toLongOrNull() ?: msg.groupId)
                .put("message", text))
        } else {
            o.put("action", "send_private_msg")
            o.put("params", JSONObject()
                .put("user_id", msg.userId.toLongOrNull() ?: msg.userId)
                .put("message", text))
        }
        o.put("echo", "reply-${System.currentTimeMillis()}")
        return o.toString()
    }

    /** HMAC-SHA1 签名校验（HTTP 上报的 X-Signature） */
    fun checkSignature(body: String, token: String, signature: String): Boolean {
        if (token.isBlank() || signature.isBlank()) return false
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val hex = mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return signature.removePrefix("sha1=").equals(hex, ignoreCase = true)
    }
}
