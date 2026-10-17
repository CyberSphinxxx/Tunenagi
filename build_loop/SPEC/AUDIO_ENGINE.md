# Android Audio & Media3 State Machine

## Components

Single `ExoPlayer` owned by `MediaLibraryService` with `MediaLibrarySession` (or appropriate MediaSession for initial phase; the final should expose library if justified). Activity uses `MediaController`; lifecycle no duplicate players. Configure manifest permissions/types appropriate to current target SDK: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` when applicable but do not unnecessarily block playback if notification permission denied. Android behavior varies by API; test.

## Player state

`Idle -> ResolvingSource -> Preparing -> Buffering -> Ready/Playing -> Ended`; `Error` has typed reason and retry. Commands are serialized: Play, Pause, Seek, Next, Previous, QueueEdit, SourceChange. A slow/failed resolver must be cancellable and should not overwrite a newer user choice (generation/request ID). Pre-resolved remote URL is short-lived: refresh when used and on 403/expired response if documented, with bounded attempts.

## Focus and interruptions

Handle transient/permanent audio focus loss, ducking policy for spoken navigation, audio becoming noisy/headphone unplug, Bluetooth loss, lockscreen controls and notification action races, service lifecycle shutdown when idle. Respect Media3 audio focus integration. Never automatically start playing after a phone call if user had deliberately paused. Handle device unplug -> pause, reconnect -> do not surprise autoplay. Test switching apps, lock/unlock, navigation away, kill/restart, reattach media controllers.

## Queue semantics

- Queue item has unique identity independent of track; duplicates allowed.
- Shuffle uses persisted queue permutation/seed (don't reshuffle each recomposition).
- Repeat one repeats exactly current queue entry; repeat all wraps; off ends gracefully.
- Previous: restart current track if elapsed > a defined threshold, otherwise previous queue entry; threshold documented and tested.
- Remove current: advance safely or stop; remove last: reset state and hide controls correctly.
- Offline/unplayable next item: show explicit problem and skip only according to a documented bounded policy; prevent endless cycling when all items unavailable.

## Gapless and preloading

Baseline: stable immediate source resolution with loading state. Optional later prefetch/preload using Media3 supported APIs; no fixed uncompressed PCM RAM. Adaptive disk cache only for content where the license and provider policy permits caching. Stream buffer ≠ permanent downloadable song. Cap cache by bytes and TTL; clean on logout/plugin removal as relevant.

## Supported formats

Local support follows Android Media3/decoders, test MP3, AAC/M4A, FLAC, OGG where available. Report unsupported codec clearly, never silently transcode protected/unsupported media. Compare seek accuracy and song duration. Seek within supported window for live/nonseekable streams; disable seek if unavailable.

## Test instruments

Use royalty-free self-produced/rights-cleared fixtures of distinct duration/format; include short .lrc. Do not put commercial recordings in Git. Mock HTTP audio fixture locally for repeatable tests; separate *live* Audius smoke test proving real integration. Capture `adb logcat` filtered for app, service/notification screenshots where possible and media-session command results. Tests should cover audio focus, network loss, player teardown/recreate, remote expiry, queue reordering while playing, device rotation and rapid tapping.
