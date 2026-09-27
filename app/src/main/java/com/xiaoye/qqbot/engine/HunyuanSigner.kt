package com.xiaoye.qqbot.engine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.LinkedHashMap

/**
 * 腾讯云 TC3-HMAC-SHA256 签名（腾讯混元用这套）
 *
 * 签名流程：
 *   1. 拼规范请求串 CanonicalRequest
 *   2. 拼待签串 StringToSign
 *   3. 逐层派生密钥：TC3+SecretKey → date → service → tc3-request
 *   4. HMAC 出签名，拼进 Authorization 头
 *
 * 密钥格式：SecretId:SecretKey（中间一个英文冒号）
 */
object HunyuanSigner {

    const val SERVICE = "hunyuan"
    const val HOST = "hunyuan.tencentcloudapi.com"
    const val ACTION = "ChatCompletions"
    const val VERSION = "2023-09-01"
    const val ENDPOINT = "https://$HOST"

    private const val ALGORITHM = "TC3-HMAC-SHA256"
    private const val CANONICAL_URI = "/"
    private const val CANONICAL_QUERY = ""
    private const val SIGNED_HEADERS = "content-type;host"

    /** 把 "SecretId:SecretKey" 拆开 */
    fun parseKey(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val i = trimmed.indexOf(':')
        require(i > 0) { "密钥格式不对，应该是 SecretId:SecretKey（中间英文冒号）" }
        return trimmed.substring(0, i).trim() to trimmed.substring(i + 1).trim()
    }

    /** 生成调用混元所需的全部请求头 */
    fun headers(payload: String, rawKey: String, region: String = "ap-guangzhou"): Map<String, String> {
        val (secretId, secretKey) = parseKey(rawKey)
        val ts = System.currentTimeMillis() / 1000
        val date = utcDate(ts)

        val canonicalHeaders = "content-type:application/json\nhost:$HOST\n"
        val hashedPayload = sha256Hex(payload)

        val canonicalRequest = listOf(
            "POST",
            CANONICAL_URI,
            CANONICAL_QUERY,
            canonicalHeaders,
            SIGNED_HEADERS,
            hashedPayload,
        ).joinToString("\n")

        val credentialScope = "$date/$SERVICE/tc3-request"
        val stringToSign = listOf(
            ALGORITHM,
            ts.toString(),
            credentialScope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val secretDate = hmac(("TC3$secretKey").toByteArray(StandardCharsets.UTF_8), date)
        val secretService = hmac(secretDate, SERVICE)
        val secretSigning = hmac(secretService, "tc3-request")
        val signature = bytesToHex(hmac(secretSigning, stringToSign))

        val authorization = listOf(
            "$ALGORITHM Credential=$secretId/$credentialScope",
            "SignedHeaders=$SIGNED_HEADERS",
            "Signature=$signature",
        ).joinToString(", ")

        return LinkedHashMap<String, String>().apply {
            put("Authorization", authorization)
            put("Content-Type", "application/json")
            put("Host", HOST)
            put("X-TC-Action", ACTION)
            put("X-TC-Version", VERSION)
            put("X-TC-Timestamp", ts.toString())
            put("X-TC-Region", region)
        }
    }

    private fun utcDate(tsSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(tsSeconds * 1000))

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256Hex(s: String): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)))

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }
}
