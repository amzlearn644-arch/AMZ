package com.amz.ai
import android.content.Context

data class AppSettings(
    val contextSize: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("amz_ai_settings", Context.MODE_PRIVATE)
    fun load() = AppSettings(
        prefs.getInt("context_size", 2048).coerceIn(512, 8192),
        prefs.getFloat("temperature", 0.7f).coerceIn(0f, 2f),
        prefs.getFloat("top_p", 0.9f).coerceIn(0.05f, 1f),
        prefs.getInt("top_k", 40).coerceIn(0, 100),
        prefs.getFloat("repeat_penalty", 1.1f).coerceIn(1f, 1.5f)
    )
    fun save(s: AppSettings) {
        prefs.edit()
            .putInt("context_size", s.contextSize.coerceIn(512, 8192))
            .putFloat("temperature", s.temperature.coerceIn(0f, 2f))
            .putFloat("top_p", s.topP.coerceIn(0.05f, 1f))
            .putInt("top_k", s.topK.coerceIn(0, 100))
            .putFloat("repeat_penalty", s.repeatPenalty.coerceIn(1f, 1.5f))
            .apply()
    }
}