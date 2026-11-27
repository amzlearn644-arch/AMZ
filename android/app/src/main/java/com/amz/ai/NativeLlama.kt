package com.amz.ai

object NativeLlama {
    init { System.loadLibrary("amz-ai-native") }
    external fun nativeVersion(): String
    external fun nativeLoad(path: String, contextSize: Int): String
    external fun nativeGenerate(prompt: String, maxTokens: Int, callback: TokenCallback?): String
    external fun nativeUnload()
}
interface TokenCallback { fun onToken(token: String) }
