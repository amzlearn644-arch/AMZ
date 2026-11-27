package com.amz.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InferenceRepository {
    suspend fun load(path: String, contextSize: Int = 2048): String =
        withContext(Dispatchers.IO) { NativeLlama.nativeLoad(path, contextSize) }

    suspend fun formatPrompt(messages: List<ChatMessage>, mode: String): String =
        withContext(Dispatchers.IO) {
            val system = if (mode == "Think") {
                "You are AMZ AI. Think carefully before answering. Give a clear, useful final answer. Do not reveal hidden chain-of-thought; provide concise reasoning summaries when useful."
            } else {
                "You are AMZ AI. Answer helpfully, accurately, and directly. Prefer concise answers unless detail is needed."
            }
            val recent = messages.filter { it.text.isNotBlank() }.takeLast(12)
            val roles = ArrayList<String>(recent.size + 1)
            val contents = ArrayList<String>(recent.size + 1)
            roles.add("system"); contents.add(system)
            recent.forEach {
                roles.add(if (it.user) "user" else "assistant")
                contents.add(it.text)
            }
            val formatted = NativeLlama.nativeFormatChat(roles.toTypedArray(), contents.toTypedArray())
            if (formatted.startsWith("ERROR:")) ConversationPromptBuilder.build(messages, mode) else formatted
        }

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 256,
        onToken: (String, Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        NativeLlama.nativeGenerate(
            prompt,
            maxTokens,
            object : TokenCallback {
                override fun onToken(token: String, generatedTokenCount: Int) {
                    onToken.invoke(token, generatedTokenCount)
                }
            }
        )
    }

    suspend fun unload() = withContext(Dispatchers.IO) { NativeLlama.nativeUnload() }
}
