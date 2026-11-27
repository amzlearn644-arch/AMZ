# AMZ AI for Android

Native Kotlin + Jetpack Compose application.

## 0.2.0 — GGUF model library foundation
- Search public Hugging Face repositories tagged for GGUF.
- List GGUF files exposed by each repository.
- Download selected GGUF files to app-private storage with live byte progress.
- Import local GGUF files using Android's system document picker.
- List locally stored GGUF files and select one for the next inference integration.
- Model downloads use the public Hugging Face resolve endpoint; gated repositories and private models are not yet supported.
- This release does not yet execute inference. A downloaded/imported model is not a loaded model until the llama.cpp runtime is integrated.

## Build
Open this `android/` directory in Android Studio with JDK 17. Build the debug APK from **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Roadmap
1. llama.cpp Android native runtime (JNI/NDK), streaming token generation and safe model lifecycle
2. Model metadata, memory preflight and hardware/backend capability reporting
3. Remote OpenAI-compatible endpoints and secure API key storage
4. Persistent chats, edit/regenerate, Pals and inference settings
5. Token/speed metrics, benchmark, voice, tools, web search and export
