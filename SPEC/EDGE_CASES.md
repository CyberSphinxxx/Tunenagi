# Adversarial Edge-Case Matrix

Each row should become at least one regression test or explicit device QA scenario. Record coverage in `STATE/test_matrix.md` as implemented.

## Audio / queue

- Start -> pause -> rapid next/previous (10 taps) -> seek before prepared -> queue reorder while song changes -> remove current/last -> repeat one -> shuffle -> restore queue across process death.
- Zero duration, absurdly long duration, unavailable duration, live/non-seekable source, malformed duration metadata; pause at final 100ms; Bluetooth disconnect while buffering; wired headphones removed.
- Service destroyed with bound controllers, task swiped away, app force-stopped by OS/user, phone call interrupts, navigation ducking, notification permission denied, notification action while app cold.
- Network loss/resume mid-track, server 206/416 ranges, content without Content-Length, redirect loops, expired URL, codec mismatch, server returns HTML as audio, corrupted FLAC, unsupported format.

## Catalog / local files

- Empty library, 1 track, 10k tracks, duplicate filename across folders, missing tag/Unicode/emoji/RTL, corrupted tag, missing art/20MB embedded art; SD card removed and reattached.
- Android 13+ permission grants, partial/missing permissions, SAF folder revocation, repeated scan without duplicates, reused MediaStore ID after re-index; Android user revokes access while app open.
- ISRC shared across different edits, same title differing artist/version, tracks with ±3+ seconds intros, podcast mislabeled song, casefold and Unicode normalization.

## Remote APIs

- Audius rights opt-out, premium/gated content, deleted/unlisted creator track, 401/403/404/429/500, empty list, pagination token malformed, temporary audio URL expiry, missing stream URL/redirect to restricted host.
- MusicBrainz 1 req/sec throttle and User-Agent, invalid MBID, ambiguous recording, response missing cover art, stale metadata.
- LRCLIB exact/no match, plain-only, offset line, multiple timestamps in line, malformed LRC, 429 Retry-After as non-JSON body, language variation, false positive title.

## Plugins / hostile data

- Manifest JSON malformed/oversize/nested, unknown schema/API major, id collision, host permission expansion, private IP/IPv6/localhost, DNS rebinding, redirects into local LAN, non-HTTPS, URL userinfo spoofing, large returned array, huge base64 art, invalid JSON path/expression attempt, Unicode host/path, timeouts.
- Disable plugin during stream resolve, uninstall with existing favorite/playlist entries, restart after failed install, plugin returns copyrighted/restricted/unknown content, user rejects permission.

## Library / persistence

- Queue duplicate entries, playlist duplicate tracks, concurrent reorders, two simultaneous rename requests, app killed during export/import, wrong backup schema, huge JSON, invalid ISO timestamp, corrupt DB migration, missing provider after restore, local URI inaccessible on another phone.

## UI/accessibility/performance

- 320–360dp small screen, landscape keyboard open, large text 2x, TalkBack focus, system high contrast, reduce motion, dynamic colors on/off, missing artwork, empty/infinite scrolling, long track names, RTL artist names, mini-player safe-area insets, taps while loading, rapid tab switching and keyboard dismissal.
