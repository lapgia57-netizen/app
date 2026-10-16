package com.hosoyduc.app

import org.json.JSONObject

/** Bản ghi đầy đủ (đồng bộ với JSON trên Firebase). */
data class Record(
    val key: String,                 // = md5(nội dung) -> khoá chống trùng, không phụ thuộc thời gian
    val original: String,
    val sha: String,                 // sha256(nội dung)
    val sha512: String,              // sha512(nội dung)
    val source: String,
    val device: String,
    val androidVersion: String,
    val times: Map<String, String>,  // giờ theo từng múi giờ đã chọn
    val savedAt: Long,
    val timeSha256: String,          // hash riêng của thời gian (savedAt + các múi giờ)
    val sealSha256: String,          // hash (nội dung + thời gian)
    val sealSha512: String,
    val extra: String? = null        // các trường lạ từ client khác -> giữ nguyên để không mất dữ liệu
) {
    fun toJson(originalOverride: String? = null): JSONObject {
        val o = JSONObject()
        extra?.let { e -> JSONObject(e).let { x -> x.keys().forEach { k -> o.put(k, x.get(k)) } } }
        o.put("sha", sha)
        o.put("sha512", sha512)
        o.put("original", originalOverride ?: original)
        o.put("hashedKey", key)
        o.put("source", source)
        o.put("deviceModel", device)
        o.put("androidVersion", androidVersion)
        o.put("times", JSONObject(times))
        o.put("savedAt", savedAt)
        if (sealSha256.isNotEmpty()) {
            o.put("timeSha256", timeSha256)
            o.put("sealSha256", sealSha256)
            o.put("sealSha512", sealSha512)
        }
        return o
    }

    companion object {
        private val KNOWN = setOf(
            "sha", "sha512", "original", "hashedKey", "source", "deviceModel", "androidVersion",
            "times", "savedAt", "timeSha256", "sealSha256", "sealSha512"
        )

        fun fromJson(key: String, o: JSONObject): Record {
            val t = o.optJSONObject("times")
            val times = LinkedHashMap<String, String>()
            if (t != null) t.keys().forEach { k -> times[k] = t.optString(k, "") }

            var extra: JSONObject? = null
            o.keys().forEach { k ->
                if (k !in KNOWN) { if (extra == null) extra = JSONObject(); extra!!.put(k, o.get(k)) }
            }
            return Record(
                key = key,
                original = o.optString("original", ""),
                sha = o.optString("sha", ""),
                sha512 = o.optString("sha512", ""),
                source = o.optString("source", ""),
                device = o.optString("deviceModel", ""),
                androidVersion = o.optString("androidVersion", ""),
                times = times,
                savedAt = o.optLong("savedAt", 0L),
                timeSha256 = o.optString("timeSha256", ""),
                sealSha256 = o.optString("sealSha256", ""),
                sealSha512 = o.optString("sealSha512", ""),
                extra = extra?.toString()
            )
        }

        fun create(text: String, source: String, device: String, androidVersion: String,
                   savedAt: Long, times: Map<String, String>): Record {
            val seal = HashUtils.seal(text, savedAt, times)
            return Record(
                key = HashUtils.md5(text), original = text,
                sha = HashUtils.sha256(text), sha512 = HashUtils.sha512(text),
                source = source, device = device, androidVersion = androidVersion,
                times = times, savedAt = savedAt,
                timeSha256 = seal.timeSha256, sealSha256 = seal.contentTimeSha256, sealSha512 = seal.contentTimeSha512
            )
        }
    }
}

/** Dòng hiển thị trong danh sách (nhẹ hơn Record). seal: true=hợp lệ, false=bị sửa, null=bản cũ chưa có seal. */
data class RecordRow(
    val key: String,
    val original: String,
    val source: String,
    val device: String,
    val timesText: String,
    val savedAt: Long,
    val seal: Boolean?
)
