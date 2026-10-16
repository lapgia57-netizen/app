package com.hosoyduc.app

import java.security.MessageDigest

/**
 * Hash nhanh + "con dấu thời gian" (seal).
 *
 * Seal gắn NỘI DUNG + THỜI GIAN (savedAt + toàn bộ múi giờ) vào cùng một bộ hash,
 * nên sửa nội dung hoặc sửa bất kỳ mốc thời gian nào đều làm hash lệch -> phát hiện được.
 * Khoá bản ghi (hashedKey) vẫn là md5(nội dung) để chống trùng không phụ thuộc thời gian.
 */
object HashUtils {
    private val HEX = "0123456789abcdef".toCharArray()

    // Nhanh hơn "%02x".format(it) hàng chục lần (không tạo String cho từng byte)
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun hash(algo: String, input: String): String =
        toHex(MessageDigest.getInstance(algo).digest(input.toByteArray(Charsets.UTF_8)))

    fun md5(input: String) = hash("MD5", input)
    fun sha256(input: String) = hash("SHA-256", input)
    fun sha512(input: String) = hash("SHA-512", input)

    /** Chuỗi thời gian chuẩn hoá (sắp xếp theo mã múi giờ) -> kết quả ổn định giữa các thiết bị. */
    fun canonicalTime(savedAt: Long, times: Map<String, String>): String {
        val sb = StringBuilder("t=").append(savedAt)
        for ((k, v) in times.toSortedMap()) sb.append(';').append(k).append('=').append(v)
        return sb.toString()
    }

    data class Seal(
        val timeSha256: String,        // hash riêng của thời gian
        val contentTimeSha256: String, // hash (nội dung + thời gian)
        val contentTimeSha512: String
    )

    fun seal(text: String, savedAt: Long, times: Map<String, String>): Seal {
        val canon = canonicalTime(savedAt, times)
        val bound = text + "\u0000" + canon
        return Seal(sha256(canon), sha256(bound), sha512(bound))
    }

    /** true = khớp, false = bị sửa, null = bản ghi cũ chưa có seal. */
    fun verify(text: String, savedAt: Long, times: Map<String, String>, ctSha256: String?): Boolean? {
        if (ctSha256.isNullOrEmpty()) return null
        return seal(text, savedAt, times).contentTimeSha256 == ctSha256
    }
}
