package com.xiaoye.qqbot.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储 —— 走 Android Keystore，明文密钥不落盘、不出 TEE/SE。
 *
 * 做法：
 *   - 在 AndroidKeyStore 里生成一把 AES-256-GCM 密钥，KeyStore 只存句柄，拿不到原料
 *   - 每次加密都生成随机 12 字节 IV，密文格式：[12 字节 IV] + [密文+16 字节 GCM Tag]
 *   - 结果 Base64 后存 SharedPreferences，即使被 root 导出也只是密文
 *
 * 注意：GCM 每次必须用新 IV，重复用同一 IV 会直接泄露明文。
 */
object KeystoreCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "xiaoye_qqbot_apikey"
    private const val AES = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun keystore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keystore()
        ks.getKey(ALIAS, null)?.let { return it as SecretKey }

        val gen = KeyGenerator.getInstance(AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(BLOCK)
                .setEncryptionPaddings(PADDING)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true) // 强制每次加密换 IV
                .build()
        )
        return gen.generateKey()
    }

    /** 明文 -> Base64 密文。明文为空直接返回空串。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("$AES/$BLOCK/$PADDING")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(body, 0, out, iv.size, body.size)
            android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
        }.getOrDefault(plain) // 极端情况（设备无 Keystore）退化，但绝不崩
    }

    /** Base64 密文 -> 明文。解密失败返回空串，不让上层拿到半截脏数据。 */
    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return runCatching {
            val raw = android.util.Base64.decode(cipherText, android.util.Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("$AES/$BLOCK/$PADDING")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** 旧版本可能存过明文，读的时候顺手升级成密文 */
    fun maybeUpgrade(value: String): String =
        if (value.isEmpty() || isEncrypted(value)) value else encrypt(value)

    /** Base64 且长度合理就认为是我们的密文格式 */
    private fun isEncrypted(v: String): Boolean =
        v.length > IV_BYTES && v.matches(Regex("^[A-Za-z0-9+/=]+$"))
}
