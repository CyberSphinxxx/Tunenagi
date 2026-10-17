# Source Integrations, Metadata and Compliance

## Provider independence

A metadata record describes a track; a `TrackSource` describes a particular playable version/URI. Provider interfaces return normalized DTOs; the queue never directly manipulates a provider API. A provider may disappear or be temporarily unavailable; the library and playlists must remain accessible. Do not use Spotify IDs, MusicBrainz IDs or Audius IDs as internal PKs.

## Built-in local adapter

- MediaStore for on-device library where permission granted; SAF for selected folders/docs, persist URI grants when possible.
- Incremental scan off main thread; index canonical URI and metadata; non-destructive when permission revoked or SD card removed.
- Cover artwork via MediaStore/embedded art (downsample and bounds); local track gets priority for identical confirmed recording only, not approximate title.
- Pure offline availability; verify file readability before resolve; failed reads return StorageGone/PermissionRequired.

## Built-in Audius adapter

Docs: https://docs.audius.co/api/ and https://docs.audius.co/sdk/ ; terms and OML: https://audius.org/open-music-license.pdf . At first implementation re-open current docs, including auth/limits/streaming permissions. Use documented public search/trending/track/stream endpoints. Never assume an endpoint from an older blog post. Read-only may work without credentials; credentials or different quotas may be required. Implement public/no-credential experience when terms permit and truthful onboarding otherwise.

- Query search/trending with pagination and explicit client identification/API key as documented. If key is required and unavailable, mark remote discovery **blocked**, not silently replace with scraped sources.
- Creator may disable API streaming; gated/paid/region-restricted tracks must show unplayable state, never replay a piracy source. Respect 401/403/404/429 distinctly. Do not fake metadata for gated tracks.
- Resolve playable stream as late as practical. Handle redirect/transcoded format and URL expiry; don't store stream URLs in library DB. Prefer playback using documented stream API; never fetch restricted downloads/hidden stems.
- Artist/title/artwork/attribution sourced and displayed accurately. Honor content removal and rights restrictions, privacy/retention, caching and link requirements.
- Live smoke test with a real currently accessible public track, not a hardcoded permanent track ID only. CI unit/integration tests use MockWebServer fixtures to avoid throttling provider.

## MusicBrainz metadata enrichment

Docs: https://musicbrainz.org/doc/MusicBrainz_API . **Max 1 request/second per client** and identifying User-Agent. Use bounded queue/rate limiter shared across all app flows, cache results, avoid lookup for every row in gigantic lists; defer until needed. MBIDs are *evidence*, not total truth. Album/artist fuzzy matches need artist+title+duration and confidence; do not merge covers/live/edit versions based on rough similarity.

## Cover art

Local embedded art -> native provider art -> Cover Art Archive by reliable release ID. Cache/downsample; respect remote responses and credits; deterministic placeholder. Reject redirects to unsafe addresses; no decoding full-resolution unbounded bitmaps.

## LRCLIB lyrics

Docs: https://lrclib.net/docs . Use `User-Agent` (app name/version/contact URL), short delays between requests, honor 429 `Retry-After` even when error body not JSON. Request by title/artist/album/duration where reliable. Parsing: `[mm:ss.xx]` stamps, offset tags, duplicate stamps, unsynced text, Unicode, blank lines. Avoid automatic wrong lyrics; score matches and let users override locally. Do not use unofficial Musixmatch token extraction. Cache provenance, plain/synced and fetched time; allow clearing.

## Provider error taxonomy

```
ProviderError.NetworkUnavailable
ProviderError.Timeout
ProviderError.Throttled(retryAfter?)
ProviderError.NotFound
ProviderError.Restricted(reason?)
ProviderError.Unauthorized
ProviderError.MalformedPayload
ProviderError.UnsupportedFormat
ProviderError.Unknown
```

Map to English UI messages and retry policies; never surface raw private tokens or URLs in error reports. A provider failed result cannot poison unrelated local track playback.

## HTTP and observability

Provider network client must set finite connect/read/call timeouts, bounded response body, cancellation, redirect validation and user agent. Retry only bounded transient failures. Instrument local opt-in diagnostic events without telemetry uploads: timing, error category, provider ID, request correlation ID; redact track query when logs exported unless user opts in.
