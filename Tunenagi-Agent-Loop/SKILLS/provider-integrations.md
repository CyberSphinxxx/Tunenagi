# Skill — Provider Integration Discipline

**Trigger:** Audius, MusicBrainz, Cover Art Archive, LRCLIB changes.

1. Recheck official endpoint, auth, rights, headers, rate limits and example responses **today**; save docs URLs/time and API schema excerpts in decision notes. Do not invent endpoint names or reuse stale unofficial reverse-engineering posts.
2. First construct MockWebServer fixtures for success, null/missing field, malformed JSON, 401, 403, 404, 429 with `Retry-After`, 500, timeout, redirect and empty pagination. Validate mapped Track/TrackSource IDs stay stable.
3. Expose a typed provider interface with cancellation/structured errors. Limit request duration, bytes, concurrency and backoff. No infinite retries, no exponential storm; apply conditional caching if allowed.
4. For live acceptance test, use one real currently accessible public Audius track and rate-respecting calls for MusicBrainz/LRCLIB. No scraping or bypassing creator settings. Distinguish provider outage from malformed app request.
5. Playback URL is temporary; refresh at resolve. Do not persist token-bearing stream URLs or print them into logs. Artwork must be decoded with bounds/placeholder.
6. When provider terms prohibit a requested behavior, remove that behavior from implementation, document legal blocker and escalate owner instead of using an unofficial endpoint.

**Exit:** mock contract tests + independent live smoke with actual provider response/status and real Android playback for streaming task.
