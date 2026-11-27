#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstring>
#include "llama.h"

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static llama_sampler * g_sampler = nullptr;

static void release_locked() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF("llama.cpp / arm64 CPU");
}
extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeLoad(JNIEnv * env, jobject, jstring path_, jint contextSize) {
    const char * path = env->GetStringUTFChars(path_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
    llama_backend_init();
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(path_, path);
    if (!g_model) return env->NewStringUTF("ERROR: Model load failed. Check GGUF file and device RAM.");
    auto cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(std::clamp(contextSize, 512, 8192));
    cp.n_batch = std::min<uint32_t>(cp.n_ctx, 2048);
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { release_locked(); return env->NewStringUTF("ERROR: Context allocation failed. Try a smaller context."); }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    return env->NewStringUTF("OK");
}
extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeGenerate(JNIEnv * env, jobject, jstring prompt_, jint maxTokens, jobject callback) {
    const char * prompt = env->GetStringUTFChars(prompt_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx || !g_sampler) { env->ReleaseStringUTFChars(prompt_, prompt); return env->NewStringUTF("ERROR: No model is loaded."); }
    llama_memory_clear(llama_get_memory(g_ctx), true);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t needed = -llama_tokenize(vocab, prompt, (int32_t)strlen(prompt), nullptr, 0, true, false);
    if (needed <= 0 || needed > 32768) { env->ReleaseStringUTFChars(prompt_, prompt); return env->NewStringUTF("ERROR: Prompt is empty or too large."); }
    std::vector<llama_token> tokens((size_t)needed);
    const int32_t count = llama_tokenize(vocab, prompt, (int32_t)strlen(prompt), tokens.data(), needed, true, false);
    env->ReleaseStringUTFChars(prompt_, prompt);
    if (count < 0) return env->NewStringUTF("ERROR: Prompt tokenization failed.");
    tokens.resize((size_t)count);
    auto batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("ERROR: Prompt evaluation failed; reduce context.");
    std::string output; std::vector<char> piece(16 * 1024);
    for (int i = 0; i < std::clamp((int)maxTokens, 1, 2048); ++i) {
        const llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        const int32_t n = llama_token_to_piece(vocab, token, piece.data(), (int32_t)piece.size(), 0, true);
        if (n > 0) {
            output.append(piece.data(), (size_t)n);
            if (callback) {
                jclass cls = env->GetObjectClass(callback);
                jmethodID method = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
                if (method) {
                    std::string part(piece.data(), (size_t)n);
                    jstring chunk = env->NewStringUTF(part.c_str());
                    env->CallVoidMethod(callback, method, chunk);
                    env->DeleteLocalRef(chunk);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
                env->DeleteLocalRef(cls);
            }
        }
        llama_token nextToken = token; auto nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(g_ctx, nextBatch) != 0) break;
    }
    return env->NewStringUTF(output.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_amz_ai_NativeLlama_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex); release_locked();
}