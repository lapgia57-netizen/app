package com.hosoyduc.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

object CryptoUtils {

    fun md5(input: String): String = HashUtils.md5(input)
    fun sha256(input: String): String = HashUtils.sha256(input)
    fun sha512(input: String): String = HashUtils.sha512(input)

    // Bản tham chiếu Levenshtein (giữ lại để đối chiếu/kiểm thử). Đường nóng dùng Dedup.Matcher.
    fun similarity(a: String, b: String, threshold: Double = 0.68): Double {
        val s1 = a.lowercase().trim()
        val s2 = b.lowercase().trim()
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        val maxLen = maxOf(len1, len2)
        val maxAllowedDiff = (maxLen * (1 - threshold)).toInt()
        if (kotlin.math.abs(len1 - len2) > maxAllowedDiff) return 0.0

        var v0 = IntArray(len2 + 1) { it }
        var v1 = IntArray(len2 + 1)

        for (i in 0 until len1) {
            v1[0] = i + 1
            var minDistance = v1[0]
            for (j in 0 until len2) {
                val cost = if (s1[i] == s2[j]) 0 else 1
                v1[j + 1] = min(min(v1[j] + 1, v0[j + 1] + 1), v0[j] + cost)
                if (v1[j + 1] < minDistance) minDistance = v1[j + 1]
            }
            if (minDistance > maxAllowedDiff) return 0.0
            val tmp = v0; v0 = v1; v1 = tmp
        }
        return (maxLen - v0[len2]).toDouble() / maxLen
    }

    /**
     * AES-CBC cho tệp backup. Khoá suy ra từ mật khẩu bằng SHA-256 — đúng định dạng cũ nên backup cũ vẫn đọc được.
     * Khác bản cũ: khoá chỉ tính MỘT lần cho cả tệp (trước đây băm lại mật khẩu cho từng bản ghi).
     */
    class AesCodec(password: String) {
        private val key = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)), "AES"
        )
        private val random = SecureRandom()

        fun encrypt(plainText: String): String {
            val iv = ByteArray(16).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        }

        fun decrypt(cipherTextB64: String): String? = try {
            val combined = Base64.decode(cipherTextB64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 16)
            val encrypted = combined.copyOfRange(16, combined.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun aesEncrypt(plainText: String, password: String): String = AesCodec(password).encrypt(plainText)
    fun aesDecrypt(cipherTextB64: String, password: String): String? = AesCodec(password).decrypt(cipherTextB64)
}
