# Out of Scope and Non-goals

## Explicitly not in v1

iOS, web and desktop; Spotify account/client emulation; anonymous web-token extraction; YouTube/YouTube Music stream extraction; SoundCloud/Bandcamp scraping; DRM, paywall or geoblock bypass; cloud-based song delivery; offline caching of remotely licensed songs unless documented and permitted; universal gapless audio; lossless claims without actual codec/bit-depth; volume normalization/advanced DSP/equalizer; collaborative cloud playlists; personal AI recommendations; podcast/video; smart-home casting/Android Auto; marketplace for arbitrary community code; custom QuickJS/WASM; app auto-rewrite/updater.

## Scope tradeoffs

- **Offline-first** means local files, playlists, metadata and lyrics cache. It does not mean all online sources download forever.
- **Plugins** means a narrow, secure declarative provider manifest v1. A JS sandbox is future R&D requiring a separate security gate.
- **Privacy first** means no analytics SDK by default. Crash logs may be local/opt-in, privacy policy must reflect actual network calls to Audius/LRCLIB/MusicBrainz.
- **Account-free** means core works without login. Optional provider credentials may be needed for future apps or higher API quotas.
- **One primary Android binary**. Do not commit broad build variants or many speculative targets before working playback.
- **Recommendations** limited to provider public discovery lists and user's recent/favorites in v1, not machine-learning personalization.
