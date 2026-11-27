package com.amz.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val text: String, val user: Boolean)

class ChatHistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("amz_ai_chat", Context.MODE_PRIVATE)

    fun load(): List<ChatMessage> {
        val raw = prefs.getString("messages", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(ChatMessage(item.optString("text"), item.optBoolean("user")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(200).forEach { message ->
            array.put(JSONObject().put("text", message.text).put("user", message.user))
        }
        prefs.edit().putString("messages", array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("messages").apply()
    }
}

object ConversationPromptBuilder {
    fun build(messages: List<ChatMessage>, mode: String): String {
        val system = if (mode == "Think") {
            "You are AMZ AI. Think carefully before answering. Give a clear, useful final answer. Do not reveal hidden chain-of-thought; provide concise reasoning summaries when useful."
        } else {
            "You are AMZ AI. Answer helpfully, accurately, and directly. Prefer concise answers unless detail is needed."
        }
        val conversation = messages
            .filter { it.text.isNotBlank() }
            .takeLast(12)
            .joinToString("\n\n") { message ->
                (if (message.user) "User: " else "Assistant: ") + message.text
            }
        return buildString {
            append("System: ").append(system).append("\n\n")
            if (conversation.isNotBlank()) append(conversation).append("\n\n")
            append("Assistant:")
        }
    }
}
