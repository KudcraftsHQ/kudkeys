# :voice — Kud Keys voice client

Android library (`com.kudcrafts.kudkeys.voice`) that streams the mic to the Kud Keys voice server
(`server/`, protocol in `server/README.md`) and gives the keyboard one `StateFlow` to draw. Public API
and a hold-to-talk example: [API.md](API.md).

Toolchain matches HeliBoard v4.2: AGP 9.1.1 (built-in Kotlin), Kotlin 2.4.0, Gradle 9.7.1,
compileSdk 37, minSdk 21, Java 17. Dependencies: OkHttp 4.12.0, kotlinx-coroutines 1.10.2,
kotlinx-serialization-json 1.11.0 (JSON tree API only, no compiler plugin).

## Build and test on its own

```bash
source ~/.config/android-env.sh
cd android/voice
./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g testDebugUnitTest assembleRelease
./gradlew --stop
```

Unit tests are plain JVM (no Robolectric, no device): OkHttp MockWebServer plays the server, a WAV
plays the mic (`WavAudioSource`).

Live run against production (opt-in; reads the token from `~/.config/kudkeys/token`, streams
`$TMPDIR/kudkeys-voice/fm.wav` in real time, writes `$TMPDIR/kudkeys-voice/live-result.txt`):

```bash
KUDKEYS_LIVE=1 ./gradlew --max-workers=2 testDebugUnitTest --tests '*VoiceLiveTest*'
```

## Joining the keyboard's Gradle root (for the integrator)

The module is standalone today only through four files. When `android/` has HeliBoard's root build:

1. **Delete** from `android/voice/`: `settings.gradle.kts`, `gradle.properties`, `gradlew`,
   `gradlew.bat`, `gradle/` (the wrapper). Keep `build.gradle.kts` unchanged: it uses
   `plugins { id("com.android.library") }` with no version, which the root's buildscript classpath
   (AGP 9.1.1 + Kotlin 2.4.0) provides.
2. **Add** to `android/settings.gradle` (or `.kts`): `include ':voice'`.
3. **Add** to `android/app/build.gradle.kts` dependencies: `implementation(project(":voice"))`.
4. Optional, for CI: `./gradlew :voice:testDebugUnitTest`. The live test skips itself unless
   `KUDKEYS_LIVE=1`.

The library's manifest adds `RECORD_AUDIO` and `INTERNET`; they merge into the app. OkHttp and
coroutines need no extra ProGuard rules.

## Rules this module keeps

- The token is never in the APK; it comes from `VoiceSettings` (app-private prefs) at each start.
- Nothing is logged: no transcript text, no token. `VoiceEndpoint.toString()` masks the token.
- The mic is released the moment recording ends (`stop`, `cancel`, error, or `done`).
- Asking for the mic permission is the caller's job (`hasMicPermission(context)`).
