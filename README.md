# SHYNTAX AUDIO — v1.6.0

Native Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## v1.6.0 interface update
- Poweramp-inspired Now Playing hierarchy with prominent square artwork and focused transport controls.
- Compact song rows and two-column album/artist browsing with embedded artwork thumbnails.
- SHYNTAX retains its own dark navy and ice-blue identity; playback and FFmpeg ALAC decoding remain unchanged.

## Implemented
- Automatic music-library scan through Android MediaStore after the user grants audio-library permission; manual SAF import remains available.
- Rescan button to discover newly added music without restarting the app. Scanning runs off the UI thread and merges results without deleting existing manually imported entries or favorites.
- Import local audio files with Android Storage Access Framework.
- Playback through Media3 ExoPlayer and MediaSessionService.
- Play/pause, next/previous and seek controls.
- Persistent library, Songs/Albums/Artists, search, sorting and favorites.
- Modern SHYNTAX graphic EQ interface with 31-point controls, frequency-curve preview, preamp, Bass Boost, built-in presets and saved custom presets.
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
- Dark + Ice Blue interface with a dedicated adaptive Android launcher icon.
- ZIP backup and restore for library references, favorite tracks, lyrics, EQ, DSP, playback tempo/pitch and visualizer preferences. Music files are excluded; Android SAF URI permissions may need to be granted again if restored on another device.

## v0.8.0 scope and supported-format expectations
The player uses Media3/Android platform extractors. Common formats supported by the standard Android/Media3 path include MP3, AAC (including AAC in M4A/MP4), WAV, FLAC, Ogg Vorbis and Opus, subject to Android codec/provider support. Container and codec support can vary by Android build. APE and DSD/DSF/DFF are not added by this milestone.

## Important audio limitations
- Metadata shown in the Hi-Res panel comes from Android's extractor and describes the source track when reported. It is not a measurement of the final DAC output.
- USB device detection confirms that Android exposes a USB audio output endpoint; it does not prove that playback is routed to it at a particular sample rate.
- This version does not provide exclusive USB DAC access, manual DAC routing, output sample-rate forcing, native DSD, or verified bit-perfect playback.
- Android may resample audio through the system mixer. DSP, EQ, tempo and pitch processing also alter the signal; bit-perfect output must not be claimed while those effects are active.
- Actual Hi-Res playback depends on source file, decoder, Android audio path, device hardware and output DAC. Validate using the target device and DAC.
- PCM bit depth is often not exposed for compressed source formats; the app displays "not reported" rather than guessing.
- The Android Visualizer and Equalizer are device/ROM dependent. The 10/31-band UI maps its control curve onto bands exposed by Android and is not a 31-independent-biquad software engine. Preamp gain is applied as an offset to the mapped EQ curve; Bass Boost uses Android's optional playback-session effect.
- Lyrics auto-read remains limited to MP3 ID3v2 USLT/TXXX and FLAC Vorbis Comment LYRICS; manual LRC/TXT import remains available.

## v1.0.0 music scanning and optimization notes
- Android 13+ requests `READ_MEDIA_AUDIO`; Android 12 and older use `READ_EXTERNAL_STORAGE` (limited to API 32).
- The scanner reads indexed audio metadata from MediaStore instead of opening every file individually, reducing unnecessary I/O. Newly added files may appear after Android's media index updates.
- Manual file selection remains available when permission is denied or a file is not indexed by MediaStore.
- The app does not recursively crawl arbitrary folders; it relies on Android's media index for a safer, more battery-conscious scan.

## v0.9.0 backup notes
- Backup is a ZIP containing a versioned JSON settings manifest.
- Restore validates the archive and supported preference value types before applying data.
- After restore, restart the app to reinitialize audio effects and playback parameters.
- The backup does not copy audio files or Android-granted access permissions to external document URIs.

## Device testing
- v0.6.0 visualizer: user-tested on TECNO Pova 7 4G / Android 16; all reported checks passed.
- v0.7.1 lyrics/tempo/pitch and embedded lyrics: user confirmed all functions worked.
- v0.8.0 Hi-Res metadata and USB output detection: user confirmed all functions worked.
- v0.9.0 backup/restore and launcher icon: user confirmed all functions work on device.
- v1.0.0 automatic MediaStore scan: implementation/build validation pending; real-device test required.

## Build
Run **Actions → Build SHYNTAX AUDIO APK** or use the GitHub Actions workflow after pushing to `main`. Requires JDK 17 and Android SDK 35.

## Contributing
Contributions are welcome! You can report bugs, suggest features, improve documentation, test on other Android devices, or submit code changes.

- Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a Pull Request.
- Use the [bug report](https://github.com/PedjoeangSolo/shyntax-audio/issues/new?template=bug_report.md) or [feature request](https://github.com/PedjoeangSolo/shyntax-audio/issues/new?template=feature_request.md) templates.
- Read our [Code of Conduct](CODE_OF_CONDUCT.md) and [Security Policy](SECURITY.md).
- Submit changes through a fork and Pull Request. All changes are reviewed before merging.

Repository: https://github.com/PedjoeangSolo/shyntax-audio
