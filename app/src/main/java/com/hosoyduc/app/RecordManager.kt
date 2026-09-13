package com.hosoyduc.app

import android.content.Context
import android.os.Build
import org.json.JSONObject

sealed class SaveOutcome {
    object Success : SaveOutcome()
    object NotLoggedIn : SaveOutcome()
    object Duplicate : SaveOutcome()
    data class Error(val message: String) : SaveOutcome()
}

object RecordManager {

    // Chỉ được gọi khi người dùng CHỦ ĐỘNG yêu cầu lưu (chọn app trong menu bôi đen,
    // hoặc bấm nút "Lưu" trong app). Không có nơi nào khác gọi hàm này tự động.
    suspend fun saveText(ctx: Context, text: String, source: String): SaveOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SaveOutcome.Error("Nội dung trống")

        val auth = FirebaseClient.getValidAuth(ctx) ?: return SaveOutcome.NotLoggedIn

        val existing = when (val res = FirebaseClient.readAllRecords(auth)) {
            is ApiResult.Success -> res.data
            is ApiResult.Failure -> return SaveOutcome.Error(res.message)
        }
        val keys = existing.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val original = existing.getJSONObject(k).optString("original", "")
            if (original.isNotEmpty() && CryptoUtils.similarity(trimmed, original) > 0.68) {
                return SaveOutcome.Duplicate
            }
        }

        val timezones = Prefs.getSelectedTimezones(ctx)
        val times = computeTimes(timezones)
        val timesJson = JSONObject()
        times.forEach { (k, v) -> timesJson.put(k, v) }

        val md5Key = CryptoUtils.md5(trimmed)
        val payload = JSONObject().apply {
            put("sha", CryptoUtils.sha256(trimmed))
            put("sha512", CryptoUtils.sha512(trimmed))
            put("original", trimmed)
            put("hashedKey", md5Key)
            put("source", source)
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE ?: "")
            put("times", timesJson)
            put("savedAt", System.currentTimeMillis())
        }

        return when (val res = FirebaseClient.writeRecord(auth, md5Key, payload)) {
            is ApiResult.Success -> SaveOutcome.Success
            is ApiResult.Failure -> SaveOutcome.Error(res.message)
        }
    }
}
