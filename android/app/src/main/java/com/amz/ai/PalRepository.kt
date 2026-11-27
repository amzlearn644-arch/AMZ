package com.amz.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Pal(val id: String, val name: String, val description: String, val systemPrompt: String, val emoji: String = "✨")

class PalRepository(context: Context) {
    private val prefs = context.getSharedPreferences("amz_ai_pals", Context.MODE_PRIVATE)
    fun load(): List<Pal> {
        val raw = prefs.getString("pals", null)
        if (raw == null) return defaultPals().also { save(it) }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(Pal(item.optString("id"), item.optString("name"), item.optString("description"), item.optString("systemPrompt"), item.optString("emoji", "✨")))
                }
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.systemPrompt.isNotBlank() }
        }.getOrDefault(defaultPals())
    }
    fun save(pals: List<Pal>) {
        val array = JSONArray()
        pals.forEach { p -> array.put(JSONObject().put("id", p.id).put("name", p.name).put("description", p.description).put("systemPrompt", p.systemPrompt).put("emoji", p.emoji)) }
        prefs.edit().putString("pals", array.toString()).apply()
    }
    fun activeId(): String? = prefs.getString("active_pal_id", null)
    fun setActive(id: String?) { prefs.edit().putString("active_pal_id", id).apply() }
    private fun defaultPals() = listOf(
        Pal("default-tutor", "Study Tutor", "Explains difficult topics step by step.", "You are a patient study tutor. Explain concepts clearly, use small steps and examples, check understanding, and adapt to the learner's level.", "🎓"),
        Pal("default-coder", "Code Expert", "A practical programming assistant.", "You are a senior software engineer and coding assistant. Give correct, maintainable solutions, explain important trade-offs, and provide complete working code when asked.", "💻"),
        Pal("default-writer", "Creative Writer", "Helps with writing and ideas.", "You are a thoughtful writing partner. Help brainstorm, structure, edit, and improve writing while preserving the user's intended voice.", "✍️"),
        Pal("default-general", "General Assistant", "A helpful everyday assistant.", "You are AMZ AI, a helpful, accurate and friendly assistant. Answer directly, state uncertainty honestly, and organize complex answers clearly.", "✨")
    )
}