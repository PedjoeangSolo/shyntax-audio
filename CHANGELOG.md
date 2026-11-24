## v1.5.0 — Responsive Hi-Fi Player Redesign
- Refined the Now Playing screen with screen-height-aware album artwork sizing and compact spacing for shorter phone displays.
- Expanded direct access to Equalizer, DSP, Visualizer, Analyzer, Lyrics, and Audio Output from the player.
- Improved the Library mini-player with Shuffle and Repeat controls alongside previous/next and seek.
- Kept the Media3 playback service, FFmpeg ALAC decoder, DSP processing, resampler, and existing library data flow unchanged.
- Bumped app version to 1.5.0 (versionCode 19).

### Testing notes
- Verify the player layout on short and standard phone screens, including long titles and missing artwork.
- Regression-test ALAC 96 kHz playback, other formats, Shuffle/Repeat, mini-player seeking, EQ, DSP, visualizer, analyzer, lyrics, and output settings.
- CI success confirms compilation and APK packaging only; it does not prove device behavior.

## v1.4.0 — Hi-Fi UI Refresh
- Introduced a refined dark navy and ice-cyan visual system for a more premium Hi-Fi identity.
- Redesigned the Now Playing header, source/output summary, album-art presentation, track metadata, and quick audio controls.
- Refreshed Library branding, album/song surfaces, and compact Now Playing panel.
- Preserved existing playback, queue navigation, Shuffle/Repeat, A–B Repeat, lyrics, EQ, DSP, visualizer, analyzer, backup, and output settings interactions.
- Bumped app version to 1.4.0 (versionCode 18).

### Testing notes
- Verify Now Playing and Library on a physical device, including short screens, long titles, and missing artwork.
- Regression-test playback controls, Library search/filter/sorting, lyrics, EQ/DSP, visualizer, analyzer, and audio settings.
- CI success confirms compilation and APK packaging only; it does not prove device behavior.

## v1.3.2
- Add the Media3 FFmpeg software decoder with ALAC support for files that the Android platform decoder cannot play.
- Keep the platform decoder available as fallback for other supported formats.
- Build native decoder libraries in GitHub Actions for Android ABIs.
- Requires device playback verification with ALAC files at 44.1, 48, 96 and 192 kHz.

# SHYNTAX AUDIO — Changelog

## 1.3.1
- Refined the Now Playing layout for phone screens with a more balanced album-art size and tighter spacing.
- Reduced track title and artist text sizing to improve fit; album metadata is shown when available.
- Improved Library filter/action row layout and added Recently Played sorting.
- Connected Shuffle and Repeat controls to the Media3 player and fixed active-track selection by playlist index.
- Bumped app version to 1.3.1 (versionCode 16).

### Testing notes
- Verify the Now Playing layout on small and standard phone screens, including long titles and missing album metadata.
- Verify Library filters, sorting, Shuffle, Repeat, artwork, and playback navigation on a physical device.
- CI success confirms compilation and APK packaging only; it does not replace device testing.


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
