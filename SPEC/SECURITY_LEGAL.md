# Security, Privacy, Licensing, Distribution and Threat Model

## Assets and adversaries

Protect: local file URIs and library data, installed plugin permissions, tokens, listening preferences, app updates, user playlists/backups, Android service integrity and device/network boundaries. Threat actors include malicious plugin publisher, compromised manifest URL, remote server, playlist/LRC file, insecure Wi-Fi or supply-chain dependency.

## Threat table

| Threat | Required mitigation + proof |
|---|---|
| Plugin exfiltration | Declarative-only no device API, no host credentials, HTTP allowlists and restricted fields; integration malicious-manifest tests |
| SSRF / DNS rebinding | Validate canonical host/IP at connection and every redirect; reject internal IP ranges; test with fake resolver; no production loopback exceptions |
| Expensive / infinite requests | Timeout, per-plugin quota, cap redirect depth/bytes/items and bounded retries; cancellation tests |
| JSON/ZIP/LRC/playlist bombs | Limits on size/depth/strings/time; randomized malformed fixtures and out-of-memory regression tests |
| Secret leakage | CI secret scanning, log redaction, no source-controlled keys, API secrets never embedded APK, Android Keystore for sensitive tokens where applicable |
| Remote code | No arbitrary dex/js/native execution or script sandbox in v1; test validator rejection |
| Compromised updates | GitHub tags signed where possible, CI checksums, owner-held signing certificate; never install app code from arbitrary in-app endpoint |
| Android app exposure | Review exported activities/services/receivers, intents, deep links, content URI grants; fuzz relevant inputs |
| Insecure TLS | HTTPS-only remote plugins and APIs; normal certificate validation, no custom trust-all client; cleartext only for constrained test environment |
| User data loss | Room migrations, backup round trips, transactions, non-destructive missing file handling; rollback tests |

## Rights and provider terms

- App does not host/supply commercial audio. **But not hosting files is not sufficient legal protection**; playback method, caching, artwork, lyrics, search and plugins each have provider/rights obligations.
- Audius OML: https://audius.org/open-music-license.pdf and API: https://docs.audius.co/api/ . Respect creator API opt-outs, licensed use, attribution if applicable, paywalls and content removal. Legal summary isn't legal advice; escalate commercial distribution uncertainty.
- MusicBrainz: user-agent and rate limit. LRCLIB: identify app and honor Retry-After; lyrics rights/attribution require review. Store only data necessary for function and respect deletion/removal.
- Never integrate unauthorized Spotify/YouTube extraction, token harvesting, geo-unblocking or cipher circumvention; adding a third-party plugin doesn't make an otherwise prohibited use allowed.
- Google Play policy on app self-updates and dynamic code: https://support.google.com/googleplay/android-developer/answer/16559646 . GitHub distribution first. Future store listing needs explicit compliance review, not blanket “Play always rejects.”
- Open-source review: app GPL-3.0-or-later **proposal**, SDK Apache-2.0 **proposal**. Check license compatibility of every dependency, copyleft obligations, icon/font/artwork rights, notices, privacy disclosures. No copying Spotify app design/assets/source.

## Privacy principles

No required account; no trackers/ads/analytics SDK by default. On-device history & library, export/delete controls. List network services visibly in settings/README. No upload of full local file content except when user explicitly authorizes a compatible remote service (not in v1). Log queries and track titles only as necessary, redact them on diagnostic export unless opted in.

## Response to security issue

Stop installing/running malicious plugin, disable compromised source, preserve forensic logs sans secrets, generate regression test, patch and increment version, notify owner. Agent must not auto-publish a potentially compromised build.
