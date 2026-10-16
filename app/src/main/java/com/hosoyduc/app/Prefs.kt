package com.hosoyduc.app

import android.content.Context
import org.json.JSONArray

data class AuthData(
    val idToken: String,
    val refreshToken: String,
    val uid: String,
    val email: String,
    val displayName: String,
    val expiresAt: Long
)

object Prefs {
    private const val FILE = "yduc_prefs"
    private const val KEY_ID_TOKEN = "idToken"
    private const val KEY_REFRESH_TOKEN = "refreshToken"
    private const val KEY_UID = "uid"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_EXPIRES_AT = "expiresAt"
    private const val KEY_TIMEZONES = "timezones"
    private const val KEY_SYNC_UID = "syncUid"
    private const val KEY_SYNC_CURSOR = "syncCursor"
    private const val KEY_LAST_FULL = "lastFullSync"
    private const val KEY_LAST_SYNC = "lastSync"
    private const val KEY_INDEX_MISSING = "indexMissing"

    val DEFAULT_TIMEZONES = listOf("VN", "KR", "CN", "JP", "SG", "TH")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveAuth(ctx: Context, auth: AuthData) {
        prefs(ctx).edit()
            .putString(KEY_ID_TOKEN, auth.idToken)
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            .putString(KEY_UID, auth.uid)
            .putString(KEY_EMAIL, auth.email)
            .putString(KEY_DISPLAY_NAME, auth.displayName)
            .putLong(KEY_EXPIRES_AT, auth.expiresAt)
            .apply()
    }

    fun getAuth(ctx: Context): AuthData? {
        val p = prefs(ctx)
        val idToken = p.getString(KEY_ID_TOKEN, null) ?: return null
        return AuthData(
            idToken = idToken,
            refreshToken = p.getString(KEY_REFRESH_TOKEN, "") ?: "",
            uid = p.getString(KEY_UID, "") ?: "",
            email = p.getString(KEY_EMAIL, "") ?: "",
            displayName = p.getString(KEY_DISPLAY_NAME, "") ?: "",
            expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        )
    }

    fun clearAuth(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ID_TOKEN).remove(KEY_REFRESH_TOKEN).remove(KEY_UID)
            .remove(KEY_EMAIL).remove(KEY_DISPLAY_NAME).remove(KEY_EXPIRES_AT)
            .apply()
    }

    fun getSelectedTimezones(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_TIMEZONES, null) ?: return DEFAULT_TIMEZONES
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            DEFAULT_TIMEZONES
        }
    }

    fun setSelectedTimezones(ctx: Context, codes: List<String>) {
        val arr = JSONArray(codes)
        prefs(ctx).edit().putString(KEY_TIMEZONES, arr.toString()).apply()
    }

    // ---------- Trạng thái đồng bộ với bộ nhớ đệm cục bộ ----------

    fun getSyncUid(ctx: Context): String? = prefs(ctx).getString(KEY_SYNC_UID, null)
    fun getSyncCursor(ctx: Context): Long = prefs(ctx).getLong(KEY_SYNC_CURSOR, 0L)
    fun setSyncCursor(ctx: Context, v: Long) = prefs(ctx).edit().putLong(KEY_SYNC_CURSOR, v).apply()
    fun getLastFullSyncAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_FULL, 0L)
    fun setLastFullSyncAt(ctx: Context, v: Long) = prefs(ctx).edit().putLong(KEY_LAST_FULL, v).apply()
    fun getLastSyncAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_SYNC, 0L)
    fun setLastSyncAt(ctx: Context, v: Long) = prefs(ctx).edit().putLong(KEY_LAST_SYNC, v).apply()
    fun isIndexMissing(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_INDEX_MISSING, false)
    fun setIndexMissing(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_INDEX_MISSING, v).apply()

    /** Xoá mốc đồng bộ và gán cho tài khoản mới (uid = null khi đăng xuất). */
    fun resetSync(ctx: Context, uid: String?) {
        val e = prefs(ctx).edit()
        e.remove(KEY_SYNC_CURSOR).remove(KEY_LAST_FULL).remove(KEY_LAST_SYNC).remove(KEY_INDEX_MISSING)
        if (uid == null) e.remove(KEY_SYNC_UID) else e.putString(KEY_SYNC_UID, uid)
        e.apply()
    }
}
