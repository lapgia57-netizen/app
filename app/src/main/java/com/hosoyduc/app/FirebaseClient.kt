package com.hosoyduc.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

fun computeTimes(codes: List<String>, millis: Long = System.currentTimeMillis()): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val date = java.util.Date(millis)
    for (code in codes) {
        val zone = SUPPORTED_TIMEZONES.find { it.code == code } ?: continue
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", zone.locale)
        fmt.timeZone = java.util.TimeZone.getTimeZone(zone.zoneId)
        result[code] = fmt.format(date)
    }
    return result
}

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val message: String, val indexMissing: Boolean = false) : ApiResult<Nothing>()
}

object FirebaseClient {

    private class HttpResp(val code: Int, val body: String)

    private fun request(method: String, urlStr: String, body: String? = null, contentType: String = "application/json"): HttpResp {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    // Mọi I/O và parse JSON chạy trên IO; lỗi mạng trả về mã -1 thay vì làm app crash
    private suspend fun call(method: String, url: String, body: String? = null, contentType: String = "application/json"): HttpResp =
        withContext(Dispatchers.IO) {
            try { request(method, url, body, contentType) }
            catch (e: IOException) { HttpResp(-1, e.message ?: "network") }
        }

    private fun parseObj(text: String): JSONObject? = try { JSONObject(text) } catch (e: Exception) { null }
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun errorMessage(r: HttpResp, fallback: String): String {
        if (r.code == -1) return NETWORK_ERROR
        val o = parseObj(r.body) ?: return fallback
        val e = o.opt("error")
        return when (e) {
            is JSONObject -> e.optString("message", fallback)
            is String -> e
            else -> fallback
        }
    }

    // ---------- Xác thực ----------

    suspend fun signIn(email: String, password: String): ApiResult<AuthData> {
        val body = JSONObject().apply {
            put("email", email); put("password", password); put("returnSecureToken", true)
        }.toString()
        val r = call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${FirebaseConfig.API_KEY}", body)
        val res = parseObj(r.body)
        if (res == null || res.has("error")) return ApiResult.Failure(errorMessage(r, "Đăng nhập thất bại"))
        return ApiResult.Success(parseAuth(res))
    }

    private fun parseAuth(res: JSONObject): AuthData {
        val expiresIn = res.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
        return AuthData(
            idToken = res.getString("idToken"),
            refreshToken = res.getString("refreshToken"),
            uid = res.getString("localId"),
            email = res.optString("email", ""),
            displayName = res.optString("displayName", res.optString("email", "").substringBefore("@")),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000
        )
    }

    private const val NETWORK_ERROR = "Lỗi kết nối mạng"

    private suspend fun refreshToken(refreshToken: String): ApiResult<AuthData> {
        val body = "grant_type=refresh_token&refresh_token=${enc(refreshToken)}"
        val r = call("POST", "https://securetoken.googleapis.com/v1/token?key=${FirebaseConfig.API_KEY}", body, "application/x-www-form-urlencoded")
        if (r.code == -1) return ApiResult.Failure(NETWORK_ERROR)
        val res = parseObj(r.body)
        if (res == null || res.has("error")) return ApiResult.Failure("Phiên đăng nhập hết hạn")
        val expiresIn = res.optString("expires_in", "3600").toLongOrNull() ?: 3600L
        return ApiResult.Success(
            AuthData(
                idToken = res.getString("id_token"),
                refreshToken = res.getString("refresh_token"),
                uid = res.getString("user_id"),
                email = "", displayName = "",
                expiresAt = System.currentTimeMillis() + expiresIn * 1000
            )
        )
    }

    // Trả về AuthData còn hiệu lực (tự làm mới token nếu sắp hết hạn), hoặc null nếu chưa đăng nhập.
    // Mất mạng KHÔNG xoá phiên đăng nhập (bản cũ bị đăng xuất oan khi offline).
    suspend fun getValidAuth(ctx: Context): AuthData? {
        val stored = Prefs.getAuth(ctx) ?: return null
        if (System.currentTimeMillis() < stored.expiresAt - 60_000) return stored

        return when (val r = refreshToken(stored.refreshToken)) {
            is ApiResult.Success -> {
                val merged = r.data.copy(email = stored.email, displayName = stored.displayName)
                Prefs.saveAuth(ctx, merged)
                merged
            }
            is ApiResult.Failure -> { if (r.message != NETWORK_ERROR) Prefs.clearAuth(ctx); null }
        }
    }

    // ---------- Realtime Database ----------

    private fun base(auth: AuthData) = "${FirebaseConfig.DATABASE_URL}/users/${auth.uid}/data"
    private fun authQ(auth: AuthData) = "auth=${enc(auth.idToken)}"
    private fun q(s: String) = enc("\"$s\"")

    private suspend fun parseRecords(r: HttpResp): ApiResult<List<Record>> {
        if (r.code !in 200..299) {
            val msg = errorMessage(r, "Lỗi đọc dữ liệu")
            return ApiResult.Failure(msg, indexMissing = msg.contains("Index not defined", ignoreCase = true))
        }
        return withContext(Dispatchers.IO) {
            if (r.body.isBlank() || r.body == "null") return@withContext ApiResult.Success(emptyList<Record>())
            try {
                val o = JSONObject(r.body)
                val out = ArrayList<Record>(o.length())
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = o.optJSONObject(k) ?: continue
                    out.add(Record.fromJson(k, v))
                }
                out.sortWith(compareBy<Record> { it.savedAt }.thenBy { it.key })
                ApiResult.Success(out)
            } catch (e: Exception) {
                ApiResult.Failure("Lỗi đọc dữ liệu")
            }
        }
    }

    /**
     * Đọc một đoạn bản ghi theo khoá (orderBy="$key" là chỉ mục có sẵn, không cần cấu hình rules).
     * startAt/endAt đều bao gồm biên. Dùng để tải song song theo "phân vùng" và phân trang.
     */
    suspend fun fetchKeyRange(auth: AuthData, startAt: String?, endAt: String?, limit: Int): ApiResult<List<Record>> {
        val sb = StringBuilder("${base(auth)}.json?${authQ(auth)}&orderBy=${enc("\"\$key\"")}&limitToFirst=$limit")
        if (startAt != null) sb.append("&startAt=").append(q(startAt))
        if (endAt != null) sb.append("&endAt=").append(q(endAt))
        return parseRecords(call("GET", sb.toString()))
    }

    /**
     * Chỉ lấy bản ghi mới (savedAt >= from). Cần ".indexOn": ["savedAt"] trong rules;
     * nếu chưa có, trả Failure(indexMissing=true) để SyncManager tự chuyển sang cách đối chiếu khoá.
     */
    suspend fun fetchSince(auth: AuthData, from: Long, limit: Int): ApiResult<List<Record>> {
        val url = "${base(auth)}.json?${authQ(auth)}&orderBy=${enc("\"savedAt\"")}&startAt=$from&limitToFirst=$limit"
        return parseRecords(call("GET", url))
    }

    /** Chỉ danh sách khoá (xấp xỉ 1,75 MB cho 50k bản ghi, tùy kích thước khóa). */
    suspend fun shallowKeys(auth: AuthData): ApiResult<Set<String>> {
        val r = call("GET", "${base(auth)}.json?${authQ(auth)}&shallow=true")
        if (r.code !in 200..299) return ApiResult.Failure(errorMessage(r, "Lỗi đọc dữ liệu"))
        return withContext(Dispatchers.IO) {
            if (r.body.isBlank() || r.body == "null") return@withContext ApiResult.Success(emptySet<String>())
            try {
                val o = JSONObject(r.body)
                val s = HashSet<String>(o.length() * 2)
                o.keys().forEach { s.add(it) }
                ApiResult.Success(s as Set<String>)
            } catch (e: Exception) { ApiResult.Failure("Lỗi đọc dữ liệu") }
        }
    }

    suspend fun fetchOne(auth: AuthData, key: String): ApiResult<Record?> {
        val r = call("GET", "${base(auth)}/${enc(key)}.json?${authQ(auth)}")
        if (r.code !in 200..299) return ApiResult.Failure(errorMessage(r, "Lỗi đọc dữ liệu"))
        if (r.body.isBlank() || r.body == "null") return ApiResult.Success(null)
        val o = parseObj(r.body) ?: return ApiResult.Failure("Lỗi đọc dữ liệu")
        return ApiResult.Success(Record.fromJson(key, o))
    }

    suspend fun writeRecord(auth: AuthData, key: String, payload: JSONObject): ApiResult<Unit> {
        val r = call("PUT", "${base(auth)}/${enc(key)}.json?${authQ(auth)}", payload.toString())
        return if (r.code in 200..299) ApiResult.Success(Unit) else ApiResult.Failure(errorMessage(r, "Lỗi ghi dữ liệu"))
    }

    /** Ghi nhiều bản ghi trong 1 request (PATCH qua method-override) -> khôi phục 50k bản khoảng 167 request. */
    suspend fun writeRecordsBatch(auth: AuthData, records: List<Record>): ApiResult<Unit> {
        if (records.isEmpty()) return ApiResult.Success(Unit)
        val body = withContext(Dispatchers.Default) {
            val o = JSONObject()
            for (rec in records) o.put(rec.key, rec.toJson())
            o.toString()
        }
        val r = call("POST", "${base(auth)}.json?${authQ(auth)}&x-http-method-override=PATCH", body)
        return if (r.code in 200..299) ApiResult.Success(Unit) else ApiResult.Failure(errorMessage(r, "Lỗi ghi dữ liệu"))
    }

    suspend fun deleteRecord(auth: AuthData, key: String): Boolean {
        val r = call("DELETE", "${base(auth)}/${enc(key)}.json?${authQ(auth)}")
        return r.code in 200..299
    }
}
