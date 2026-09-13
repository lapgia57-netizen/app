package com.hosoyduc.app

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import kotlin.math.min

object CryptoUtils {

    fun md5(input: String): String = hashHex("MD5", input)
    fun sha256(input: String): String = hashHex("SHA-256", input)
    fun sha512(input: String): String = hashHex("SHA-512", input)

    private fun hashHex(algo: String, input: String): String {
        val digest = MessageDigest.getInstance(algo).digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Thuật toán Levenshtein để chống trùng lặp (giống bản web/extension gốc)
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

    // Mã hoá/giải mã AES-CBC cho tệp backup (khoá suy ra từ mật khẩu bằng SHA-256)
    fun aesEncrypt(plainText: String, password: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun aesDecrypt(cipherTextB64: String, password: String): String? {
        return try {
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
            val key = SecretKeySpec(keyBytes, "AES")
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
}
