# Detoxin Android Project Guidelines

## Project Overview
Offline-first Android application designed to limit addictive social media loops (Shorts, VK clips/feed, TikTok) and track daily habits.
- **minSdk**: 29
- **targetSdk**: 34
- **Language**: Kotlin 2.0+
- **UI**: Jetpack Compose (Material 3), pure Canvas for charts (no third-party chart libraries)
- **Local Data**: Room 2.6+ with KSP, Flow, Coroutines
- **Widgets**: Jetpack Glance 1.1.0

## Core Rules
1. **Zero Internet Policy**: `android.permission.INTERNET` is strictly prohibited. The app must remain 100% offline.
2. **No Placeholders**: Never write `// TODO`, `/* implement later */`, or incomplete boilerplate. Output complete, strongly typed, production-ready code.
3. **Compilation First**: Always verify your code by running `./gradlew compileDebugKotlin` before declaring a task done. If the build fails, analyze the error log and fix it immediately.
4. **Lifecycle & Thread Safety**:
   - Heavy parsing, DB operations, and node traversals must never run on the Main Thread.
   - For `AccessibilityNodeInfo` on SDK < 34, ensure `.recycle()` is invoked on temporary nodes during traversals.

## Build & Test Commands
- Compile Kotlin: `./gradlew compileDebugKotlin`
- Full debug build: `./gradlew assembleDebug`
- Clean build: `./gradlew clean`