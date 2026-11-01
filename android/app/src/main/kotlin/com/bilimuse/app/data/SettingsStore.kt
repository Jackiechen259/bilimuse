package com.bilimuse.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(restore())
    val settings: StateFlow<EngineSettings> = _settings.asStateFlow()

    fun update(transform: (EngineSettings) -> EngineSettings): EngineSettings {
        val next = transform(_settings.value).clamp()
        prefs.edit()
            .putBoolean(KEY_BTR, next.btrEnabled)
            .putInt(KEY_CONCURRENCY, next.concurrency)
            .putInt(KEY_CHUNK, next.chunkSize)
            .putBoolean(KEY_LOSSLESS, next.preferLossless)
            .putBoolean(KEY_SHOW_BTR_DETAILS, next.showBtrDetails)
            .apply()
        _settings.value = next
        return next
    }

    fun setBtrEnabled(enabled: Boolean): EngineSettings = update { it.copy(btrEnabled = enabled) }

    fun setConcurrency(value: Int): EngineSettings = update { it.copy(concurrency = value) }

    fun setChunkSize(value: Int): EngineSettings = update { it.copy(chunkSize = value) }

    fun setPreferLossless(enabled: Boolean): EngineSettings =
        update { it.copy(preferLossless = enabled) }

    fun setShowBtrDetails(enabled: Boolean): EngineSettings =
        update { it.copy(showBtrDetails = enabled) }

    private fun restore(): EngineSettings = EngineSettings(
        btrEnabled = prefs.getBoolean(KEY_BTR, true),
        concurrency = prefs.getInt(KEY_CONCURRENCY, 8),
        chunkSize = prefs.getInt(KEY_CHUNK, 512 * 1024),
        preferLossless = prefs.getBoolean(KEY_LOSSLESS, true),
        showBtrDetails = prefs.getBoolean(KEY_SHOW_BTR_DETAILS, true),
    ).clamp()

    private companion object {
        const val PREFS = "bilimuse_settings"
        const val KEY_BTR = "btr_enabled"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_CHUNK = "chunk_size"
        const val KEY_LOSSLESS = "prefer_lossless"
        const val KEY_SHOW_BTR_DETAILS = "show_btr_details"
    }
}
