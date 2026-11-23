# SHYNTAX AUDIO — v0.4.0

Native Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Implemented
- Import multiple local audio files with Android Storage Access Framework.
- Playback through Media3 ExoPlayer and MediaSessionService.
- Play/pause, next/previous and seek controls.
- Persistent imported-library list using saved SAF URI permissions.
- Read embedded title, artist, album and duration metadata when the decoder/container exposes it.
- Library views for Songs, Albums and Artists.
- Search across title, artist and album.
- Sort by title, artist or album.
- Mark and retain favorite tracks.
- Native playback-session graphic equalizer with 10-band and 31-band control curves.
- Five-band parametric curve controls for frequency, gain and Q, mapped onto the equalizer bands exposed by Android audio effects.
- EQ presets, bypass and saved settings.
- Dark + Ice Blue interface.

## Build
Open this folder in Android Studio (JDK 17, Android SDK 35), or push it to GitHub and run **Actions → Build SHYNTAX AUDIO APK**.

## Notes and limitations
This is an early player foundation, not yet the complete Hi-Res player. Album artwork display, full MediaStore auto-scan, Room database, playlists, advanced decoders, DSP, advanced DSP beyond the Android audio effect band capabilities, lyrics, visualizers, USB DAC controls and backup/restore remain future work. The EQ uses Android Equalizer on the active playback audio session; available hardware band count and gain range vary by device. 10/31-band and parametric curves are interpolated onto the bands provided by Android, so this is not a software 31-independent-biquad engine and Q precision is constrained by the platform effect. Metadata may be unavailable for some files. Actual codec, sample-rate and bit-perfect capabilities depend on Android, decoder and output hardware and must be tested on the target device.
