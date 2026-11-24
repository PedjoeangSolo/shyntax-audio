# SHYNTAX AUDIO — Changelog

## 1.3.0
- Added A–B Repeat controls to mark a start point (A) and end point (B) in the current track.
- Playback automatically seeks back to A when the active player reaches B.
- A–B markers reset when the track changes and can be cleared manually.
- B can only be set after A and at a later playback position.
- Bumped app version to 1.3.0 (versionCode 15).

### Testing notes
- Test A/B marker validation, loop timing, pause/resume, manual seeking, and track changes on a physical device.
- CI success confirms compilation and APK packaging only.


## 1.2.0
- Added a local Recently Played history tab in the music library.
- Stores up to 50 unique recently played tracks and moves the current track to the top when playback starts or the player changes tracks.
- Persists history on-device using app preferences; no account or network access is required.
- Bumped app version to 1.2.0 (versionCode 14).

### Testing notes
- Verify history ordering after selecting tracks and using next/previous controls.
- Verify history survives app restart and ignores tracks no longer in the library.
- CI success confirms build/package only; physical-device regression testing is still required.


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
