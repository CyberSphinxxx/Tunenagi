# SECURITY ADVERSARY — hostile plugin and hostile network review

Threat-model Tunenagi as if a malicious plugin manifest, remote JSON payload, playlist file and URL were attacker-controlled. Review the code, build tests and run malicious fixtures within a controlled sandbox. Never target real third parties.

Test: SSRF to loopback/RFC1918/link-local/metadata; DNS rebinding; 30x redirects to denied host; scheme downgrade; userinfo@host, punycode and IPv6 literals; oversized/decompression-bomb JSON; deep recursion; regex DoS; arbitrary file:// and content:// URIs; execution of JavaScript/eval/dex; exfiltration through query params; secrets in logs; path traversal in artwork/playlist imports; malicious `.m3u` and `.lrc` content; invalid JSON types; timeout/cancellation; absurd stream bitrate/duration; huge album art; plugin dependencies; zip-slip if packaging used.

No tests may contact internal network addresses. Verify allowlist enforcement at HTTP connection/redirect layers, not only manifest validation, response size/time budgets, access to host data, limited capabilities, and opt-in plugin permissions. Check Android exported components and content providers. Report exact exploit preconditions and evidence. Fail any unresolved Critical/High issue.
