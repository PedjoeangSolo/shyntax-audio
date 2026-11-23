# SHYNTAX AUDIO — v0.6.0

Native Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Implemented
- Import multiple local audio files with Android Storage Access Framework.
- Playback through Media3 ExoPlayer and MediaSessionService.
- Play/pause, next/previous and seek controls.
- Persistent imported-library list using saved SAF URI permissions.
- Read embedded title, artist, album and duration metadata when exposed by the file.
- Library views for Songs, Albums and Artists; search, sort and favorites.
- Native playback-session graphic equalizer with 10-band and 31-band control curves.
- Five-band parametric curve controls mapped onto the equalizer bands exposed by Android audio effects.
- EQ presets, bypass and saved settings.
- Session-based DSP controls: Bass Boost, treble shaping, Virtualizer width, Loudness Enhancer and preset reverb where supported.
- Live visualizer reads waveform and FFT data from the active playback audio session using Android's Visualizer effect.
- Visualizer modes: Waveform, Spectrum Bars, Circular Spectrum, Oscilloscope and Particles.
- Visualizer performance controls: 15/30/60 FPS and 32/64/96/128 FFT display bins.
- Visualizer mode and performance preferences persist locally.
- Dark + Ice Blue interface.

## Build
Open this folder in Android Studio (JDK 17, Android SDK 35), or push it to GitHub and run **Actions → Build SHYNTAX AUDIO APK**.

## Notes and limitations
This is an early player foundation, not yet the complete Hi-Res player. The Android Visualizer effect is device/ROM dependent and requires the user to grant the RECORD_AUDIO runtime permission before capture. It captures the playback session's waveform/FFT; if the platform denies or does not expose the effect, the app reports the failure instead of faking audio data. Lower FPS and fewer FFT bins reduce UI work. A custom JavaScript visualizer editor, lyrics, tempo/pitch, USB DAC controls, advanced decoders and backup/restore are not part of this 0.6.0 build yet.

The EQ uses Android Equalizer on the active playback audio session; available hardware band count and gain range vary by device. 10/31-band and parametric curves are interpolated onto the bands provided by Android, so this is not a software 31-independent-biquad engine and Q precision is constrained by the platform effect. Actual codec, sample-rate and bit-perfect capabilities depend on Android, decoder and output hardware and must be tested on the target device.
