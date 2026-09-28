package com.baystudio.droide.core

import android.content.Context

internal data class GitHubAccountRecord(
    val login: String,
    val userId: Long,
    val avatarUrl: String?,
    val connectedAtEpochSeconds: Long,
    val tokenExpiresAtEpochSeconds: Long?,
)

 
internal class GitHubAccountStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): GitHubAccountRecord? {
        val login = prefs.getString(KEY_LOGIN, null)?.takeIf { LOGIN.matches(it) } ?: return null
        val userId = prefs.getLong(KEY_USER_ID, 0L).takeIf { it > 0L } ?: return null
        val connected = prefs.getLong(KEY_CONNECTED_AT, 0L).takeIf { it > 0L } ?: return null
        val avatar = prefs.getString(KEY_AVATAR_URL, null)?.takeIf { it.length <= 2_048 && it.startsWith("https://") }
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L).takeIf { it > 0L }
        return GitHubAccountRecord(login, userId, avatar, connected, expiry)
    }

    fun put(record: GitHubAccountRecord): Boolean {
        require(LOGIN.matches(record.login)) { "Invalid GitHub login" }
        require(record.userId > 0L && record.connectedAtEpochSeconds > 0L)
        record.avatarUrl?.let { require(it.length <= 2_048 && it.startsWith("https://")) { "Invalid GitHub avatar URL" } }
        record.tokenExpiresAtEpochSeconds?.let { require(it > 0L) }
        val editor = prefs.edit()
            .putString(KEY_LOGIN, record.login)
            .putLong(KEY_USER_ID, record.userId)
            .putLong(KEY_CONNECTED_AT, record.connectedAtEpochSeconds)
        if (record.avatarUrl == null) editor.remove(KEY_AVATAR_URL) else editor.putString(KEY_AVATAR_URL, record.avatarUrl)
        if (record.tokenExpiresAtEpochSeconds == null) editor.remove(KEY_TOKEN_EXPIRES_AT) else editor.putLong(KEY_TOKEN_EXPIRES_AT, record.tokenExpiresAtEpochSeconds)
        return editor.commit()
    }

    fun remove(): Boolean = prefs.edit().clear().commit()

    companion object {
        private const val PREFS = "droide_github_account_state_v1"
        private const val KEY_LOGIN = "login"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_AVATAR_URL = "avatar_url"
        private const val KEY_CONNECTED_AT = "connected_at"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    }
}
