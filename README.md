# SHYNTAX AUDIO — v0.7.1

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
- Live visualizer using waveform and FFT data from the active playback audio session.
- Five visualizer modes: Waveform, Spectrum Bars, Circular Spectrum, Oscilloscope and Particles.
- Visualizer performance controls: 15/30/60 FPS and FFT display quality options.
- Automatic embedded lyrics reading for MP3 ID3v2 USLT/TXXX and FLAC Vorbis Comment LYRICS tags.\n- LRC synchronized lyrics and plain TXT lyrics import, saved per track; a manual import overrides embedded lyrics for that track.
- Current lyric line highlighting based on playback position.
- Playback speed / tempo control from 0.5× to 2.0×.
- Pitch shifting from -12 to +12 semitones, independently adjustable from playback speed.
- Playback speed and pitch preferences saved locally and restored on app start.
- Dark + Ice Blue interface.

## Device test report — v0.6.0
User-tested on TECNO Pova 7 4G / Android 16. All reported visualizer checks passed, including all five modes, FPS/quality settings, pause/start, track changes, background playback and stability.

## v0.7.0 testing status
The lyrics, tempo and pitch controls have been added to the source. The GitHub Actions build and on-device testing are still required before these new features can be considered verified.

## Build
Open this folder in Android Studio (JDK 17, Android SDK 35), or push it to GitHub and run **Actions → Build SHYNTAX AUDIO APK**.

## Notes and limitations
This is an evolving player foundation, not yet the complete Hi-Res player. LRC timestamps are used for line highlighting; plain TXT files are displayed without time synchronization. Lyrics are imported for the currently selected track and stored locally against that track's URI. Automatic embedded-tag support is currently limited to MP3 ID3v2 USLT/TXXX and FLAC Vorbis Comment LYRICS; other formats/tag variants may need additional parsing. OGG, M4A/ALAC, APE and synchronized ID3 SYLT are not yet supported by this embedded reader. Online lyrics fetching and an in-app lyric timing editor are not included yet.

Tempo and pitch use Media3 playback parameters. The effective time-stretch/pitch-shift quality depends on Media3's processing path, codec and device. This is not a promise of artifact-free studio-grade processing; test with different tracks and listen for artifacts. Speed/pitch processing may affect bit-perfect output, so bit-perfect playback must not be claimed while these processing controls are active.

The Android Visualizer and Android Equalizer effects are device/ROM dependent. EQ curves are mapped onto the bands exposed by Android and are not a 31-independent-biquad software engine. Actual codec, sample-rate, USB DAC and bit-perfect capabilities depend on Android, decoder and output hardware and must be tested on the target device.

## Next milestone — v0.8.0
Focus: decoder/format expansion, Hi-Res playback path investigation, output-device information and USB DAC compatibility checks. Hardware-dependent features will be reported only after real-device testing.
