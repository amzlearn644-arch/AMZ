package com.amz.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InferenceRepository {
    suspend fun load(path: String, contextSize: Int = 2048): String =
        withContext(Dispatchers.IO) { NativeLlama.nativeLoad(path, contextSize) }

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
