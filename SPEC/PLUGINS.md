# Plugins v1 — Restricted Declarative Provider Protocol

## Boundary decision

**No external Kotlin/Dex/JAR/native or JS/WASM code in v1.** Community extensions may be described by a validated, versioned JSON **declaration** that only instructs a host-owned generic HTTP/JSON adapter how to call predetermined public APIs. No arbitrary scripts, expressions, templated headers holding secrets, user-controlled file reads or access to Android services. This is a narrower system than general “Tachiyomi extensions”; release messaging must be honest.

## Manifest minimum schema (conceptual; implement precise JSON Schema + tests)

```json
{
  "manifestVersion": 1,
  "pluginId": "org.example.catalog",
  "displayName": "Example Music",
  "version": "1.0.0",
  "author": "Example Publisher",
  "homepage": "https://example.org",
  "apiVersion": "1.0",
  "capabilities": ["searchTracks", "getTrack", "resolvePublicStream"],
  "permissions": {"hosts": ["api.example.org"], "network": true},
  "endpoints": {
    "searchTracks": {"baseUrl": "https://api.example.org", "method": "GET", "path": "/tracks", "queryMap": {"q": "query", "limit": "limit"}},
    "getTrack": {"baseUrl": "https://api.example.org", "method": "GET", "path": "/tracks/{trackId}"}
  },
  "mapping": {
    "tracksJsonPath": "$.data.items",
    "id": "id", "title": "title", "artist": "artist.name", "durationMs": "duration_ms",
    "playableUrl": "public_stream_url"
  }
}
```

This **example is not automatically safe or executable**; validator must implement an allowlisted subset of JSON path/field mapping with no expressions or recursive selectors, validate path placeholders and reject unsupported features. No arbitrary POST bodies, shell substitution, auth bearer extraction, custom cookie headers, WebView navigation or open redirect chain. Prefer only GET for initial generic provider with fully specified response shape; streaming endpoint may be URL supplied by response if validated through host gateway.

## Installation flow

User picks local JSON or enters HTTPS manifest URL -> host fetch with strict maximum bytes/time and HTTPS only -> parse and validate schema -> validate version/API range and duplicate ID/publisher update consistency -> show exact requested hosts and capabilities, link to license/terms if supplied -> user approves -> save manifest hash and installed state -> run controlled health check -> enable. Invalid or unapproved manifest never becomes active. If remote URL install, no automatic arbitrary updates in v1.

## Mandatory security controls

1. Whitelist https scheme, port 443 by default, canonical DNS hostnames and no credentials in URL. Resolve and reject loopback, private, link-local, multicast, unspecified, reserved addresses including IPv4-mapped IPv6; re-evaluate on *each redirect* and at actual connection time to resist rebinding. If app cannot adequately enforce DNS/IP and redirects, **disable URL installation** and limit controlled bundled/test manifests until safe.
2. Fixed maximum host count, endpoints, URL length, URL parameters, response bytes, JSON depth, item count, artwork size and concurrency; per-plugin timeouts, cancellation, rate limiting, retry caps.
3. No access to raw Room handles, contact/files, local music paths, host auth credentials or arbitrary HTTP client. Never provide a general-purpose programmable callback.
4. Test MIME/content-type, safe JSON parse, percent-encoding and any mapping paths. No eval, reflection, expression engines, dynamic class loaders, DEX or JS runtimes.
5. Plugins cannot mutate user library except through explicit user action handled by host. Disable/uninstall preserves playlist entries and source identity; avoid automatic destructive migration.
6. Install errors are reversible; plugin cannot crash player, wedge queue or block app startup.
7. Display publisher as **unverified** unless verifiable signature/trust mechanism actually implemented; don't imply checking a SHA256 downloaded from the same source establishes authenticity.

## Versioning / upgrades

`manifestVersion` is parser schema, `apiVersion` is app contract, `version` is plugin release. Reject unsupported major versions; preserve last known working manifest. Reapproval required for expanded permissions. No auto-update v1. Samples need positive and malicious cases.

## External plugin proof

Instrumented test starts a loopback *controlled* MockWebServer/fixture in test harness, with special allowlist for test builds only (never production). Install a manifest, search real fixture track, resolve/test playable small rights-cleared audio, disable/uninstall, observe queue/playlist degradation. Add unit tests for SSRF/redirect/DNS rebinding, unsafe URL schemes, oversized JSON, malformed mappings, disabling during request, update with new host, and corrupt manifest.

## Post-v1 direction

Only consider sandboxed QuickJS/WASM after a threat model, independent review and deployment-policy check. QuickJS alone does not guarantee Android isolation. Do not use plugins as a legal workaround for restrictions on other services.
