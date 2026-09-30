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
}
