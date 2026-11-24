# SHYNTAX AUDIO — Changelog

## 1.1.0
- Added visible app version label on the Now Playing and Library screens to make test builds easier to identify.
- Enabled generated BuildConfig metadata so the displayed version always follows Gradle versionName.
- Refreshed Hi-Fi player header and quick access controls for EQ, DSP, Visualizer, and Analyzer.
- Added source format and detected audio output summary on the player screen.
- Refreshes audio output device information when Android reports audio devices added or removed.
- Added the official development roadmap in ROADMAP.md.

### Testing notes
- Successful CI build confirms compilation and APK packaging only.
- Verify playback, navigation, output device switching, and displayed version on a physical Android device.
- Output sample rate/bit-perfect claims are intentionally not shown unless measured.
