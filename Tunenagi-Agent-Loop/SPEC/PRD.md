# PRD — Tunenagi Android v1.0

## Product thesis

Tunenagi (繋ぎ), “The modular bridge for your music”, is an offline-capable, source-independent Android music player that **works without an account**. It combines the user's local audio with legitimately accessible remote music using reliable playback, user-owned playlists and strict provider boundaries. The first public source is Audius; local music always works if the online service is offline. This is **not** a general Spotify/YouTube scraper or a promise of free access to subscription catalogs.

## Personas and jobs to be done

1. **Offline listener on a 3–4GB Android phone:** find a locally stored MP3/FLAC, play through screen lock/Bluetooth, resume later, avoid huge battery/heap use.
2. **Discovery listener:** search/preview/listen to available Audius tracks without a login, favorite/queue/add to custom playlist, recover gracefully if creator disallows streaming.
3. **Library curator:** build mixed playlists, preserve unavailable entries and source preferences, export/import collection; never lose edits when offline.
4. **Power user:** install limited-scope additional provider declarations, see permissions/source health, disable faulty plugins without breaking playback.

## Main user journeys (definition of end-to-end)

**J01 first launch:** first launch -> Home with clear empty/local/online entry points -> no permission modal until user explicitly taps import/scan -> choose MediaStore access or folder picker -> library appears -> choose track -> audio plays with media notification. Deny permission -> explain alternative; no crash.

**J02 remote discovery:** Search -> select All/Device/Audius -> enter title -> debounce + cancellation -> results with artwork/source/artist -> click playable track -> actual streaming -> error state on geo/rights/provider denial -> retain queue intent; no fake substitute.

**J03 persistent playback:** Start local song -> lock screen -> headset controls play/pause/skip -> audio focus interruption pauses/ducks correctly -> return app -> current position/queue visible -> force-close/reopen behavior documented (do not promise playback after force stop).

**J04 combined playlist:** Add local and Audius tracks to playlist -> reorder -> edit name -> relaunch -> order and source identity preserved -> offline mode makes remote entry unavailable but does not delete it -> JSON export/import round trip.

**J05 lyrics:** While listening, open lyrics -> local .lrc preferred -> synced highlight and seek-to-line -> LRCLIB fallback if terms permit -> gracefully handle instrumental/unknown/plain lyric -> cached results offline.

**J06 external plugin:** Import a validated declarative manifest from file/URL -> user sees publisher, exact network permissions, version -> approve -> test provider is discoverable and returns authorized public content from test fixture/controlled endpoint -> disable plugin -> source disappears without corrupting playlist/history -> re-enable.

**J07 failure recovery:** turn off network mid Audius track -> bounded loading / retry / clear offline indication; switch to local track instantly; preserve remote track's queue entry and last known metadata.

## Functional requirements

### Playback [P0]
- Play/pause, seek, next/previous, seekbar, queue reorder, shuffle/repeat-one/repeat-all/repeat-off, play next/add queue; distinguish play previous (restart if elapsed threshold) from previous item. Deterministic shuffle with persisted seed/order.
- Single Media3 session in service; media notification, headset/lockscreen, audio focus, audio becoming noisy; correct foreground service types/permissions. Graceful state under app process death and interruptions.
- MediaStore and SAF file scanning; MP3/AAC/FLAC/OGG and formats supported by device decoder; unsupported codec state clear. Don't claim universal codec support.
- Real Audius public track playback using current documented API (do not bypass gated tracks). Stream URL may be short-lived; refresh on playback not metadata caching.
- Recoverable typed errors: Offline, NotFound, Restricted, Unauthorized, Throttled, Timeout, DecodeFailure, StorageGone, PluginRejected, Unknown.

### Catalog & library [P0]
- Home with recent/favorites and provider discovery; Search all/local/Audius with track/artist/album where available. Local library lists songs, artists, albums; favorites; editable playlists with order and stable IDs; recently played with sensible completion threshold.
- Store canonical track and source separately. Source confidence; manual override and lock per track for disambiguation where alternatives exist. Do not auto-merge uncertain editions.
- Offline-first metadata, resilient migrations, efficient pagination, duplicate handling without deleting user intent. On rescans, preserve playlists referencing missing files.
- Back up and restore app playlists/favorites/source preferences via documented JSON schema with schemaVersion, deterministic ID mapping and safe limits. M3U optional only if file URI rules and mapping tested; P0 only Tunenagi JSON.

### Lyrics [P0]
- Import sidecar .lrc for local files with parser tests (offsets, duplicate timestamps, negative/unordered timestamps, malformed lines, multiple tags), plain lyric fallback and LRCLIB remote search with User-Agent/rate handling. Tap line to seek when timestamps valid. Attribution/source displayed as required. Cache with expiry/refresh and user clear action.

### Plugins [P0]
- Built-in Audius provider follows the same *conceptual* discovery/playback interface, but external plugins may only use versioned declarative HTTP+JSON templates, without JS/script/native code.
- Install from controlled user-picked local JSON or HTTPS manifest; validate schema + signature/hash if present (do not imply unverified publisher trust); show allowlisted domains, types of data requested, install location, version, and explicit approval. Support enable/disable/uninstall and errors.
- At least one end-to-end installed **real** sample plugin operates against a local controlled test server for instrumentation; demonstrate a real network/result/playback path using rights-cleared test content. Can't claim plugin support based solely on manifest parsing.

### Settings & transparency [P0]
- Dark default, system theme optional, data-saving toggle influencing artwork/preload but not unsupported quality claims, cache clear, storage, player behavior, credits/licenses, version, export/import, plugin management.
- Source inspector in Now Playing: name, playable/disabled status, approximate quality only if source supplies it, explanation of any source mismatch. Never label lossy stream lossless based on extension alone.

## Quality attributes / measurable acceptance

- No unhandled crash/ANR in defined scripted P0 journeys on supported API levels. No infinite loading spinners (timeouts/cancel/retry).
- Start and scroll performance measured with Macrobenchmark + representative low-memory device; targets chosen after baseline, document p50/p95 and release vs debug distinction. No unqualified 'zero latency' or 'always 120 Hz' claims.
- Persistent playlist/queue survives app restart, device reboot as supported and database migration; content URI permissions behave correctly for documented import method.
- Battery/network respect: no constant scanner, unlimited preloading, or unnecessary wake locks. Plugin request time/size bounds.
- UI fully tappable and understandable; accessible labels, touch targets, font scaling; content descriptions/semantics; tested error/empty states.

## Success / release KPIs (verification, not fabricated targets)

- All P0 feature IDs verified with command + device evidence; zero open critical/high P0 defects; explicit known issues list.
- Real local playback and Audius playback on device; true external sample plugin; offline degraded state; media interruption/notification verified.
- Signed release-capable APK once owner key available, installed and checksum documented; build/test CI green.

## Scope governance

Owner-approved changes to P0 require ADR with rationale, replacements and updated tests/tasks; agents cannot weaken requirements unilaterally. Any P1 item that risks P0 stability is postponed. In case of rights conflict, preserve legality and mark affected source behavior as blocked rather than circumvent.
