# AMZ AI for Android

## 0.4.0 — Streaming and generation metrics
- Kotlin + Jetpack Compose application with a dark, rounded chat interface.
- GGUF model discovery on Hugging Face, download, local import and installed-model listing.
- llama.cpp native runtime connected through JNI for arm64-v8a CPU inference.
- Token streaming uses UTF-8-aware buffering and converts complete code points to Java UTF-16.
- Native generation reports the actual number of sampled output tokens; the UI can calculate tokens/second and elapsed time.
- Fast/Think are still preliminary modes and do not yet configure distinct sampler profiles or guarantee reasoning behavior.
- GPU acceleration, model-specific chat templates, persistent chat history, Pals, voice and remote providers are not complete yet.

Initialize the llama.cpp submodule:
`git submodule update --init --recursive`

Build with JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1.
Start with a small quantized GGUF and context 2048. Validate on a physical arm64 device; large models may exceed RAM. Not production-ready yet.
