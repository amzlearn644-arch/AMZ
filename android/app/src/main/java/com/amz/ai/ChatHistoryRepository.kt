package com.amz.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(val text: String, val user: Boolean)

data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>
) {
    companion object {
        fun create(): Conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "گفتگوی جدید",
            updatedAt = System.currentTimeMillis(),
            messages = listOf(ChatMessage("سلام! برای اجرای آفلاین، ابتدا یک مدل GGUF وارد یا دانلود کن.", false))
        )
    }
}

class ChatHistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("amz_ai_chat", Context.MODE_PRIVATE)

    fun loadConversations(): List<Conversation> {
        val raw = prefs.getString("conversations", null)
        if (!raw.isNullOrBlank()) {
            val restored = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val messagesArray = item.optJSONArray("messages") ?: JSONArray()
                        val messages = buildList {
                            for (j in 0 until messagesArray.length()) {
                                val message = messagesArray.getJSONObject(j)
                                add(ChatMessage(message.optString("text"), message.optBoolean("user")))
                            }
                        }
                        val id = item.optString("id")
                        if (id.isNotBlank()) add(
                            Conversation(
                                id = id,
                                title = item.optString("title", "گفتگوی جدید").ifBlank { "گفتگوی جدید" },
                                updatedAt = item.optLong("updatedAt", 0L),
                                messages = messages
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
            if (restored.isNotEmpty()) return restored.sortedByDescending { it.updatedAt }
        }

        // One-time migration: keep the previous single-chat history intact.
        val legacyMessages = load()
        val migrated = Conversation(
            id = UUID.randomUUID().toString(),
            title = legacyMessages.firstOrNull { it.user }?.text?.take(32)?.ifBlank { "گفتگوی قبلی" } ?: "گفتگوی قبلی",
            updatedAt = System.currentTimeMillis(),
            messages = if (legacyMessages.isEmpty()) Conversation.create().messages else legacyMessages
        )
        saveConversations(listOf(migrated))
        return listOf(migrated)
    }

    fun saveConversations(conversations: List<Conversation>) {
        val array = JSONArray()
        conversations.sortedByDescending { it.updatedAt }.take(50).forEach { conversation ->
            val messagesArray = JSONArray()
            conversation.messages.takeLast(200).forEach { message ->
                messagesArray.put(JSONObject().put("text", message.text).put("user", message.user))
            }
            array.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("updatedAt", conversation.updatedAt)
                    .put("messages", messagesArray)
            )
        }
        prefs.edit().putString("conversations", array.toString()).apply()
    }

    // Legacy single-chat methods are retained for migration compatibility.
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
