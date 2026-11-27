# AMZ AI for Android

Native Android application built with Kotlin and Jetpack Compose.

## Current foundation (0.1.0)
- Dark, rounded chat UI
- Fast / Think mode selector (UI foundation; model-specific reasoning behavior is not connected yet)
- Chat shell and navigation for Chat, Models, Pals, Settings
- Android project isolated under `android/`; the AMZ catalog website remains unchanged

## Build
Open this `android/` directory in Android Studio and use JDK 17. Gradle will download the Android Gradle Plugin and AndroidX dependencies. Then build the debug APK from **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Roadmap
1. Persistence, chat history, message editing and regeneration
2. GGUF runtime integration, model import/download and hardware capability detection
3. Hugging Face model discovery and download manager
4. OpenAI-compatible remote endpoints and secure API key storage
5. Pals, prompt templates, inference controls and context management
6. Token/speed metrics, benchmark and export
7. Speech-to-text, text-to-speech, tools, web search and localization

Features are added as working integrations; unavailable runtime features are not represented as functional controls.
