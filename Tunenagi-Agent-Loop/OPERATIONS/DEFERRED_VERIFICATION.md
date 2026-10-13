# Deferred verification register

Owner instruction (2026-10-14): continue the build **without** Android device testing. ADR-008
records the decision. This file is the standing, auditable list of the proof that is therefore
*missing* from every `verified` status in `STATE/tasks.json`, so that "verified" is never
mistaken for "proven on a device".

## What changed, mechanically

`evidenceKinds` was reduced to `command` on the 38 tasks that previously required a `device`
and/or `live` artifact (35 `command,device`; 3 `command,device,live`). No task was deleted,
reordered or re-scoped, and no acceptance criterion was removed: the deliverables and the
`acceptance` text still describe the on-device behaviour. What changed is which artifact
`taskctl verify` demands as the minimum, because the owner has directed that no Android runtime
is available.

A substitute is used for the parts that can honestly be substituted, and nothing else:

| Kind of proof | Substitute used | What it genuinely proves | What it never proves |
|---|---|---|---|
| Compose UI behaviour | `src/sharedTest` Compose tests executed on the JVM by Robolectric (`:app:testDebugUnitTest`) | real composition, real navigation graph, real semantics tree, real assertions | device insets/cutouts, OEM theming, GPU rendering, input latency, TalkBack |
| Audio playback | `Media3` `ExoPlayer` driven through a `TestPlayer`/fake renderer in JVM tests | state machine, queue transitions, error mapping, state restoration | real codecs, real audio focus, real background service survival, lockscreen |
| Provider integration | MockWebServer fixtures of the real payloads | request shape, parsing, error/rate-limit mapping, retry policy | live network, real catalog contents, upstream policy/entitlement changes |
| Storage | Room in-memory + migration tests on the JVM | schema, DAO queries, migration correctness | real on-device storage behaviour, process death, large real libraries |
| Live third-party smoke | none | — | an actual Audius/LRCLIB/MusicBrainz request never happened; no live artifact exists |

## The three verification classes, stated plainly

1. **Verified here** — the task ran a real command that passed, with a manifest produced by
   `SCRIPTS/run_check.py`.
2. **Verified by JVM substitute** — as the table above; the limitation is recorded per task.
3. **Not performed at all** — anything named in the deliverables below that needed a device, a
   live provider or an owner-held key.

## Standing list of what remains unperformed

- **Runtime/launch proof**: install and launch the APK on a real Android runtime; first-launch
  screenshots; API 26/33/35 matrix; rotation, font scale 1.3x/2.0x, small-screen 360x640dp,
  tablet 800dp+; gesture vs three-button navigation; notification/media-session controls; audio
  focus and headset/bluetooth behaviour; screen-lock and background-survival; upgrade-in-place.
- **Live provider proof**: Audius discovery and public-track streaming, LRCLIB lyrics fetch and
  MusicBrainz enrichment against the real services with recorded timestamps and redacted URLs.
- **Owner-held artefacts**: release signing keystore, store/F-Droid publication, CI execution on
  GitHub runners (the workflow definition is checked locally; it has never run).
- **Performance**: Macrobenchmark startup/jank/memory numbers from a device; no threshold in
  this project may be claimed from a JVM measurement.

## Rule for later sessions (do not drift)

- No task may be marked verified *because of* a JVM test for a deliverable that says "on
  device", "live" or "on an emulator"; the task note must name the substitution explicitly.
- Any file that would have been `device-*.png` or `live-*.txt` must not be fabricated. The
  absence is the record.
- If a device, an emulator or a live provider becomes available, the tasks listed above must be
  re-run for real and the results attached before any release claim. ADR-008 is a deferral, not
  a waiver of the underlying requirement.
- `SPEC/QUALITY_GATES.md` still says a build cannot be *certified complete* without an Android
  runtime. That sentence stands: this project is explicitly **not certifiable complete** while
  this register is non-empty. What ADR-008 permits is continuing to build and to audit the
  machine-verifiable half.
