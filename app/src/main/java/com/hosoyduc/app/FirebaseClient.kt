package com.hosoyduc.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// Cấu hình lấy từ mã nguồn web gốc của bạn
object FirebaseConfig {
    const val API_KEY = "AIzaSyC7HE94M-w9chHikRLxlAKMeYsR_71GBpc"
    const val DATABASE_URL = "https://fbamthucdouong-default-rtdb.asia-southeast1.firebasedatabase.app"
}

// Danh sách 24 múi giờ hỗ trợ
data class TimezoneOption(val code: String, val label: String, val zoneId: String, val locale: java.util.Locale)

val SUPPORTED_TIMEZONES = listOf(
    // Châu Á
    TimezoneOption("VN", "Việt Nam (Hà Nội) UTC+7", "Asia/Ho_Chi_Minh", java.util.Locale("vi", "VN")),
    TimezoneOption("TH", "Thái Lan (Bangkok) UTC+7", "Asia/Bangkok", java.util.Locale("th", "TH")),
    TimezoneOption("ID", "Indonesia (Jakarta) UTC+7", "Asia/Jakarta", java.util.Locale("id", "ID")),
    TimezoneOption("SG", "Singapore UTC+8", "Asia/Singapore", java.util.Locale("en", "SG")),
    TimezoneOption("MY", "Malaysia (Kuala Lumpur) UTC+8", "Asia/Kuala_Lumpur", java.util.Locale("ms", "MY")),
    TimezoneOption("PH", "Philippines (Manila) UTC+8", "Asia/Manila", java.util.Locale("en", "PH")),
    TimezoneOption("CN", "Trung Quốc (Bắc Kinh) UTC+8", "Asia/Shanghai", java.util.Locale("zh", "CN")),
    TimezoneOption("HK", "Hồng Kông UTC+8", "Asia/Hong_Kong", java.util.Locale("zh", "HK")),
    TimezoneOption("TW", "Đài Loan (Đài Bắc) UTC+8", "Asia/Taipei", java.util.Locale("zh", "TW")),
    TimezoneOption("KR", "Hàn Quốc (Seoul) UTC+9", "Asia/Seoul", java.util.Locale("ko", "KR")),
    TimezoneOption("JP", "Nhật Bản (Tokyo) UTC+9", "Asia/Tokyo", java.util.Locale("ja", "JP")),
    TimezoneOption("IN", "Ấn Độ (Kolkata) UTC+5:30", "Asia/Kolkata", java.util.Locale("en", "IN")),
    TimezoneOption("AE", "UAE (Dubai) UTC+4", "Asia/Dubai", java.util.Locale("ar", "AE")),
    // Châu Âu
    TimezoneOption("UK", "Anh (London) UTC+0/+1", "Europe/London", java.util.Locale("en", "GB")),
    TimezoneOption("FR", "Pháp (Paris) UTC+1/+2", "Europe/Paris", java.util.Locale("fr", "FR")),
    TimezoneOption("DE", "Đức (Berlin) UTC+1/+2", "Europe/Berlin", java.util.Locale("de", "DE")),
    TimezoneOption("IT", "Ý (Rome) UTC+1/+2", "Europe/Rome", java.util.Locale("it", "IT")),
    TimezoneOption("ES", "Tây Ban Nha (Madrid) UTC+1/+2", "Europe/Madrid", java.util.Locale("es", "ES")),
    TimezoneOption("RU", "Nga (Moscow) UTC+3", "Europe/Moscow", java.util.Locale("ru", "RU")),
    // Châu Mỹ & Úc
    TimezoneOption("US_NY", "Mỹ Đông (New York) UTC-5/-4", "America/New_York", java.util.Locale("en", "US")),
    TimezoneOption("US_LA", "Mỹ Tây (Los Angeles) UTC-8/-7", "America/Los_Angeles", java.util.Locale("en", "US")),
    TimezoneOption("US_CHI", "Mỹ Trung (Chicago) UTC-6/-5", "America/Chicago", java.util.Locale("en", "US")),
    TimezoneOption("BR", "Brazil (São Paulo) UTC-3", "America/Sao_Paulo", java.util.Locale("pt", "BR")),
    TimezoneOption("AU", "Úc (Sydney) UTC+10/+11", "Australia/Sydney", java.util.Locale("en", "AU"))
)

fun computeTimes(codes: List<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (code in codes) {
        val zone = SUPPORTED_TIMEZONES.find { it.code == code } ?: continue
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", zone.locale)
        fmt.timeZone = java.util.TimeZone.getTimeZone(zone.zoneId)
        result[code] = fmt.format(java.util.Date())
    }
    return result
}

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val message: String) : ApiResult<Nothing>()
}

object FirebaseClient {

    private suspend fun httpPost(urlStr: String, body: String, formEncoded: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", if (formEncoded) "application/x-www-form-urlencoded" else "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(text)
        }

    private suspend fun httpPut(urlStr: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().readText()
        conn.disconnect()
        text
    }

    private suspend fun httpGet(urlStr: String): String = withContext(Dispatchers.IO) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        text
    }

    private suspend fun httpDelete(urlStr: String) = withContext(Dispatchers.IO) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.inputStream.close()
        conn.disconnect()
    }

    suspend fun signIn(email: String, password: String): ApiResult<AuthData> {
        val body = JSONObject().apply {
            put("email", email); put("password", password); put("returnSecureToken", true)
        }.toString()
        val res = httpPost("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${FirebaseConfig.API_KEY}", body)
        if (res.has("error")) return ApiResult.Failure(res.getJSONObject("error").optString("message", "Đăng nhập thất bại"))
        return ApiResult.Success(parseAuth(res))
    }

    suspend fun signUp(email: String, password: String, displayName: String): ApiResult<AuthData> {
        val body = JSONObject().apply {
            put("email", email); put("password", password); put("returnSecureToken", true)
        }.toString()
        val res = httpPost("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${FirebaseConfig.API_KEY}", body)
        if (res.has("error")) return ApiResult.Failure(res.getJSONObject("error").optString("message", "Đăng ký thất bại"))

        val idToken = res.getString("idToken")
        val updateBody = JSONObject().apply {
            put("idToken", idToken); put("displayName", displayName); put("returnSecureToken", false)
        }.toString()
        httpPost("https://identitytoolkit.googleapis.com/v1/accounts:update?key=${FirebaseConfig.API_KEY}", updateBody)

        return ApiResult.Success(parseAuth(res, displayName))
    }

    private fun parseAuth(res: JSONObject, displayNameOverride: String? = null): AuthData {
        val expiresIn = res.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
        return AuthData(
            idToken = res.getString("idToken"),
            refreshToken = res.getString("refreshToken"),
            uid = res.getString("localId"),
            email = res.optString("email", ""),
            displayName = displayNameOverride ?: res.optString("displayName", res.optString("email", "").substringBefore("@")),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000
        )
    }

    private suspend fun refreshToken(refreshToken: String): AuthData? {
        val body = "grant_type=refresh_token&refresh_token=$refreshToken"
        val res = httpPost("https://securetoken.googleapis.com/v1/token?key=${FirebaseConfig.API_KEY}", body, formEncoded = true)
        if (res.has("error")) return null
        val expiresIn = res.optString("expires_in", "3600").toLongOrNull() ?: 3600L
        return AuthData(
            idToken = res.getString("id_token"),
            refreshToken = res.getString("refresh_token"),
            uid = res.getString("user_id"),
            email = "", displayName = "",
            expiresAt = System.currentTimeMillis() + expiresIn * 1000
        )
    }

    // Trả về AuthData còn hiệu lực (tự làm mới token nếu sắp hết hạn), hoặc null nếu chưa đăng nhập
    suspend fun getValidAuth(ctx: Context): AuthData? {
        val stored = Prefs.getAuth(ctx) ?: return null
        if (System.currentTimeMillis() < stored.expiresAt - 60_000) return stored

        val refreshed = refreshToken(stored.refreshToken) ?: run { Prefs.clearAuth(ctx); return null }
        val merged = refreshed.copy(email = stored.email, displayName = stored.displayName)
        Prefs.saveAuth(ctx, merged)
        return merged
    }

    suspend fun readAllRecords(auth: AuthData): ApiResult<JSONObject> {
        val url = "${FirebaseConfig.DATABASE_URL}/users/${auth.uid}/data.json?auth=${auth.idToken}"
        val text = httpGet(url)
        if (text.isBlank() || text == "null") return ApiResult.Success(JSONObject())
        return try { ApiResult.Success(JSONObject(text)) } catch (e: Exception) { ApiResult.Failure("Lỗi đọc dữ liệu") }
    }

    suspend fun writeRecord(auth: AuthData, key: String, payload: JSONObject): ApiResult<Unit> {
        val url = "${FirebaseConfig.DATABASE_URL}/users/${auth.uid}/data/$key.json?auth=${auth.idToken}"
        val res = httpPut(url, payload.toString())
        return if (res.contains("\"error\"")) ApiResult.Failure("Lỗi ghi dữ liệu") else ApiResult.Success(Unit)
    }

    suspend fun deleteRecord(auth: AuthData, key: String) {
        val url = "${FirebaseConfig.DATABASE_URL}/users/${auth.uid}/data/$key.json?auth=${auth.idToken}"
        httpDelete(url)
    }
}
