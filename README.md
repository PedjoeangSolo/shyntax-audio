# SHYNTAX AUDIO — v0.2.0

Android native music player foundation (Kotlin + Jetpack Compose + Media3).

## Implemented in this increment
- Import multiple local audio files through Android Storage Access Framework.
- Local queue/list UI.
- Media3 ExoPlayer playback through `MediaSessionService`.
- Background playback session and Android media notification integration.
- Previous / next / play-pause / seek controls.
- Dark + Ice Blue interface.

## Build
Open this folder in Android Studio (JDK 17, Android SDK 35), or push it to GitHub and run **Actions → Build SHYNTAX AUDIO APK**.

The workflow uses Gradle installed on the runner. For a fully reproducible build, add the Gradle Wrapper (`gradlew`, wrapper JAR and wrapper properties) from Android Studio or `gradle wrapper`.

## Important limitations
This is an early foundation, not the finished Hi-Res player. Album/artist indexing, metadata database, advanced decoders, DSP, parametric EQ, lyrics, visualizers, USB DAC controls and backup/restore are not implemented yet. Actual codec, sample-rate and bit-perfect capabilities depend on Android, decoder and output hardware and must be tested on the target device.
