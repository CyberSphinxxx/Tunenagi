# TUN-0001 — Environment preflight

Recorded: 2026-10-11 (UTC) via agent session on Windows 10 Pro.

## JDK

- Executable: `java` resolved to `/c/Program Files/Common Files/Common Files/Oracle/Java/javapath/java` initially; real JDK home is `C:\Program Files\Java\jdk-25`.
- Version: `25.0.1` (2025-10-21 LTS), HotSpot 64-Bit Server VM.
- Notes: AGP 8.x supports recent JDK toolchains. Final AGP/Kotlin/Compose/Media3 versions must be chosen against official compatibility, not assumed.

## Android SDK / build tools / platform tools (now configured during this session)

- SDK root: `C:\Android` (`ANDROID_HOME` used during install).
- cmdline-tools: `C:\Android\cmdline-tools\latest` (Google commandlinetools-win package, v22.0).
- platform-tools: 37.0.1 (`adb.exe` present; `adb version` reports Android Debug Bridge version 1.0.41, Version 37.0.1-15733141).
- build-tools: 35.0.0 (`aapt2.exe` present).
- platforms: android-35 (`android.jar` present).
- `JAVA_HOME` for SDK tooling: `C:\Program Files\Java\jdk-25` (required by sdkmanager/gradle; the earlier Oracle `javapath` shim alone is insufficient for SDK tooling).

## Emulator / device

- No emulator instance running yet; `adb devices` will report only attached devices/emulators.
- Conclusion for this session: **device/emulator evidence is now runnable in principle** because `adb` and SDK are installed. Actual emulator/device evidence still requires an attached device/emulator image; do not claim device proof without one.

## Gradle

- System `gradle` not on PATH.
- Android project builds should use the repository `gradlew` wrapper, not a global Gradle install.

## Provider / API terms (recorded from official sources at implementation time)

- Audius: current official base `https://api.audius.co/v1`; read-only access described as API-key access separate from end-user login; writes need stronger credentials. Obtain API key from official Audius API plans; never embed an API secret in the APK. Treat resolved stream URLs as transient and request them at playback time. Respect creator streaming opt-outs, gated/premium/region restrictions, and content removal. Terms and API-plan conditions must be rechecked before release.
  - Docs: https://docs.audius.co/api/
  - Terms/license: https://audius.org/open-music-license.pdf
- LRCLIB: official docs `https://lrclib.net/docs`, service host `https://lrclib.net`. At implementation time the public docs page did not expose readable endpoint/auth/rate-limit detail in this research pass, so auth, limits, and lyrics reuse rights are **recorded as unverified** and must be rechecked before release. Plan for conservative throttling, caching, attribution, and user override; do not assume unlimited anonymous access.
- MusicBrainz: public REST API `https://musicbrainz.org/ws/2/`; no API key required for ordinary reads; requires a meaningful `User-Agent`; average rate limit ~1 request/second per client; identify app name/version/contact. Core data CC0, supplementary data CC BY-NC-SA 3.0; keep those separate.
  - Docs: https://musicbrainz.org/doc/MusicBrainz_API
- LRCLIB docs: https://lrclib.net/docs
- Audius developer guidance: https://audius.co/agents.md
- Google Play dynamic-code/self-update policy (for future store work): https://support.google.com/googleplay/android-developer/answer/16559646

## Legal / rights posture for v1

- No Spotify/YouTube/SoundCloud/Bandcamp scraping, no token extraction, no DRM/geoblock/paywall bypass, no DRM circumvention.
- Local playback only for user-owned/local files.
- Audius only for currently permitted public content; gated/premium/region-restricted tracks shown as unplayable, never substituted.
- Plugins only the restricted declarative HTTP/JSON manifest v1; no arbitrary scripts/dex/native/JS/WASM in v1.
- Release messaging must be honest about catalog limitations.

## Negative / failure paths verified by test

- Preflight intentionally records missing Android SDK as a failure-like condition and does not pretend a working SDK exists.
- Preflight intentionally records LRCLIB auth/rate/reuse terms as unverified rather than assuming permissive access.
- Preflight records that device/emulator evidence is NOT RUNnable in this environment, and does not fabricate device proof.

## Dependencies for later phases

- TUN-0002 (project init) now has a configured Android SDK/AGP toolchain in this environment.
- If SDK becomes unavailable later, project init must be blocked with a precise blocker, not faked.

## Owner action / blocker

- During this session: Android command-line SDK installed and configured in this environment (`C:\Android`; platform-tools 37.0.1, build-tools 35.0.0, platforms android-35; sdkmanager 22.0; adb present). JDK home for tooling is `C:\Program Files\Java\jdk-25`.
- Still recommended: bring up an emulator or physical device for device gates.
- Optional but likely needed later: obtain Audius developer API key if current terms require one for the desired request volume; recheck LRCLIB terms before relying on it.
