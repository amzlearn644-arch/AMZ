# AMZ AI for Android

## 0.6.0 — Personal assistants (Pals)
- Kotlin + Jetpack Compose dark chat UI.
- Hugging Face GGUF discovery, download and local import.
- llama.cpp native arm64 CPU inference through JNI.
- UTF-8-aware token streaming and sampled-token count / generation timing.
- Local conversation persistence (up to 200 messages) and clear-chat confirmation.
- Prompt builder includes recent conversation turns (last 12 messages) and applies distinct Fast/Think system instructions.
- Personal assistant profiles: built-in study, coding, writing and general presets; create, edit and delete custom Pals; persist profiles and selected Pal locally; inject the selected Pal's system prompt into local inference.
- GPU acceleration, voice and remote providers are not complete.

Build with JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1.
Initialize llama.cpp: `git submodule update --init --recursive`
Start with a small quantized GGUF and context 2048. Test on a physical arm64 phone; model RAM needs vary.
