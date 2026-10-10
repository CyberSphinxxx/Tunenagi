# Release Engineering and Supply Chain

## Repository conventions

Use Gradle Wrapper checked in (generated from verified Gradle version), Kotlin DSL and `libs.versions.toml`. No `+` version ranges or snapshots for release. Lock/transparently document dependencies, verify checksums where feasible and run dependency vulnerability checks. `.gitignore` must exclude local.properties, keystores, API keys, temp audio/test dumps, build output and private SDK paths. Include `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, privacy statement, README with screenshots, app compatibility and source attribution.

## CI gates (GitHub Actions, avoid secrets on pull requests)

`validate -> static lint -> unit + integration -> assembleDebug -> instrumentation/emulator -> report + SBOM/license scan -> release candidate`. Allow split jobs and caches; do not run live Audius automated test on every PR. Run controlled MockWebServer fixtures. Sign artifacts only in protected release workflow. Test artifacts must never claim public release signing.

## Release candidate gates

- Pin versionName/versionCode, minimum API 26, compile/target current API after policy check, build tools/JDK.
- Produce debug-signed APK for QA and verify `adb install`, launch, actual playback, permissions, session controls, plugin, lyrics, offline.
- Produce *release* APK signed by an owner-approved keystore only when credentials available. Verify signature with `apksigner`, record SHA256 via sha256sum/PowerShell Get-FileHash, generate release notes and known issues.
- On existing installation test migration/update with same signing identity; uninstall/reinstall tests are different. Preserve keystore across releases; never commit key/password.
- Protect GitHub releases with tag version discipline; draft publication for owner approval. Do not auto-push tags or public releases without explicit authorization.

## Proposed output folders

`build/outputs/apk/`, `STATE/evidence/release/`, `docs/release-notes/`, `docs/licenses/`, `STATE/audits/`. Package source + APK checksums. Keep app user data private; never upload debug logs with personal media filenames by default.

## Final report

Commit SHA, file manifest, version, source/ref verification timestamps, real device/emulator model/API, test matrix, link/path to APK and checksum, signs/limitations, open issues, phase audits, data rights reviews. Declare `READY FOR OWNER APPROVAL` only if all gates pass; otherwise `BLOCKED` with exact missing proof.
