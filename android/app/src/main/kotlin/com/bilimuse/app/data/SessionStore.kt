package com.bilimuse.app.data

import android.content.Context
import kotlinx.serialization.json.Json

/** 登录态只存在应用私有目录（等价于 Python 版的 0600 文件），不备份。 */
class SessionStore(context: Context) : SessionSink {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): SessionData {
        val raw = prefs.getString(KEY, null) ?: return SessionData()
        return runCatching { json.decodeFromString<SessionData>(raw) }.getOrDefault(SessionData())
    }

    override fun save(data: SessionData) {
        val stored = data.copy(updatedAt = System.currentTimeMillis() / 1000)
        prefs.edit().putString(KEY, json.encodeToString(SessionData.serializer(), stored)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "bilimuse_session"
        const val KEY = "session"
    }
}
