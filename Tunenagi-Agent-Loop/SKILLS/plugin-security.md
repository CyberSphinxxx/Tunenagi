# Skill — Declarative Plugin Threat Review

**Trigger:** JSON manifest parsing, plugin install/update, host HTTP gateway, URL mapping or permission UI.

1. Read `SPEC/PLUGINS.md` and `SPEC/SECURITY_LEGAL.md`. This is declarative-only v1; **never add eval, WebView JS, dynamic dex, QuickJS, WASM, arbitrary expression evaluation or executable extensions**.
2. Define strict JSON schema and bound all fields. Default deny unsupported features. Canonicalize host/URL, reject credentials, non-HTTPS, forbidden ports, private ranges and unsafe IP literals; prevent unsafe redirects/DNS rebinding at actual connection.
3. Separate plugin-private lookup and host data. No raw OkHttpClient, local file URI, Room DB or tokens given to plugin. Persist explicit user approval for hosts/capabilities; expansion triggers reapproval.
4. Fuzz with malicious fixture cases: recursive JSON path, giant arrays, redirects from public to private host, 127.0.0.1/IP-mapped IPv6, DNS changes, userinfo, path/query injection, malformed content, cancellation, disabled mid-request.
5. Installer never silently enables unverified publisher with misleading trust label. Uninstall leaves playlists and favorites stable; external source unavailable state is visible.
6. Independently run `PROMPTS/03_SECURITY_ADVERSARY.md` after implementation. Archive reproduction/test output for all findings and the patched retests.

**Exit:** real external controlled test plugin usable and malicious fixtures rejected without exposing device/network credentials.
