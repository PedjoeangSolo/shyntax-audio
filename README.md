# SHYNTAX AUDIO — v0.8.0

Native Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Implemented
- Import local audio files with Android Storage Access Framework.
- Playback through Media3 ExoPlayer and MediaSessionService.
- Play/pause, next/previous and seek controls.
- Persistent library, Songs/Albums/Artists, search, sorting and favorites.
- Native playback-session graphic equalizer and parametric controls mapped to Android audio effects.
- Bass Boost, treble shaping, stereo width, loudness and preset reverb where supported.
- Live visualizer: Waveform, Spectrum, Circular, Oscilloscope and Particles.
- Visualizer performance controls: 15/30/60 FPS and quality settings.
- Lyrics: embedded MP3 ID3v2 USLT/TXXX and FLAC Vorbis Comment LYRICS, plus manual LRC/TXT import.
- Playback speed 0.5×–2.0× and pitch shift ±12 semitones.
- Hi-Res & Audio Output panel:
  - Reads audio MIME/codec, sample rate, channel count and bitrate when Android MediaExtractor exposes them.
  - Shows PCM encoding only when exposed by the decoder.
  - Detects connected Android output endpoints, including USB audio devices/headsets, wired, Bluetooth, HDMI and built-in speaker.
  - Refreshes the current output endpoint list on demand.
- Dark + Ice Blue interface.

## v0.8.0 scope and supported-format expectations
The player uses Media3/Android platform extractors. Common formats supported by the standard Android/Media3 path include MP3, AAC (including AAC in M4A/MP4), WAV, FLAC, Ogg Vorbis and Opus, subject to Android codec/provider support. Container and codec support can vary by Android build. APE and DSD/DSF/DFF are not added by this milestone.

## Important audio limitations
- Metadata shown in the Hi-Res panel comes from Android's extractor and describes the source track when reported. It is not a measurement of the final DAC output.
- USB device detection confirms that Android exposes a USB audio output endpoint; it does not prove that playback is routed to it at a particular sample rate.
- This version does not provide exclusive USB DAC access, manual DAC routing, output sample-rate forcing, native DSD, or verified bit-perfect playback.
- Android may resample audio through the system mixer. DSP, EQ, tempo and pitch processing also alter the signal; bit-perfect output must not be claimed while those effects are active.
- Actual Hi-Res playback depends on source file, decoder, Android audio path, device hardware and output DAC. Validate using the target device and DAC.
- PCM bit depth is often not exposed for compressed source formats; the app displays "not reported" rather than guessing.
- The Android Visualizer and Equalizer are device/ROM dependent. The 10/31-band UI maps to bands exposed by Android and is not a 31-independent-biquad software engine.
- Lyrics auto-read remains limited to MP3 ID3v2 USLT/TXXX and FLAC Vorbis Comment LYRICS; manual LRC/TXT import remains available.

## Device testing
- v0.6.0 visualizer: user-tested on TECNO Pova 7 4G / Android 16; all reported checks passed.
- v0.7.1 lyrics/tempo/pitch and embedded lyrics: user confirmed all functions worked.
- v0.8.0 Hi-Res metadata and USB output detection: build and real-device testing pending.

## Build
Run **Actions → Build SHYNTAX AUDIO APK** or use the GitHub Actions workflow after pushing to `main`. Requires JDK 17 and Android SDK 35.

Repository: https://github.com/PedjoeangSolo/shyntax-audio
