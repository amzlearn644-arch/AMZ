# AMZ AI for Android

## 0.3.0 — llama.cpp native inference integration (initial CPU path)

- Adds the upstream llama.cpp source as a pinned Git submodule.
- Adds an Android NDK/CMake JNI bridge for model load, prompt tokenization, greedy token generation and unload.
- The first backend targets arm64-v8a CPU for a portable baseline. GPU acceleration and streaming UI are not yet enabled.
- The Kotlin inference repository calls the JNI bridge off the main thread.
- GGUF chat-template formatting, conversation memory, cancellation, streaming tokens, performance counters and device-specific acceleration remain follow-up work.

### Initialize source
`git submodule update --init --recursive`

### Build
Install Android SDK Platform 35, NDK 27.2.12479018, CMake 3.22.1 and JDK 17. Open this directory in Android Studio and build the debug APK.

### Important
Large models may exceed device RAM. Start with a small quantized GGUF and context size 2048. This first JNI bridge is an integration foundation and must be validated on a physical arm64 device before treating it as production-ready.
