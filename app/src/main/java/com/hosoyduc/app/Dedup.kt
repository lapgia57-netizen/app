package com.hosoyduc.app

/**
 * Chống trùng lặp mờ (ngưỡng 0.68) nhanh hơn rất nhiều so với Levenshtein O(n*m) thuần.
 * Kết quả tương đương CryptoUtils.similarity(a, b) > 0.68, nhưng đi qua các bước lọc rẻ trước:
 *   1) lọc theo độ dài (SQL BETWEEN / bucket)  2) chữ ký ký tự 64-bit  3) Myers bit-parallel.
 */
object Dedup {
    const val THRESHOLD = 0.68

    fun normalize(s: String): String = s.lowercase().trim()

    /** Số sửa đổi tối đa cho phép với độ dài lớn nhất maxLen (giữ đúng công thức bản gốc). */
    fun maxDiff(maxLen: Int): Int = (maxLen * (1 - THRESHOLD)).toInt()

    /** Bitmask các ký tự xuất hiện. Dùng làm cận dưới: mỗi ký tự thiếu ở bên kia cần >= 1 phép sửa. */
    fun signature(norm: String): Long {
        var m = 0L
        for (c in norm) m = m or (1L shl (c.code and 63))
        return m
    }

    /** Dải độ dài (đã chuẩn hoá) mà ứng viên có thể trùng với chuỗi độ dài len. */
    fun lengthRange(len: Int): IntRange {
        val lo = (len - maxDiff(len)).coerceAtLeast(1)
        var hi = (len / THRESHOLD).toInt() + 2
        while (hi > len && hi - len > maxDiff(hi)) hi--
        return lo..hi
    }

    /**
     * Không thread-safe: mỗi luồng tạo một Matcher riêng.
     * prepare(query) một lần, rồi gọi isSimilar() với nhiều ứng viên.
     */
    class Matcher {
        private val idx = IntArray(65536)      // ký tự -> hàng trong bảng Peq (0 = không có)
        private var used = CharArray(0)
        private var table = LongArray(0)
        private var pv = LongArray(0)
        private var mv = LongArray(0)
        private var blocks = 0
        private var m = 0
        private var highMask = 0L
        private var pattern = ""
        private var patternSig = 0L

        fun prepare(query: String) {
            for (c in used) idx[c.code] = 0
            pattern = query
            m = query.length
            patternSig = signature(query)
            blocks = (m + 63) ushr 6
            if (m == 0) { used = CharArray(0); return }

            val usedBuf = CharArray(m)
            var distinct = 0
            for (i in 0 until m) {
                val c = query[i]
                if (idx[c.code] == 0) { distinct++; idx[c.code] = distinct; usedBuf[distinct - 1] = c }
            }
            used = usedBuf.copyOf(distinct)
            table = LongArray((distinct + 1) * blocks)
            for (i in 0 until m) {
                table[idx[query[i].code] * blocks + (i ushr 6)] =
                    table[idx[query[i].code] * blocks + (i ushr 6)] or (1L shl (i and 63))
            }
            highMask = 1L shl ((m - 1) and 63)
            if (pv.size < blocks) { pv = LongArray(blocks); mv = LongArray(blocks) }
        }

        /** cand/candSig đã chuẩn hoá (lowercase+trim). */
        fun isSimilar(cand: String, candSig: Long): Boolean {
            val n = cand.length
            if (m == 0 || n == 0) return false
            if (pattern == cand) return true
            val maxLen = if (m > n) m else n
            val k = maxDiff(maxLen)
            if (kotlin.math.abs(m - n) > k) return false
            if (java.lang.Long.bitCount(patternSig and candSig.inv()) > k) return false
            if (java.lang.Long.bitCount(candSig and patternSig.inv()) > k) return false
            val dist = myers(cand, k)
            if (dist > k) return false
            return (maxLen - dist).toDouble() / maxLen > THRESHOLD
        }

        // Myers/Hyyrö bit-vector, nhiều khối 64-bit, khoảng cách toàn cục. Trả về > k nếu vượt k.
        private fun myers(text: String, k: Int): Int {
            val n = text.length
            val b = blocks
            for (i in 0 until b) { pv[i] = -1L; mv[i] = 0L }
            var score = m
            for (j in 0 until n) {
                val rowBase = idx[text[j].code] * b
                var hin = 1
                for (blk in 0 until b) {
                    var eq = table[rowBase + blk]
                    val pvB = pv[blk]
                    val mvB = mv[blk]
                    val xv = eq or mvB
                    if (hin < 0) eq = eq or 1L
                    val xh = (((eq and pvB) + pvB) xor pvB) or eq
                    var ph = mvB or (xh or pvB).inv()
                    var mh = pvB and xh
                    val hMask = if (blk == b - 1) highMask else Long.MIN_VALUE
                    var hout = 0
                    if ((ph and hMask) != 0L) hout = 1 else if ((mh and hMask) != 0L) hout = -1
                    ph = ph shl 1
                    mh = mh shl 1
                    if (hin < 0) mh = mh or 1L else if (hin > 0) ph = ph or 1L
                    pv[blk] = mh or (xv or ph).inv()
                    mv[blk] = ph and xv
                    hin = hout
                }
                score += hin
                if (score - (n - 1 - j) > k) return k + 1
            }
            return score
        }
    }

    /** Chỉ mục trong bộ nhớ (chỉ đọc sau khi dựng xong -> an toàn cho nhiều luồng). */
    class Index {
        class Entry(val id: Int, val norm: String, val sig: Long)
        private val buckets = HashMap<Int, ArrayList<Entry>>()
        var size = 0; private set

        fun add(norm: String, sig: Long = signature(norm)) {
            buckets.getOrPut(norm.length) { ArrayList() }.add(Entry(size++, norm, sig))
        }

        /** Có phần tử nào (id < beforeId) tương tự không? Matcher đã prepare(query). */
        fun hasSimilar(matcher: Matcher, query: String, beforeId: Int = Int.MAX_VALUE): Boolean {
            for (len in lengthRange(query.length)) {
                val list = buckets[len] ?: continue
                for (e in list) {
                    if (e.id >= beforeId) break
                    if (matcher.isSimilar(e.norm, e.sig)) return true
                }
            }
            return false
        }
    }
}
