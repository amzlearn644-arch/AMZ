# AMZ AI for Android

## 0.5.0 — Persistent conversations
- Kotlin + Jetpack Compose dark chat UI.
- Hugging Face GGUF discovery, download and local import.
- llama.cpp native arm64 CPU inference through JNI.
- UTF-8-aware token streaming and sampled-token count / generation timing.
- Local conversation persistence (up to 200 messages) and clear-chat confirmation.
- Prompt builder includes recent conversation turns (last 12 messages) and applies distinct Fast/Think system instructions.
- The prompt format is currently a generic role-labelled fallback, not yet the model's native GGUF chat template.
- GPU acceleration, full sampler controls, Pals, voice and remote providers are not complete.

Build with JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1.
Initialize llama.cpp: `git submodule update --init --recursive`
Start with a small quantized GGUF and context 2048. Test on a physical arm64 phone; model RAM needs vary.
