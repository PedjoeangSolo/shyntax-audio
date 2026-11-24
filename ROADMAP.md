- [x] Integrate Media3 FFmpeg decoder with ALAC enabled in CI (v1.3.2; device playback verification pending).
# SHYNTAX AUDIO — Product Roadmap

Roadmap ini menjadi daftar kerja resmi. Status "planned" berarti belum diimplementasikan; status "implemented" tetap memerlukan pengujian perangkat sebelum dianggap tervalidasi.

## Prinsip
- Jangan mengklaim Hi-Res, bit-perfect, sample rate output, atau dukungan driver tanpa pengukuran yang benar.
- Pertahankan kompatibilitas Android dan jalur pemutaran yang sudah berfungsi.
- Setiap tahap wajib melewati GitHub Actions dan uji perangkat.
- Fitur yang tidak didukung perangkat harus ditampilkan sebagai tidak tersedia, bukan simulasi.

## Sudah tersedia (perlu regresi test)
- Media3 / ExoPlayer playback dan background playback service
- Library scan, import manual, favorites, album/artist grouping
- Embedded lyrics dan import LRC/text
- Equalizer, DSP dasar, visualizer
- Smart album artwork: embedded → folder → cache → MusicBrainz/Cover Art Archive
- PCM16 resampler (Fast / Balanced / High)
- Advanced Audio Analyzer (file metadata dan PCM16 peak/RMS/clipping)
- Backup & restore preferences/library metadata

## Tahap 1 — UI/UX Hi-Fi Refresh
- [x] Refresh visual Now Playing dan Library (1.4.0); navigasi utama masih memakai alur yang sama
- [ ] Ringkasan format sumber dan perangkat output
- [ ] Quick controls untuk EQ, DSP, visualizer, analyzer
- [ ] Empty/loading/error states yang konsisten
- [ ] Accessibility, ukuran layar kecil, dan dark theme checks (perlu uji perangkat)

## Tahap 2 — Audio Diagnostics
- [ ] Audio output route change listener dan refresh otomatis
- [ ] Pisahkan data sumber, decoder PCM, dan output device
- [ ] Laporkan sample rate/format output hanya jika API/perangkat benar-benar menyediakannya
- [ ] Status USB DAC dan kemampuan format yang terdeteksi
- [ ] Diagnostic log yang dapat diekspor
- [ ] Validasi clipping/RMS analyzer dan throttling UI updates

## Tahap 3 — Playback & DSP
- [ ] Gapless playback verification dan konfigurasi nyata
- [ ] Crossfade yang terintegrasi dengan transisi player
- [ ] ReplayGain Track/Album
- [ ] Auto volume normalization (opsional, dapat dimatikan)
- [ ] Limiter dengan headroom aman
- [ ] Channel balance dan mono/stereo
- [x] AB Repeat (A/B markers and automatic loop seek; requires device verification)
- [ ] AB comparison untuk preset DSP/EQ (bukan klaim blind-test)
- [ ] Parametric EQ preset import/export
- [ ] Headphone profiles dan Auto EQ (dengan profil terukur, bukan tebakan)
- [ ] AAudio native backend sebagai eksperimen terpisah; AudioTrack tetap fallback

## Tahap 4 — Music Management
- [ ] Metadata editor dengan SAF dan penulisan tag yang aman
- [ ] Duplicate detection berdasarkan metadata lalu fingerprint opsional
- [ ] Smart playlists dan rating
- [x] Listening history (up to 50 recently played tracks, locally persisted)\n- [ ] Listening statistics (play counts and listening time)
- [ ] Folder browser dan recently added
- [ ] Manual album artwork picker

## Tahap 5 — Lyrics & Visualizer
- [ ] LRC editor dan lyric offset
- [ ] Lyrics search online dengan sumber yang jelas
- [ ] BPM/beat detection dengan kontrol konsumsi CPU
- [ ] Beat-synced visualizer dan custom presets
- [ ] Fullscreen lyrics / visualizer

## Tahap 6 — Integrasi & Reliability
- [ ] Android Auto / MediaLibraryService compatibility
- [ ] Home-screen widget
- [ ] Queue persistence dan auto resume
- [ ] Backup/restore schema migration dan verifikasi integritas
- [ ] Test layar mati, panggilan, Bluetooth, USB DAC, dan route switching
- [ ] Uji CPU/RAM/baterai pada perangkat kelas menengah

## Urutan rilis
1. UI refresh + regresi fitur lama
2. Audio diagnostics
3. Playback/DSP
4. Music management
5. Lyrics/visualizer
6. Integrasi dan reliability

